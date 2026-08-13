package vn.com.vhb.qmsdisplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private static final String VERSION = "2.2.2";
    private static final String PREFS = "vhb_qms_display_settings";
    private static final long BACK_HOLD_MS = 1800L;
    private static final long RETRY_MIN_MS = 2000L;
    private static final long RETRY_MAX_MS = 15000L;
    private static GeckoRuntime runtime;

    private SharedPreferences prefs;
    private FrameLayout root;
    private GeckoView view;
    private GeckoSession session;
    private LinearLayout overlay;
    private TextView title, detail, tech;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean backHeld, canGoBack;
    private boolean firstPaint;
    private long retryDelayMs = RETRY_MIN_MS;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable backHold = () -> { backHeld = true; showPin(); };
    private final Runnable retryRunnable = () -> {
        if (session == null || configuredUrl().isEmpty()) return;
        showConnecting();
        try { session.reload(); } catch (Throwable ignored) { recover("reload"); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(4,29,54));
        setContentView(root);
        createOverlay();
        showConnecting();
        immersive();
        initNetwork();

        // Cho Android vẽ ngay màn hình chờ VHB trước, sau đó mới khởi tạo engine nặng.
        root.post(this::startGecko);
    }

    private void startGecko() {
        try {
            ensureRuntime();
            createSession();
            loadConfigured();
        } catch (Throwable t) {
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
        // GeckoView 150+ không còn tự tạo trước content process; warmUp giúp tải trang đầu nhanh hơn.
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
            @Override public void onCrash(GeckoSession s) { recover("content crash"); }
            @Override public void onKill(GeckoSession s) { recover("content killed"); }
            @Override public void onTitleChange(GeckoSession s, String t) { updateTech(); }
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
                        if (!firstPaint) scheduleRetry();
                    }, 12000L);
                }
            }
            @Override public void onProgressChange(GeckoSession s, int progress) {
                updateTech();
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(GeckoSession s, boolean value) { canGoBack = value; }
            @Override public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError e) {
                // Tuyệt đối không đưa URI/IP/token ra màn hình công cộng.
                showConnecting();
                scheduleRetry();
                return null;
            }
        });

        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, GeckoSession.PermissionDelegate.ContentPermission perm) {
                if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE
                        || perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                        || perm.permission == GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS
                        || perm.permission == GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS) {
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
        String u = configuredUrl();
        if (u.isEmpty()) {
            showStatus("Chưa cấu hình hệ thống", "Giữ phím Back để vào Quản trị và cấu hình VHB QMS.", true);
            return;
        }
        prefs.edit().putString("url", u).apply();
        showConnecting();
        session.loadUri(u);
    }

    private String configuredUrl() {
        return normalize(prefs.getString("url", ""));
    }

    private void showConnecting() {
        showStatus("Đang kết nối hệ thống VHB QMS", "Vui lòng chờ, hệ thống sẽ tự động kết nối.", false);
    }

    private void scheduleRetry() {
        main.removeCallbacks(retryRunnable);
        long delay = retryDelayMs;
        retryDelayMs = Math.min(RETRY_MAX_MS, Math.max(RETRY_MIN_MS, retryDelayMs * 2L));
        main.postDelayed(retryRunnable, delay);
    }

    private void recover(String reason) {
        showConnecting();
        main.removeCallbacks(retryRunnable);
        main.postDelayed(() -> {
            try {
                ensureRuntime();
                createSession();
                loadConfigured();
            } catch (Throwable ignored) {
                scheduleRetry();
            }
        }, 500L);
    }

    private void createOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(dp(32), dp(24), dp(32), dp(24));
        overlay.setBackgroundColor(Color.rgb(4,29,54));

        title = label(30, true);
        detail = label(18, false);
        tech = label(14, false);

        Button retry = new Button(this);
        retry.setText("Thử lại");
        retry.setOnClickListener(v -> {
            retryDelayMs = RETRY_MIN_MS;
            if (session == null) startGecko();
            else {
                showConnecting();
                try { session.reload(); } catch (Throwable ignored) { recover("manual reload"); }
            }
        });

        Button admin = new Button(this);
        admin.setText("Quản trị");
        admin.setOnClickListener(v -> showPin());

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.addView(retry);
        row.addView(admin);

        overlay.addView(title);
        overlay.addView(detail);
        overlay.addView(tech);
        overlay.addView(row);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView label(int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(null, 1);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        return t;
    }

    private void showStatus(String h, String d, boolean showTech) {
        title.setText(h);
        detail.setText(d);
        tech.setText(showTech ? "VHB QMS Display " + VERSION + " • Mozilla GeckoView" : "");
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
    }

    private void updateTech() {
        if (tech != null && overlay.getVisibility() == View.VISIBLE && tech.length() > 0) {
            tech.setText("VHB QMS Display " + VERSION + " • Mozilla GeckoView");
        }
    }

    private void hideStatus() { overlay.setVisibility(View.GONE); }

    private void showPin() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("PIN quản trị");
        new AlertDialog.Builder(this)
                .setTitle("VHB Display - Quản trị")
                .setView(input)
                .setPositiveButton("Mở", (d, w) -> {
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
        url.setHint("Địa chỉ màn hình VHB QMS");
        url.setText(prefs.getString("url", ""));

        EditText pin = new EditText(this);
        pin.setHint("PIN quản trị");
        pin.setText(prefs.getString("admin_pin", "1234"));
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        CheckBox auto = new CheckBox(this);
        auto.setText("Tự khởi động sau khi bật Android Box");
        auto.setChecked(prefs.getBoolean("auto_start", true));

        box.addView(url);
        box.addView(pin);
        box.addView(auto);

        new AlertDialog.Builder(this)
                .setTitle("VHB QMS Display " + VERSION + " • GeckoView")
                .setView(box)
                .setPositiveButton("Lưu & mở", (d, w) -> {
                    String u = normalize(url.getText().toString());
                    if (u.isEmpty()) {
                        Toast.makeText(this, "Địa chỉ không hợp lệ", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String newPin = pin.getText().toString().trim();
                    prefs.edit()
                            .putString("url", u)
                            .putString("admin_pin", newPin.isEmpty() ? "1234" : newPin)
                            .putBoolean("auto_start", auto.isChecked())
                            .apply();
                    retryDelayMs = RETRY_MIN_MS;
                    if (session == null) startGecko(); else loadConfigured();
                })
                .setNeutralButton("Thoát kiosk", (d, w) -> exitKiosk())
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void exitKiosk() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_APP_BROWSER);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) { }
        finish();
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.isEmpty()) return "";
        if (!u.regionMatches(true, 0, "http://", 0, 7)
                && !u.regionMatches(true, 0, "https://", 0, 8)) {
            u = "http://" + u;
        }
        return u;
    }

    private void initNetwork() {
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                main.postDelayed(() -> {
                    if (session != null && !configuredUrl().isEmpty()) {
                        retryDelayMs = RETRY_MIN_MS;
                        showConnecting();
                        try { session.reload(); } catch (Throwable ignored) { recover("network available"); }
                    }
                }, 250L);
            }
            @Override public void onLost(Network n) {
                main.postDelayed(() -> {
                    if (cm.getActiveNetwork() == null) showConnecting();
                }, 300L);
            }
        };
        try { cm.registerDefaultNetworkCallback(networkCallback); } catch (Throwable ignored) { }
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(retryRunnable);
        if (cm != null && networkCallback != null) {
            try { cm.unregisterNetworkCallback(networkCallback); } catch (Throwable ignored) { }
        }
        try { if (view != null) view.releaseSession(); } catch (Throwable ignored) { }
        try { if (session != null) session.close(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        if (session != null) try { session.setActive(true); } catch (Throwable ignored) { }
    }

    @Override protected void onPause() {
        if (session != null) try { session.setActive(false); } catch (Throwable ignored) { }
        super.onPause();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && e.getRepeatCount() == 0) {
            backHeld = false;
            main.postDelayed(backHold, BACK_HOLD_MS);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            main.removeCallbacks(backHold);
            if (!backHeld && canGoBack && session != null) session.goBack();
            backHeld = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void immersive() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable ignored) { }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
