package vn.com.vhb.qmsdisplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

public final class MainActivity extends Activity {
    private static final String VERSION = "2.2.3";
    private static final String PREFS = "vhb_qms_display_settings";
    private static final long BACK_HOLD_MS = 1800L;
    private static final long RETRY_MIN_MS = 2000L;
    private static final long RETRY_MAX_MS = 15000L;
    private static final int REQ_HOME_ROLE = 2203;
    private static GeckoRuntime runtime;

    private SharedPreferences prefs;
    private FrameLayout root;
    private GeckoView view;
    private GeckoSession session;
    private LinearLayout overlay;
    private TextView title;
    private TextView detail;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean backHeld;
    private boolean canGoBack;
    private boolean firstPaint;
    private long retryDelayMs = RETRY_MIN_MS;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable backHold = () -> {
        backHeld = true;
        showPin();
    };

    private final Runnable retryRunnable = new Runnable() {
        @Override public void run() {
            if (session == null || configuredUrl().isEmpty()) return;
            showConnecting();
            if (!hasNetwork()) {
                scheduleRetry();
                return;
            }
            try {
                session.reload();
            } catch (Throwable ignored) {
                recover();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(4, 29, 54));
        setContentView(root);
        createOverlay();
        showConnecting();
        immersive();
        initNetwork();

        // Vẽ ngay giao diện VHB, sau đó mới khởi tạo Gecko nặng.
        root.post(this::startGecko);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        immersive();
        if (session != null) {
            try { session.setActive(true); } catch (Throwable ignored) { }
        }
    }

    private void startGecko() {
        try {
            ensureRuntime();
            createSession();
            loadConfigured();
        } catch (Throwable ignored) {
            showConnecting();
            scheduleRetry();
        }
    }

    private void ensureRuntime() {
        if (runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .allowInsecureConnections(GeckoRuntimeSettings.ALLOW_ALL)
                    .remoteDebuggingEnabled(false)
                    .consoleOutput(false)
                    .build();
            runtime = GeckoRuntime.create(getApplicationContext(), settings);
        }
        runtime.warmUp();
    }

    private void createSession() {
        main.removeCallbacks(retryRunnable);
        firstPaint = false;

        if (session != null) {
            try { if (view != null) view.releaseSession(); } catch (Throwable ignored) { }
            try { session.close(); } catch (Throwable ignored) { }
        }
        if (view != null) root.removeView(view);

        view = new GeckoView(this);
        root.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));

        GeckoSessionSettings ss = new GeckoSessionSettings.Builder()
                .allowJavascript(true)
                .usePrivateMode(false)
                .suspendMediaWhenInactive(false)
                .build();
        session = new GeckoSession(ss);

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onFirstContentfulPaint(GeckoSession s) {
                firstPaint = true;
                retryDelayMs = RETRY_MIN_MS;
                main.removeCallbacks(retryRunnable);
                hideStatus();
            }

