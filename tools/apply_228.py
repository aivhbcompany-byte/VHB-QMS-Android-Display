from pathlib import Path

java_path = Path('app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java')
boot_path = Path('app/src/main/java/vn/com/vhb/qmsdisplay/BootReceiver.java')
manifest_path = Path('app/src/main/AndroidManifest.xml')
gradle_path = Path('app/build.gradle')

src = java_path.read_text(encoding='utf-8')

src = src.replace('private static final String VERSION = "2.2.7";', 'private static final String VERSION = "2.2.8";')

old_prefs = '        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);\n'
new_prefs = '''        Context configContext = createDeviceProtectedStorageContext();\n        if (VhbApp.isUserUnlocked(this)) {\n            try { configContext.moveSharedPreferencesFrom(this, PREFS); } catch (Throwable ignored) { }\n        }\n        prefs = configContext.getSharedPreferences(PREFS, MODE_PRIVATE);\n'''
if old_prefs not in src:
    raise SystemExit('Could not find preferences initialization')
src = src.replace(old_prefs, new_prefs, 1)

old_start = '''    private void startGecko() {\n        try {\n            ensureRuntime();\n            createSession();\n            loadConfigured();\n        } catch (Throwable ignored) {\n            showConnecting();\n            scheduleRetry();\n        }\n    }\n'''
new_start = '''    private void startGecko() {\n        // Direct Boot can launch the Activity before credential storage/user services are ready.\n        // Draw the VHB screen immediately, then start Gecko the instant user 0 is unlocked.\n        if (!VhbApp.isUserUnlocked(this)) {\n            showConnecting();\n            main.postDelayed(this::startGecko, 250L);\n            return;\n        }\n        try {\n            ensureRuntime();\n            createSession();\n            loadConfigured();\n        } catch (Throwable ignored) {\n            showConnecting();\n            scheduleRetry();\n        }\n    }\n'''
if old_start not in src:
    raise SystemExit('Could not find startGecko')
src = src.replace(old_start, new_start, 1)

old_runtime = '''    private void ensureRuntime() {\n        if (runtime == null) {\n            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()\n                    .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)\n                    .remoteDebuggingEnabled(false)\n                    .consoleOutput(false)\n                    .build();\n            runtime = GeckoRuntime.create(getApplicationContext(), settings);\n        }\n        runtime.warmUp();\n    }\n'''
new_runtime = '''    private void ensureRuntime() {\n        runtime = VhbApp.getRuntime(getApplicationContext());\n    }\n'''
if old_runtime not in src:
    raise SystemExit('Could not find ensureRuntime')
src = src.replace(old_runtime, new_runtime, 1)

