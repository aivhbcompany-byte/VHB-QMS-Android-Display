from pathlib import Path

java_path = Path('app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java')
boot_path = Path('app/src/main/java/vn/com/vhb/qmsdisplay/BootReceiver.java')
gradle_path = Path('app/build.gradle')

src = java_path.read_text(encoding='utf-8')

src = src.replace('private static final String VERSION = "2.2.8";', 'private static final String VERSION = "2.2.9";')
src = src.replace('VHB QMS Display 2.2.8 • FAST DIRECT BOOT', 'VHB QMS Display 2.2.9 • FAST BOOT RECOVERY')

# Add independent Gecko-bootstrap backoff. This is intentionally separate from
# page reload retry because page retry cannot recover when GeckoSession is null.
field_marker = '    private long retryDelayMs = RETRY_MIN_MS;\n'
field_replacement = '''    private long retryDelayMs = RETRY_MIN_MS;\n    private long geckoBootstrapDelayMs = 250L;\n    private boolean geckoBootstrapScheduled;\n'''
if field_marker not in src:
    raise SystemExit('Could not find retryDelayMs field')
src = src.replace(field_marker, field_replacement, 1)

old_start = '''    private void startGecko() {\n        // Direct Boot can launch the Activity before credential storage/user services are ready.\n        // Draw the VHB screen immediately, then start Gecko the instant user 0 is unlocked.\n        if (!VhbApp.isUserUnlocked(this)) {\n            showConnecting();\n            main.postDelayed(this::startGecko, 250L);\n            return;\n        }\n        try {\n            ensureRuntime();\n            createSession();\n            loadConfigured();\n        } catch (Throwable ignored) {\n            showConnecting();\n            scheduleRetry();\n        }\n    }\n'''
new_start = '''    private void startGecko() {\n        geckoBootstrapScheduled = false;\n\n        // PANAPLAYBOX firmware may report UserManager.isUserUnlocked() incorrectly.\n        // Do not gate Gecko on that flag. Try immediately and retry the complete\n        // Gecko bootstrap until credential-backed Gecko services become available.\n        try {\n            refreshLegacyPrefsAfterUnlock();\n            ensureRuntime();\n            createSession();\n            loadConfigured();\n            geckoBootstrapDelayMs = 250L;\n        } catch (Throwable error) {\n            showConnecting();\n            scheduleGeckoBootstrap();\n        }\n    }\n\n    private void scheduleGeckoBootstrap() {\n        if (geckoBootstrapScheduled || isFinishing() || isDestroyed()) return;\n        geckoBootstrapScheduled = true;\n        long delay = geckoBootstrapDelayMs;\n        geckoBootstrapDelayMs = Math.min(2000L, geckoBootstrapDelayMs * 2L);\n        main.postDelayed(() -> {\n            geckoBootstrapScheduled = false;\n            startGecko();\n        }, delay);\n    }\n\n    private void refreshLegacyPrefsAfterUnlock() {\n        // 2.2.8 can be launched before credential storage is available. If this is\n        // an upgrade from an older build and the device-protected prefs are still\n        // empty, migrate the old settings as soon as Android exposes them.\n        try {\n            String currentUrl = prefs != null ? prefs.getString("url", "") : "";\n            if ((currentUrl == null || currentUrl.trim().isEmpty()) && VhbApp.isUserUnlocked(this)) {\n                Context storage = createDeviceProtectedStorageContext();\n                try { storage.moveSharedPreferencesFrom(this, PREFS); } catch (Throwable ignored) { }\n                prefs = storage.getSharedPreferences(PREFS, MODE_PRIVATE);\n            }\n        } catch (Throwable ignored) { }\n    }\n'''
if old_start not in src:
    raise SystemExit('Could not find 2.2.8 startGecko')
src = src.replace(old_start, new_start, 1)

# USER_UNLOCKED delivered while this singleTask Activity is already alive must
# retrigger bootstrap when no Gecko session exists.
old_new_intent = '''        if (session != null) {\n            try { session.setActive(true); } catch (Throwable ignored) { }\n        }\n'''
new_new_intent = '''        if (session != null) {\n            try { session.setActive(true); } catch (Throwable ignored) { }\n        } else {\n            geckoBootstrapDelayMs = 250L;\n            startGecko();\n        }\n'''
if old_new_intent not in src:
    raise SystemExit('Could not find onNewIntent session block')
src = src.replace(old_new_intent, new_new_intent, 1)