            @Override public void onCrash(GeckoSession s) { recover(); }
            @Override public void onKill(GeckoSession s) { recover(); }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession s, String url) {
                firstPaint = false;
                showConnecting();
            }

            @Override public void onPageStop(GeckoSession s, boolean success) {
                if (!success) {
                    showConnecting();
                    scheduleRetry();
                } else if (!firstPaint) {
                    main.postDelayed(() -> {
                        if (!firstPaint) {
                            showConnecting();
                            scheduleRetry();
                        }
                    }, 12000L);
                }
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(GeckoSession s, boolean value) {
                canGoBack = value;
            }

            @Override public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError error) {
                // Không hiển thị URI/IP/token ở màn hình công cộng.
                showConnecting();
                scheduleRetry();
                return null;
            }
        });

        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public GeckoResult<Integer> onContentPermissionRequest(
                    GeckoSession s,
                    GeckoSession.PermissionDelegate.ContentPermission permission) {
                if (permission.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE
                        || permission.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                        || permission.permission == GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS
                        || permission.permission == GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS) {
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
                }
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT);
            }
        });

        session.open(runtime);
        view.setSession(session);
        session.setActive(true);
    }

    private void loadConfigured() {
        String url = configuredUrl();
        if (url.isEmpty()) {
            showNotConfigured();
            return;
        }
        prefs.edit().putString("url", url).apply();
        showConnecting();
        session.loadUri(url);
    }

    private void recover() {
        showConnecting();
        main.postDelayed(() -> {
            try {
                createSession();
                loadConfigured();
            } catch (Throwable ignored) {
                scheduleRetry();
            }
        }, 500L);
    }

    private void scheduleRetry() {
        main.removeCallbacks(retryRunnable);
        long delay = retryDelayMs;
        retryDelayMs = Math.min(RETRY_MAX_MS, retryDelayMs * 2L);
        main.postDelayed(retryRunnable, delay);
    }

    private void createOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(dp(32), dp(24), dp(32), dp(24));
        overlay.setBackgroundColor(Color.rgb(4, 29, 54));

        title = label(34, true);
        detail = label(20, false);
        overlay.addView(title);
        overlay.addView(detail);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView label(int sp, boolean bold) {
        TextView text = new TextView(this);
        text.setTextColor(Color.WHITE);
        text.setTextSize(sp);
        text.setGravity(Gravity.CENTER);
        if (bold) text.setTypeface(null, 1);
        text.setPadding(dp(8), dp(8), dp(8), dp(8));
        return text;
    }

    private void showConnecting() {
        title.setText("VHB QMS");
        detail.setText("Đang kết nối hệ thống VHB QMS");
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
    }

    private void showNotConfigured() {
        title.setText("VHB QMS");
        detail.setText("Chưa cấu hình hệ thống\nGiữ nút BACK 1,8 giây để vào Quản trị");
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
    }

    private void hideStatus() {
        overlay.setVisibility(View.GONE);
    }

    private void showPin() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("PIN quản trị");

        new AlertDialog.Builder(this)
                .setTitle("VHB QMS - Quản trị")
                .setView(input)
                .setPositiveButton("Mở", (dialog, which) -> {
                    String pin = prefs.getString("admin_pin", "1234");
                    if (pin.equals(input.getText().toString())) showAdmin();
                    else Toast.makeText(this, "Sai PIN", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void showAdmin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        EditText url = new EditText(this);
        url.setHint("URL hệ thống VHB QMS");
        url.setText(prefs.getString("url", ""));

        EditText pin = new EditText(this);
        pin.setHint("PIN quản trị");
        pin.setText(prefs.getString("admin_pin", "1234"));
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        CheckBox auto = new CheckBox(this);
        auto.setText("Tự khởi động khi bật Android Box");
        auto.setChecked(prefs.getBoolean("auto_start", true));

        CheckBox home = new CheckBox(this);
        home.setText("Dùng VHB QMS làm HOME Launcher chuyên dụng");
        home.setChecked(prefs.getBoolean("dedicated_home", true));

        TextView homeState = label(14, false);
        homeState.setTextColor(Color.DKGRAY);
        homeState.setText(isDefaultHome()
                ? "HOME hiện tại: VHB QMS đang là HOME mặc định"
                : "HOME hiện tại: chưa đặt VHB QMS làm HOME mặc định");

        Button setHome = new Button(this);
        setHome.setText("Đặt / đổi HOME mặc định");
        setHome.setOnClickListener(v -> requestHomeRole());

        Button settings = new Button(this);
        settings.setText("Mở cài đặt Android");
        settings.setOnClickListener(v -> openAndroidSettings());

        box.addView(url);
        box.addView(pin);
        box.addView(auto);
        box.addView(home);
        box.addView(homeState);
        box.addView(setHome);
        box.addView(settings);

        new AlertDialog.Builder(this)
                .setTitle("VHB QMS Display 2.2.3 • HOME Launcher")
                .setView(box)
                .setPositiveButton("Lưu & mở", (dialog, which) -> {
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
                            .putBoolean("dedicated_home", home.isChecked())
                            .apply();

                    try {
                        createSession();
                        loadConfigured();
                    } catch (Throwable ignored) {
                        recover();
                    }

                    if (home.isChecked() && !isDefaultHome()) {
                        main.postDelayed(this::requestHomeRole, 350L);
                    }
                })
                .setNegativeButton("Đóng", null)
                .show();
    }

    private void requestHomeRole() {
        if (isDefaultHome()) {
            Toast.makeText(this, "VHB QMS đã là HOME mặc định", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);
                if (roleManager != null
                        && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)
                        && !roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME_ROLE);
                    return;
                }
            }
            openHomeSettings();
        } catch (Throwable ignored) {
            openHomeSettings();
        }
    }

    private void openHomeSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_HOME_SETTINGS);
            startActivity(intent);
        } catch (Throwable ignored) {
            openAndroidSettings();
        }
    }

    private void openAndroidSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Throwable ignored) { }
    }

    private boolean isDefaultHome() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return info != null
                    && info.activityInfo != null
                    && getPackageName().equals(info.activityInfo.packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME_ROLE) {
            Toast.makeText(this,
                    isDefaultHome() ? "Đã đặt VHB QMS làm HOME mặc định" : "VHB QMS chưa được đặt làm HOME",
                    Toast.LENGTH_LONG).show();
        }
    }

    private String configuredUrl() {
        return normalize(prefs.getString("url", ""));
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        String url = raw.trim();
        if (url.isEmpty()) return "";
        if (!url.regionMatches(true, 0, "http://", 0, 7)
                && !url.regionMatches(true, 0, "https://", 0, 8)) {
            url = "http://" + url;
        }
        return url;
    }

    private boolean hasNetwork() {
        try {
            return cm != null && cm.getActiveNetwork() != null;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void initNetwork() {
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                main.postDelayed(() -> {
                    if (session != null && !configuredUrl().isEmpty() && !firstPaint) {
                        showConnecting();
                        try { session.reload(); } catch (Throwable ignored) { recover(); }
                    }
                }, 300L);
            }

            @Override public void onLost(Network network) {
                main.postDelayed(() -> {
                    if (cm.getActiveNetwork() == null) {
                        firstPaint = false;
                        showConnecting();
                        scheduleRetry();
                    }
                }, 500L);
            }
        };

        try { cm.registerDefaultNetworkCallback(networkCallback); } catch (Throwable ignored) { }
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        if (session != null) {
            try { session.setActive(true); } catch (Throwable ignored) { }
        }
    }

    @Override protected void onPause() {
        if (session != null) {
            try { session.setActive(false); } catch (Throwable ignored) { }
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (cm != null && networkCallback != null) {
            try { cm.unregisterNetworkCallback(networkCallback); } catch (Throwable ignored) { }
        }
        try { if (view != null) view.releaseSession(); } catch (Throwable ignored) { }
        try { if (session != null) session.close(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            backHeld = false;
            main.postDelayed(backHold, BACK_HOLD_MS);
            return true;
        }
        return super.onKeyDown(code, event);
    }

    @Override public boolean onKeyUp(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_BACK) {
            main.removeCallbacks(backHold);
            if (!backHeld && canGoBack && session != null) {
                try { session.goBack(); } catch (Throwable ignored) { }
            }
            backHeld = false;
            return true;
        }
        return super.onKeyUp(code, event);
    }

    private void immersive() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable ignored) { }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
