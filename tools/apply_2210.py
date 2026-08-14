from pathlib import Path

java_path = Path('app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java')
boot_path = Path('app/src/main/java/vn/com/vhb/qmsdisplay/BootReceiver.java')
manifest_path = Path('app/src/main/AndroidManifest.xml')
gradle_path = Path('app/build.gradle')

src = java_path.read_text(encoding='utf-8')

src = src.replace('private static final String VERSION = "2.2.9";', 'private static final String VERSION = "2.2.10";')
src = src.replace('VHB QMS Display 2.2.9 • FAST BOOT RECOVERY', 'VHB QMS Display 2.2.10 • HYBRID FAST BOOT')

# 2.2.8/2.2.9 moved the full application configuration into device-protected
# storage and also created GeckoRuntime in Application. PANAPLAYBOX boots fast
# with that layout but Gecko never reaches the working loadUri path. 2.2.10 uses
# Direct Boot only to draw the VHB splash early; actual URL/Gecko startup returns
# to the proven 2.2.7 credential-storage/runtime path.
old_fields = '''    private long retryDelayMs = RETRY_MIN_MS;\n    private long geckoBootstrapDelayMs = 250L;\n    private boolean geckoBootstrapScheduled;\n'''
new_fields = '''    private long retryDelayMs = RETRY_MIN_MS;\n    private long operationalRetryDelayMs = 500L;\n    private boolean operationalStartScheduled;\n    private boolean operationalStarted;\n'''
if old_fields not in src:
    raise SystemExit('Could not find 2.2.9 bootstrap fields')
src = src.replace(old_fields, new_fields, 1)

old_prefs = '''        Context configContext = createDeviceProtectedStorageContext();\n        if (VhbApp.isUserUnlocked(this)) {\n            try { configContext.moveSharedPreferencesFrom(this, PREFS); } catch (Throwable ignored) { }\n        }\n        prefs = configContext.getSharedPreferences(PREFS, MODE_PRIVATE);\n'''
if old_prefs not in src:
    raise SystemExit('Could not find 2.2.8 device-protected prefs init')
src = src.replace(old_prefs, '        prefs = null;\n', 1)

old_post = '        root.post(this::startGecko);\n'
new_post = '''        boolean earlyBootOnly = getIntent() != null\n                && getIntent().getBooleanExtra("vhb_early_boot", false)\n                && !getIntent().getBooleanExtra("vhb_boot_ready", false);\n        if (earlyBootOnly) {\n            // Keep the fast VHB first frame, but do NOT touch credential prefs/Gecko yet.\n            // BOOT_COMPLETED/USER_UNLOCKED will re-enter through onNewIntent().\n            main.postDelayed(this::startOperationalMode, 3500L);\n        } else {\n            root.post(this::startOperationalMode);\n        }\n'''
if old_post not in src:
    raise SystemExit('Could not find root.post startGecko')
src = src.replace(old_post, new_post, 1)

# Replace onNewIntent completely: a boot-ready intent is the primary hand-off
# from fast Direct Boot splash to the known-good Gecko startup path.
start = src.index('    @Override protected void onNewIntent(Intent intent) {')
end = src.index('    private void startGecko() {', start)
new_on_new_intent = r'''    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        immersive();

        boolean bootReady = intent != null && intent.getBooleanExtra("vhb_boot_ready", false);
        if (bootReady || session == null) {
            operationalRetryDelayMs = 500L;
            startOperationalMode();
        } else {
            try { session.setActive(true); } catch (Throwable ignored) { }
        }
    }

'''
src = src[:start] + new_on_new_intent + src[end:]

