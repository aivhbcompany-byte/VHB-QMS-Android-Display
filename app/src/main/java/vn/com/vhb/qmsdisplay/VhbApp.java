package vn.com.vhb.qmsdisplay;

import android.app.Application;
import android.content.Context;
import android.os.UserManager;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/**
 * Process-level Gecko runtime holder.
 *
 * The application process can be created by BootReceiver before MainActivity.
 * When the Android user is already unlocked we warm Gecko here so MainActivity
 * can reuse the same runtime instead of constructing a second engine.
 */
public final class VhbApp extends Application {
    private static volatile GeckoRuntime runtime;

    @Override public void onCreate() {
        super.onCreate();
        if (isUserUnlocked(this)) {
            try { getRuntime(this); } catch (Throwable ignored) { }
        }
    }

    public static GeckoRuntime getRuntime(Context context) {
        GeckoRuntime local = runtime;
        if (local != null) return local;

        synchronized (VhbApp.class) {
            local = runtime;
            if (local == null) {
                GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                        .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
                        .remoteDebuggingEnabled(false)
                        .consoleOutput(false)
                        .build();
                local = GeckoRuntime.create(context.getApplicationContext(), settings);
                try { local.warmUp(); } catch (Throwable ignored) { }
                runtime = local;
            }
        }
        return local;
    }

    public static boolean isUserUnlocked(Context context) {
        try {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return um == null || um.isUserUnlocked();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
