package opt.internal;

import com.google.ortools.Loader;

/** Loads the OR-Tools native library exactly once, from whichever entry point runs first. */
public final class NativeLoader {

    private static volatile boolean loaded;

    /** Loads the native library if it has not been loaded yet; thread-safe and idempotent. */
    public static void ensureLoaded() {
        if (!loaded) {
            synchronized (NativeLoader.class) {
                if (!loaded) {
                    Loader.loadNativeLibraries();
                    loaded = true;
                }
            }
        }
    }

    private NativeLoader() {}
}
