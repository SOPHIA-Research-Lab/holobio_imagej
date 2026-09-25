import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Live camera settings (exposure, gain, …) through Windows DirectShow, with no extra
 * dependencies: JNA ships with Fiji, and the two interfaces used here — {@code IAMCameraControl}
 * (exposure) and {@code IAMVideoProcAmp} (gain) — are the standard ones every UVC
 * webcam and DirectShow-driven scientific camera (The Imaging Source included) implements.
 *
 * <p>Settings are device-level, so they apply to the live stream opened by the webcam
 * backend; this class only opens a second, non-streaming handle to the same device.
 *
 * <p>All COM work runs on one dedicated thread that owns the COM apartment, so callers on
 * the EDT or anywhere else never touch COM directly.
 */
public final class HoloBioDShowCamera {

    // ── Property identifiers (strmif.h) ─────────────────────────────────────
    /** Which DirectShow interface a property lives on. */
    public enum Group { CAMERA_CONTROL, VIDEO_PROC_AMP }

    /** The properties HoloBio exposes. Exposure is log2(seconds): -6 is 1/64 s. */
    public enum Prop {
        EXPOSURE("Exposure", Group.CAMERA_CONTROL, 4),
        GAIN("Gain", Group.VIDEO_PROC_AMP, 9);

        public final String label;
        final Group group;
        final int id;

        Prop(String label, Group group, int id) {
            this.label = label;
            this.group = group;
            this.id = id;
        }
    }

    /** Range and current state of one property, as reported by the device. */
    public static final class Range {
        public final Prop prop;
        public final int min, max, step, def;
        public final boolean canAuto, canManual;
        public final int value;
        public final boolean auto;

        Range(Prop prop, int min, int max, int step, int def, int caps, int value, int flags) {
            this.prop = prop;
            this.min = min;
            this.max = max;
            this.step = Math.max(1, step);
            this.def = def;
            this.canAuto = (caps & FLAG_AUTO) != 0;
            this.canManual = (caps & FLAG_MANUAL) != 0;
            this.value = value;
            this.auto = (flags & FLAG_AUTO) != 0;
        }

        @Override
        public String toString() {
            return String.format("%s: %d [%d..%d step %d, default %d] %s%s", prop.label, value,
                    min, max, step, def, auto ? "AUTO" : "manual",
                    canAuto ? "" : " (no auto)");
        }
    }

    private static final int FLAG_AUTO = 1;
    private static final int FLAG_MANUAL = 2;

    // ── COM plumbing ────────────────────────────────────────────────────────
    public interface Ole32 extends Library {
        Ole32 INSTANCE = Native.load("ole32", Ole32.class);

        int CoInitializeEx(Pointer reserved, int coInit);
        int CoCreateInstance(Guid clsid, Pointer outer, int ctx, Guid iid, PointerByReference out);
    }

    public interface OleAut32 extends Library {
        OleAut32 INSTANCE = Native.load("oleaut32", OleAut32.class);

        int VariantClear(Pointer variant);
    }

    @Structure.FieldOrder({ "data1", "data2", "data3", "data4" })
    public static class Guid extends Structure {
        public int data1;
        public short data2;
        public short data3;
        public byte[] data4 = new byte[8];

        public Guid() {
        }

        Guid(String s) {
            String h = s.replace("{", "").replace("}", "").replace("-", "");
            data1 = (int) Long.parseLong(h.substring(0, 8), 16);
            data2 = (short) Integer.parseInt(h.substring(8, 12), 16);
            data3 = (short) Integer.parseInt(h.substring(12, 16), 16);
            for (int i = 0; i < 8; i++) {
                data4[i] = (byte) Integer.parseInt(h.substring(16 + 2 * i, 18 + 2 * i), 16);
            }
            write();
        }
    }