# Resume is another safe recovery point for vendor firmware that suppresses USER_UNLOCKED.
old_resume = '''        if (session != null) {\n            try { session.setActive(true); } catch (Throwable ignored) { }\n        }\n    }\n\n    @Override protected void onPause() {\n'''
new_resume = '''        if (session != null) {\n            try { session.setActive(true); } catch (Throwable ignored) { }\n        } else if (!configuredUrl().isEmpty()) {\n            geckoBootstrapDelayMs = 250L;\n            startGecko();\n        }\n    }\n\n    @Override protected void onPause() {\n'''
if old_resume not in src:
    raise SystemExit('Could not find onResume block')
src = src.replace(old_resume, new_resume, 1)

# Add a temporary Exit button. It does NOT disable auto-start for the next reboot.
admin_settings = '''        Button settings = new Button(this);\n        settings.setText("Mở cài đặt Android");\n        settings.setOnClickListener(v -> {\n            url.clearFocus();\n            pin.clearFocus();\n            hideKeyboard(v);\n            openAndroidSettings();\n        });\n'''
admin_settings_new = admin_settings + '''\n        Button exitApp = new Button(this);\n        exitApp.setText("THOÁT ỨNG DỤNG");\n        exitApp.setOnClickListener(v -> {\n            url.clearFocus();\n            pin.clearFocus();\n            hideKeyboard(v);\n            new AlertDialog.Builder(this)\n                    .setTitle("Thoát VHB QMS")\n                    .setMessage("Thoát về giao diện Android? Ứng dụng vẫn tự khởi động ở lần bật máy tiếp theo.")\n                    .setPositiveButton("THOÁT", (d, w) -> exitApplication())\n                    .setNegativeButton("Hủy", null)\n                    .show();\n        });\n'''
if admin_settings not in src:
    raise SystemExit('Could not find settings button in 2.2.8 admin')
src = src.replace(admin_settings, admin_settings_new, 1)

layout_marker = '''        box.addView(bootInfo);\n        box.addView(settings);\n'''
layout_new = '''        box.addView(bootInfo);\n        box.addView(settings);\n        box.addView(exitApp);\n'''
if layout_marker not in src:
    raise SystemExit('Could not find admin layout marker')
src = src.replace(layout_marker, layout_new, 1)

# Add exit helper before Android settings helper.
settings_helper_marker = '    private void openAndroidSettings() {'
exit_helper = r'''    private void exitApplication() {
        try { main.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
        try {
            if (cm != null && networkCallback != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (Throwable ignored) { }
        networkCallback = null;

        try { if (session != null) session.setActive(false); } catch (Throwable ignored) { }
        try { if (view != null) view.releaseSession(); } catch (Throwable ignored) { }
        try { if (session != null) session.close(); } catch (Throwable ignored) { }
        session = null;
        view = null;

        try { finishAndRemoveTask(); } catch (Throwable ignored) { try { finish(); } catch (Throwable ignored2) { } }

        // Terminate only this app process after the Activity has been removed. BootReceiver
        // will start a fresh process normally on the next device boot.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { android.os.Process.killProcess(android.os.Process.myPid()); } catch (Throwable ignored) { }
        }, 180L);
    }

'''
if settings_helper_marker not in src:
    raise SystemExit('Could not find openAndroidSettings marker')
src = src.replace(settings_helper_marker, exit_helper + settings_helper_marker, 1)

java_path.write_text(src, encoding='utf-8')

# USER_UNLOCKED must bypass duplicate suppression so an already-running singleTask
# MainActivity receives onNewIntent() and retries Gecko immediately.
boot = boot_path.read_text(encoding='utf-8')
old_dup = '''        long now = SystemClock.elapsedRealtime();\n        long last = prefs.getLong("last_boot_launch_elapsed", -1L);\n        if (last >= 0L && now >= last && (now - last) < 60000L) return;\n        prefs.edit().putLong("last_boot_launch_elapsed", now).apply();\n'''
new_dup = '''        boolean userUnlockedEvent = Intent.ACTION_USER_UNLOCKED.equals(intent != null ? intent.getAction() : null);\n        long now = SystemClock.elapsedRealtime();\n        long last = prefs.getLong("last_boot_launch_elapsed", -1L);\n        if (!userUnlockedEvent && last >= 0L && now >= last && (now - last) < 60000L) return;\n        prefs.edit().putLong("last_boot_launch_elapsed", now).apply();\n'''
if old_dup not in boot:
    raise SystemExit('Could not find BootReceiver duplicate suppression')
boot = boot.replace(old_dup, new_dup, 1)
boot_path.write_text(boot, encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
gradle = gradle.replace("versionCode 2020800", "versionCode 2020900")
gradle = gradle.replace("versionName '2.2.8'", "versionName '2.2.9'")
gradle_path.write_text(gradle, encoding='utf-8')

print('Applied VHB QMS Display 2.2.9 Gecko bootstrap recovery + Exit')
