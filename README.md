# HoloBio — Fiji Plugin

Digital holographic microscopy (DHM) and lensless digital holographic microscopy (DLHM)
for Fiji, with both offline post-processing and live camera reconstruction.

Ported from the HoloBio Python (https://sophia-research-lab.github.io/HoloBio/) platform; the reconstruction math follows the Python
reference implementations (`pyDHM_methods`, `phaseShifting`, `parallel_rc`).

## Modules

The plugin installs four modules under **Plugins ▸ HoloBio**:

| Command | What it does |
|---|---|
| **Offline DHM** | Off-axis DHM on a loaded hologram: phase compensation (ERS / CFS / Vortex–Legendre), phase shifting (SOSR, BPS2/3, PS3/4/5), numerical propagation (angular spectrum / Fresnel) with z-scan and autofocus |
| **Offline DLHM** | Lensless in-line reconstruction (angular spectrum, DLHM-rec, Kreuzer) from a hologram plus optional reference |
| **Real-Time DHM** | Live off-axis reconstruction from a camera or video file, with FT view and filter control, live phase profiles, video recording, complex-field recording, and live camera exposure / gain |
| **Real-Time DLHM** | Live lensless reconstruction from a camera or video file, with video recording, complex-field recording, and live camera exposure / gain |

Analysis tools (**QPI** and **Speckle**) open from the Offline modules' *Analysis tools*
menu: phase profiles and speckle

## Requirements

- [Fiji](https://fiji.sc/) — the bundled Java 8 build is what this targets
- A webcam or video file for the real-time modules 
- To **build from source**: the JDK inside your Fiji install
  (`Fiji.app/java/<platform>/<jdk>/bin/javac`)

## Install

### Pre-built JAR

1. Download `HoloBio_.jar` from this repository.
2. Copy it into your Fiji plugins folder:
   - **Windows:** `Fiji.app\plugins\HoloBio_.jar`
   - **macOS / Linux:** `Fiji.app/plugins/HoloBio_.jar`
3. For the real-time camera modules, also copy `lib/webcam-capture-0.3.12.jar` and
   `lib/bridj-0.7.0.jar` into `Fiji.app/jars/`. 
4. Restart Fiji.

## Use

### Offline

1. **File ▸ Open** your hologram.
2. **Plugins ▸ HoloBio ▸ Offline DHM** (or **Offline DLHM**).
3. **Use Active Image** to link the open image.
4. Pick a submodule, set wavelength and pixel pitch in µm, and run the pipeline buttons.
5. **Open All in Fiji** sends amplitude / phase / FT to normal Fiji windows; **Save**
   writes FT, phase or amplitude as TIFF, or the reconstructed complex field as a NumPy
   `.npy` (`complex64`).

### Real-time

1. **Plugins ▸ HoloBio ▸ Real-Time DHM** (or **Real-Time DLHM**).
2. In **Capture**, choose *Camera* (then **Refresh**) or *Video* (then **Browse…**).
3. Set the values in **Optics**, then press **Start**.
4. **Record** captures the selected product (phase, amplitude, or hologram) to an MP4.
   **Record complex fields** saves every reconstructed field until you stop, as one
   NumPy `.npz` (`frame_00000`, `frame_00001`, …). CAN BE HEAVY
5. **Snap to Fiji** sends the current view to a normal Fiji window so you can apply Fiji tools to it.
6. With a camera running  a **Camera** section appears under Capture with
   **Exposure** (and *Auto*) and **Gain**. It starts from whatever the camera currently
   holds and changes nothing until you move a control. Settings are stored by the camera
   driver, not by HoloBio. IN PLUGIN GAIN AND EXPOSURE LIVE CONFIG ONLY WORKS IN WINDOWS

## Project layout

| Path | Purpose |
|------|---------|
| `src/` | Java source and `plugins.config` (the menu definition) |
| `lib/` | Third-party JARs needed to build and to run the camera backends |
| `build.ps1` / `build.bat` | Auto compile, self-check, and install into Fiji |
| `HoloBio_.jar` | Pre-built plugin |

## Third-party

- [jcobyla](https://github.com/cureos/jcobyla) — COBYLA optimiser, bundled into the plugin JAR
- [webcam-capture](https://github.com/sarxos/webcam-capture) + [BridJ](https://github.com/nativelibs4java/BridJ) — camera access for the real-time modules
- [JNA](https://github.com/java-native-access/jna) — Windows DirectShow exposure / gain control (compile-time only; provided by Fiji)

---

### Extra: Adding your own code and fast compiling (Windows)

If you want to add your own functions and don't want to build and copy paste the .jar into Fiji everytime, you can use the build.bat or build.ps1 scripts for fast builts.

0. Add whatever code you want to .java files under /src
1. Open `build.ps1` and set `$FijiDir` to your Fiji folder, and `$Javac` / `$Jar` /
   `$IjJar` to match the JDK and `ij-*.jar` versions your Fiji ships.
2. Run:
   ```powershell
   .\build.ps1
   ```
   (or double-click `build.bat`)
3. The script compiles `src/`, bundles the jcobyla classes, runs a self-check, then
   installs `HoloBio_.jar` into `Fiji.app\plugins\`, copies the webcam runtime JARs into
   `Fiji.app\jars\`, and installs `HoloBio-core.jar`. It will print every step and tell you if it was successful
4. Restart Fiji.
   
Nancy Burgos, 2026. Contact me: ncburgosb@eafit.edu.co