    private static final Guid CLSID_SystemDeviceEnum   = new Guid("62BE5D10-60EB-11d0-BD3B-00A0C911CE86");
    private static final Guid IID_ICreateDevEnum       = new Guid("29840822-5B84-11D0-BD3B-00A0C911CE86");
    private static final Guid CLSID_VideoInputCategory = new Guid("860BB310-5D01-11d0-BD3B-00A0C911CE86");
    private static final Guid IID_IPropertyBag         = new Guid("55272A00-42CB-11CE-8135-00AA004BB851");
    private static final Guid IID_IBaseFilter          = new Guid("56a86895-0ad4-11ce-b03a-0020af0ba770");
    private static final Guid IID_IAMCameraControl     = new Guid("C6E13370-30AC-11d0-A18C-00A0C9118956");
    private static final Guid IID_IAMVideoProcAmp      = new Guid("C6E13360-30AC-11d0-A18C-00A0C9118956");

    private static final int CLSCTX_INPROC_SERVER = 1;
    private static final int COINIT_APARTMENTTHREADED = 2;
    private static final short VT_BSTR = 8;

    /** Invoke vtable slot {@code index} of COM object {@code obj}. */
    private static int call(Pointer obj, int index, Object... args) {
        Pointer vtbl = obj.getPointer(0);
        Pointer fn = vtbl.getPointer((long) index * Native.POINTER_SIZE);
        Object[] full = new Object[args.length + 1];
        full[0] = obj;
        System.arraycopy(args, 0, full, 1, args.length);
        return Function.getFunction(fn, Function.ALT_CONVENTION).invokeInt(full);
    }

    private static void release(Pointer obj) {
        if (obj != null) {
            call(obj, 2);
        }
    }

    private static Pointer query(Pointer obj, Guid iid) {
        PointerByReference out = new PointerByReference();
        return call(obj, 0, iid, out) == 0 ? out.getValue() : null;
    }