# Replace all 2.2.9 bootstrap helpers up to ensureRuntime. This deliberately
# restores Gecko startup semantics from 2.2.7 while adding a full-start retry
# for the short interval between LOCKED_BOOT_COMPLETED and BOOT_COMPLETED.
start = src.index('    private void startGecko() {')
end = src.index('    private void ensureRuntime() {', start)
new_start_region = r'''    private void startGecko() {
        startOperationalMode();
    }

    private void startOperationalMode() {
        operationalStartScheduled = false;
        if (operationalStarted && session != null) return;

        try {
            initializeOperationalPrefs();
            ensureRuntime();
            createSession();
            loadConfigured();
            operationalStarted = true;
            operationalRetryDelayMs = 500L;
        } catch (Throwable ignored) {
            showConnecting();
            scheduleOperationalStart();
        }
    }

    private void scheduleOperationalStart() {
        if (operationalStartScheduled || isFinishing() || isDestroyed()) return;
        operationalStartScheduled = true;
        long delay = operationalRetryDelayMs;
        operationalRetryDelayMs = Math.min(2000L, operationalRetryDelayMs * 2L);
        main.postDelayed(() -> {
            operationalStartScheduled = false;
            startOperationalMode();
        }, delay);
    }

    private void initializeOperationalPrefs() {
        if (prefs != null) return;

        // Proven 2.2.7 storage path: regular credential-protected SharedPreferences.
        SharedPreferences normal = getSharedPreferences(PREFS, MODE_PRIVATE);

        // Migration safety for boxes that already ran 2.2.8/2.2.9, where URL/PIN
        // may have been moved into device-protected storage.
        try {
            String normalUrl = normal.getString("url", "");
            if (normalUrl == null || normalUrl.trim().isEmpty()) {
                Context dpContext = createDeviceProtectedStorageContext();
                SharedPreferences dp = dpContext.getSharedPreferences(PREFS, MODE_PRIVATE);
                String dpUrl = dp.getString("url", "");
                if (dpUrl != null && !dpUrl.trim().isEmpty()) {
                    normal.edit()
                            .putString("url", dpUrl)
                            .putString("admin_pin", dp.getString("admin_pin", "1234"))
                            .putBoolean("auto_start", dp.getBoolean("auto_start", true))
                            .apply();
                }
            }
        } catch (Throwable ignored) { }

        prefs = normal;
        syncBootFlags(prefs.getBoolean("auto_start", true));
    }

'''
src = src[:start] + new_start_region + src[end:]

# Restore the exact runtime pattern that worked before Direct Boot changes.
old_runtime = '''    private void ensureRuntime() {\n        runtime = VhbApp.getRuntime(getApplicationContext());\n    }\n'''
new_runtime = '''    private void ensureRuntime() {\n        if (runtime == null) {\n            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()\n                    .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)\n                    .remoteDebuggingEnabled(false)\n                    .consoleOutput(false)\n                    .build();\n            runtime = GeckoRuntime.create(getApplicationContext(), settings);\n        }\n        runtime.warmUp();\n    }\n'''
if old_runtime not in src:
    raise SystemExit('Could not find VhbApp ensureRuntime')
src = src.replace(old_runtime, new_runtime, 1)

# Make PIN/admin safe even if opened during the early splash phase.
show_pin_marker = '    private void showPin() {\n'
show_pin_repl = '''    private void showPin() {\n        if (prefs == null) {\n            try { initializeOperationalPrefs(); } catch (Throwable ignored) { }\n        }\n        if (prefs == null) {\n            Toast.makeText(this, "Hệ thống đang khởi động, vui lòng thử lại sau vài giây", Toast.LENGTH_SHORT).show();\n            return;\n        }\n'''
if show_pin_marker not in src:
    raise SystemExit('Could not find showPin')
src = src.replace(show_pin_marker, show_pin_repl, 1)

# Mirror only the boot enable flag to Direct-Boot storage; URL/PIN stay in the
# known-good normal storage path.
save_marker = '''                    prefs.edit()\n                            .putString("url", normalized)\n                            .putString("admin_pin", newPin)\n                            .putBoolean("auto_start", auto.isChecked())\n                            .apply();\n'''
save_repl = save_marker + '                    syncBootFlags(auto.isChecked());\n'
if save_marker not in src:
    raise SystemExit('Could not find admin save block')
src = src.replace(save_marker, save_repl, 1)

