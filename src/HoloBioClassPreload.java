import java.io.File;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads every HoloBio class from the plugin JAR when a HoloBio plugin starts.
 *
 * Java reads a class from the JAR only the first time it is used. If HoloBio_.jar is replaced
 * while Fiji is running, a class used for the first time afterwards (e.g. Vortex-Legendre)
 * fails with NoClassDefFoundError. Loading them all up front keeps the running session intact.
 */
final class HoloBioClassPreload {
    private static volatile boolean done;

    private HoloBioClassPreload() {}

    static synchronized void preloadAll() {
        if (done) {
            return;
        }
        done = true;
        ClassLoader loader = HoloBioClassPreload.class.getClassLoader();
        try {
            URL src = HoloBioClassPreload.class.getProtectionDomain().getCodeSource().getLocation();
            File jar = new File(src.toURI());
            if (!jar.isFile()) {
                return;                      // running from a classes folder: nothing to protect
            }
            try (JarFile jf = new JarFile(jar)) {
                Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    String name = en.nextElement().getName();
                    // Only HoloBio's own top-level-package classes; bundled libraries load on demand.
                    if (!name.endsWith(".class") || name.indexOf('/') >= 0 || !name.startsWith("HoloBio")) {
                        continue;
                    }
                    try {
                        Class.forName(name.substring(0, name.length() - 6), false, loader);
                    } catch (Throwable ignored) {
                        // a class that cannot load here would fail later anyway; keep going
                    }
                }
            }
        } catch (Throwable ignored) {
            // preloading is a safeguard only
        }
    }
}