    // ── The COM thread ──────────────────────────────────────────────────────
    private static final ExecutorService COM = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED);
            r.run();
        }, "HoloBio-DirectShow");
        t.setDaemon(true);
        return t;
    });

    private static <T> T onCom(Callable<T> task) throws Exception {
        Future<T> f = COM.submit(task);
        try {
            return f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            throw c instanceof Exception ? (Exception) c : new RuntimeException(c);
        }
    }

    /** True when DirectShow is reachable (Windows with ole32). */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Friendly names of the video capture devices DirectShow sees, in enumeration order. */
    public static List<String> listDevices() throws Exception {
        return onCom(() -> {
            List<String> names = new ArrayList<>();
            forEachDevice((name, moniker) -> {
                names.add(name);
                return false;
            });
            return names;
        });
    }

    private interface DeviceVisitor {
        /** Return true to stop enumerating. */
        boolean visit(String friendlyName, Pointer moniker) throws Exception;
    }

    private static void forEachDevice(DeviceVisitor v) throws Exception {
        PointerByReference devEnumRef = new PointerByReference();
        int hr = Ole32.INSTANCE.CoCreateInstance(CLSID_SystemDeviceEnum, null,
                CLSCTX_INPROC_SERVER, IID_ICreateDevEnum, devEnumRef);
        if (hr != 0) {
            throw new IllegalStateException(String.format("DirectShow unavailable (0x%08X)", hr));
        }
        Pointer devEnum = devEnumRef.getValue();
        PointerByReference enumRef = new PointerByReference();
        hr = call(devEnum, 3, CLSID_VideoInputCategory, enumRef, 0);   // CreateClassEnumerator
        release(devEnum);
        if (hr != 0 || enumRef.getValue() == null) {
            return;                                                     // S_FALSE: no devices
        }
        Pointer enumMon = enumRef.getValue();
        try {
            PointerByReference monRef = new PointerByReference();
            while (call(enumMon, 3, 1, monRef, null) == 0) {            // IEnumMoniker::Next
                Pointer moniker = monRef.getValue();
                try {
                    if (v.visit(friendlyName(moniker), moniker)) {
                        return;
                    }
                } finally {
                    release(moniker);
                }
            }
        } finally {
            release(enumMon);
        }
    }

    private static String friendlyName(Pointer moniker) {
        PointerByReference bagRef = new PointerByReference();
        if (call(moniker, 9, null, null, IID_IPropertyBag, bagRef) != 0) {   // BindToStorage
            return "?";
        }
        Pointer bag = bagRef.getValue();
        Memory variant = new Memory(24);                                    // VARIANT (x64)
        variant.clear();
        variant.setShort(0, VT_BSTR);
        try {
            if (call(bag, 3, new WString("FriendlyName"), variant, null) != 0) { // Read
                return "?";
            }
            Pointer bstr = variant.getPointer(8);
            return bstr == null ? "?" : bstr.getWideString(0);
        } finally {
            OleAut32.INSTANCE.VariantClear(variant);
            release(bag);
        }
    }

    // ── One opened device ───────────────────────────────────────────────────
    private final String name;
    private Pointer filter;
    private Pointer cameraControl;
    private Pointer procAmp;

    private HoloBioDShowCamera(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * Open control over the device whose DirectShow name matches {@code hint} (exact,
     * then prefix — sarxos appends " 0", " 1" … to names). Null when nothing matches.
     */
    public static HoloBioDShowCamera open(String hint) throws Exception {
        return onCom(() -> {
            HoloBioDShowCamera[] found = new HoloBioDShowCamera[1];
            String h = hint == null ? "" : hint.trim();
            for (int pass = 0; pass < 2 && found[0] == null; pass++) {
                final boolean exact = pass == 0;
                forEachDevice((fname, moniker) -> {
                    boolean match = exact ? fname.equalsIgnoreCase(h)
                            : !fname.isEmpty() && h.toLowerCase().startsWith(fname.toLowerCase());
                    if (!match) {
                        return false;
                    }
                    PointerByReference fRef = new PointerByReference();
                    if (call(moniker, 8, null, null, IID_IBaseFilter, fRef) != 0) {  // BindToObject
                        return false;
                    }
                    HoloBioDShowCamera cam = new HoloBioDShowCamera(fname);
                    cam.filter = fRef.getValue();
                    cam.cameraControl = query(cam.filter, IID_IAMCameraControl);
                    cam.procAmp = query(cam.filter, IID_IAMVideoProcAmp);
                    found[0] = cam;
                    return true;
                });
            }
            return found[0];
        });
    }

    private Pointer iface(Prop p) {
        return p.group == Group.CAMERA_CONTROL ? cameraControl : procAmp;
    }

    /** The device's range and current value for {@code p}, or null if unsupported. */
    public Range range(Prop p) throws Exception {
        return onCom(() -> {
            Pointer i = iface(p);
            if (i == null) {
                return null;
            }
            IntByReference min = new IntByReference(), max = new IntByReference(),
                    step = new IntByReference(), def = new IntByReference(),
                    caps = new IntByReference(), val = new IntByReference(),
                    flags = new IntByReference();
            if (call(i, 3, p.id, min, max, step, def, caps) != 0) {            // GetRange
                return null;
            }
            if (call(i, 5, p.id, val, flags) != 0) {                           // Get
                return null;
            }
            return new Range(p, min.getValue(), max.getValue(), step.getValue(),
                    def.getValue(), caps.getValue(), val.getValue(), flags.getValue());
        });
    }

    /**
     * Set a manual value, or hand control back to the camera with {@code auto}. Returns the
     * state the device reports afterwards — cameras clamp and round, so show this, not the
     * requested value.
     */
    public Range set(Prop p, int value, boolean auto) throws Exception {
        onCom(() -> {
            Pointer i = iface(p);
            if (i == null) {
                throw new IllegalStateException(p.label + " is not supported by " + name);
            }
            int hr = call(i, 4, p.id, value, auto ? FLAG_AUTO : FLAG_MANUAL);   // Set
            if (hr != 0) {
                throw new IllegalStateException(String.format("%s rejected (0x%08X)", p.label, hr));
            }
            return null;
        });
        return range(p);
    }

    public void close() {
        try {
            onCom(() -> {
                release(cameraControl);
                release(procAmp);
                release(filter);
                cameraControl = procAmp = filter = null;
                return null;
            });
        } catch (Exception ignored) {
            // releasing is best-effort
        }
    }

    /** Diagnostic: {@code java HoloBioDShowCamera} lists devices and their settings. */
    public static void main(String[] args) throws Exception {
        for (String d : listDevices()) {
            System.out.println("device: " + d);
            HoloBioDShowCamera c = open(d);
            for (Prop p : Prop.values()) {
                Range r = c.range(p);
                System.out.println("   " + (r == null ? p.label + ": not supported" : r));
            }
            c.close();
        }
        System.out.println(Arrays.toString(Prop.values()));
    }
}