# Add the boot-flag helper before the Exit helper retained from 2.2.9.
exit_marker = '    private void exitApplication() {'
boot_helper = r'''    private void syncBootFlags(boolean enabled) {
        try {
            Context dp = createDeviceProtectedStorageContext();
            dp.getSharedPreferences("vhb_qms_boot_flags", MODE_PRIVATE)
                    .edit()
                    .putBoolean("auto_start", enabled)
                    .apply();
        } catch (Throwable ignored) { }
    }

'''
if exit_marker not in src:
    raise SystemExit('Could not find 2.2.9 exitApplication')
src = src.replace(exit_marker, boot_helper + exit_marker, 1)

# configuredUrl must tolerate the few seconds where only the early splash exists.
old_configured = '''    private String configuredUrl() {\n        return normalize(prefs.getString("url", ""));\n    }\n'''
new_configured = '''    private String configuredUrl() {\n        if (prefs == null) return "";\n        return normalize(prefs.getString("url", ""));\n    }\n'''
if old_configured not in src:
    raise SystemExit('Could not find configuredUrl')
src = src.replace(old_configured, new_configured, 1)

# Replace 2.2.9 onResume bootstrap logic with a simple recovery into the proven path.
start = src.index('    @Override protected void onResume() {')
end = src.index('    @Override protected void onPause() {', start)
new_resume = r'''    @Override protected void onResume() {
        super.onResume();
        immersive();
        if (session != null) {
            try { session.setActive(true); } catch (Throwable ignored) { }
        } else if (prefs != null || (getIntent() != null && getIntent().getBooleanExtra("vhb_boot_ready", false))) {
            startOperationalMode();
        }
    }

'''
src = src[:start] + new_resume + src[end:]

java_path.write_text(src, encoding='utf-8')

# Two-phase BootReceiver:
#  - LOCKED_BOOT_COMPLETED: show VHB immediately, no URL/Gecko access.
#  - BOOT_COMPLETED/USER_UNLOCKED: send a boot-ready intent to the same singleTask
#    Activity, which starts the exact 2.2.7-style Gecko path.
boot_path.write_text(r'''package vn.com.vhb.qmsdisplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

public final class BootReceiver extends BroadcastReceiver {
    private static final String BOOT_PREFS = "vhb_qms_boot_flags";

    @Override public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        Context dp = app.createDeviceProtectedStorageContext();
        SharedPreferences bootPrefs = dp.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE);
        if (!bootPrefs.getBoolean("auto_start", true)) return;
        if (isDefaultHome(app)) return;

        String action = intent != null ? intent.getAction() : "";
        boolean early = Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        boolean ready = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action);

        if (!early && !ready) return;

        // Suppress vendor duplicate broadcasts, but never let an early launch block
        // the later BOOT_COMPLETED hand-off that actually starts Gecko.
        long now = SystemClock.elapsedRealtime();
        String key = ready ? "last_ready_launch" : "last_early_launch";
        long last = bootPrefs.getLong(key, -1L);
        if (last >= 0L && now >= last && (now - last) < (ready ? 1500L : 10000L)) return;
        bootPrefs.edit().putLong(key, now).apply();

        try {
            Intent launch = new Intent(app, MainActivity.class);
            launch.putExtra("vhb_early_boot", early);
            launch.putExtra("vhb_boot_ready", ready);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            app.startActivity(launch);
        } catch (Throwable ignored) { }
    }

    private boolean isDefaultHome(Context context) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return info != null && info.activityInfo != null
                    && context.getPackageName().equals(info.activityInfo.packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
''', encoding='utf-8')

# Keep Direct-Boot awareness for the early splash, but stop VhbApp from creating
# GeckoRuntime before BOOT_COMPLETED. This is the key rollback to the working engine path.
manifest = manifest_path.read_text(encoding='utf-8')
manifest = manifest.replace('        android:name="vn.com.vhb.qmsdisplay.VhbApp"\n', '', 1)
manifest_path.write_text(manifest, encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
gradle = gradle.replace("versionCode 2020900", "versionCode 2021000")
gradle = gradle.replace("versionName '2.2.9'", "versionName '2.2.10'")
gradle_path.write_text(gradle, encoding='utf-8')

print('Applied VHB QMS Display 2.2.10 Hybrid Fast Boot + proven Gecko path')