# Replace the admin UI. HOME forcing is intentionally removed because PANAPLAYBOX
# rejects SET_PREFERRED_APPLICATIONS, has no role service and blocks su/root.
start = src.index('    private void showAdmin() {')
end = src.index('    private void provisionHomeOneTouch(', start)
new_admin = r'''    private void showAdmin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        box.setFocusableInTouchMode(true);

        EditText url = new EditText(this);
        url.setHint("URL hệ thống VHB QMS");
        url.setText(prefs.getString("url", ""));

        EditText pin = new EditText(this);
        pin.setHint("PIN quản trị");
        pin.setText(prefs.getString("admin_pin", "1234"));
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        Button hideIme = new Button(this);
        hideIme.setText("Ẩn bàn phím");
        hideIme.setOnClickListener(v -> {
            url.clearFocus();
            pin.clearFocus();
            box.requestFocus();
            hideKeyboard(v);
        });

        CheckBox auto = new CheckBox(this);
        auto.setText("Tự khởi động nhanh khi bật Android Box");
        auto.setChecked(prefs.getBoolean("auto_start", true));
        auto.setOnClickListener(v -> hideKeyboard(v));

        TextView bootInfo = label(14, false);
        bootInfo.setTextColor(Color.DKGRAY);
        bootInfo.setText("Khởi động: Direct Boot + LOCKED_BOOT_COMPLETED + BOOT_COMPLETED\n"
                + "Không phụ thuộc HOME Launcher / Role / Root");

        Button settings = new Button(this);
        settings.setText("Mở cài đặt Android");
        settings.setOnClickListener(v -> {
            url.clearFocus();
            pin.clearFocus();
            hideKeyboard(v);
            openAndroidSettings();
        });

        box.addView(url);
        box.addView(pin);
        box.addView(hideIme);
        box.addView(auto);
        box.addView(bootInfo);
        box.addView(settings);

        box.setOnTouchListener((v, event) -> {
            if (event != null && event.getAction() == MotionEvent.ACTION_DOWN) {
                View focused = getCurrentFocus();
                if (focused instanceof EditText) {
                    focused.clearFocus();
                    box.requestFocus();
                    hideKeyboard(focused);
                }
            }
            return false;
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(box, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        AlertDialog adminDialog = new AlertDialog.Builder(this)
                .setTitle("VHB QMS Display 2.2.8 • FAST DIRECT BOOT")
                .setView(scroll)
                .setPositiveButton("Lưu & mở", (dialog, which) -> {
                    hideKeyboard(url);
                    String normalized = normalize(url.getText().toString());
                    if (normalized.isEmpty()) {
                        Toast.makeText(this, "URL không hợp lệ", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String newPin = pin.getText().toString().trim();
                    if (newPin.isEmpty()) newPin = "1234";

                    prefs.edit()
                            .putString("url", normalized)
                            .putString("admin_pin", newPin)
                            .putBoolean("auto_start", auto.isChecked())
                            .apply();

                    try {
                        createSession();
                        loadConfigured();
                    } catch (Throwable ignored) {
                        recover();
                    }
                })
                .setNegativeButton("Đóng", (dialog, which) -> hideKeyboard(url))
                .create();

        adminDialog.setCanceledOnTouchOutside(false);
        adminDialog.setCancelable(true);
        adminDialog.setOnShowListener(ignored -> {
            try {
                if (adminDialog.getWindow() != null) {
                    adminDialog.getWindow().setSoftInputMode(
                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                }
            } catch (Throwable ignored2) { }
        });
        adminDialog.show();
    }

'''
src = src[:start] + new_admin + src[end:]
java_path.write_text(src, encoding='utf-8')

# Direct-Boot-aware receiver with duplicate suppression. It launches immediately on
# LOCKED_BOOT_COMPLETED and keeps BOOT_COMPLETED/USER_UNLOCKED as fallbacks.
boot_path.write_text(r'''package vn.com.vhb.qmsdisplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

public final class BootReceiver extends BroadcastReceiver {
    private static final String PREFS = "vhb_qms_display_settings";

    @Override public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        Context storage = app.createDeviceProtectedStorageContext();
        SharedPreferences prefs = storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_start", true)) return;

        // If VHB ever becomes HOME on another firmware, Android itself will launch it.
        if (isDefaultHome(app)) return;

        // LOCKED_BOOT_COMPLETED and BOOT_COMPLETED may both arrive. Avoid duplicate launches.
        long now = SystemClock.elapsedRealtime();
        long last = prefs.getLong("last_boot_launch_elapsed", -1L);
        if (last >= 0L && now >= last && (now - last) < 60000L) return;
        prefs.edit().putLong("last_boot_launch_elapsed", now).apply();

        try {
            Intent launch = new Intent(app, MainActivity.class);
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

manifest = manifest_path.read_text(encoding='utf-8')
manifest = manifest.replace(
    '    <application\n        android:allowBackup="false"',
    '    <application\n        android:name="vn.com.vhb.qmsdisplay.VhbApp"\n        android:directBootAware="true"\n        android:allowBackup="false"',
    1)
manifest = manifest.replace(
    '            android:name="vn.com.vhb.qmsdisplay.MainActivity"\n            android:configChanges=',
    '            android:name="vn.com.vhb.qmsdisplay.MainActivity"\n            android:directBootAware="true"\n            android:configChanges=',
    1)
manifest = manifest.replace(
    '            android:name="vn.com.vhb.qmsdisplay.BootReceiver"\n            android:enabled="true"',
    '            android:name="vn.com.vhb.qmsdisplay.BootReceiver"\n            android:directBootAware="true"\n            android:enabled="true"',
    1)
manifest = manifest.replace(
    '                <action android:name="android.intent.action.BOOT_COMPLETED" />',
    '                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />\n                <action android:name="android.intent.action.BOOT_COMPLETED" />',
    1)
manifest_path.write_text(manifest, encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
gradle = gradle.replace("versionCode 2020700", "versionCode 2020800")
gradle = gradle.replace("versionName '2.2.7'", "versionName '2.2.8'")
gradle_path.write_text(gradle, encoding='utf-8')

print('Applied VHB QMS Display 2.2.8 Fast Direct Boot')
