package vn.vhb.lpr.edge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private boolean authenticated = false;
    private boolean manufacturerMode = false;
    private boolean firstStart = true;
    private EditText server, database, agentCode, enrollmentCode;
    private CheckBox autoStart;
    private TextView statusRegistration, statusOdoo, statusService, statusCamera, statusConfig, statusHeartbeat, statusError, cameraInfo;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (authenticated) refreshStatus();
            handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AuthManager.ensureDefault(this);
        migrateLegacyConfig();
        showLockedScreen();
    }

    @Override protected void onStart() {
        super.onStart();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
        if (firstStart) {
            firstStart = false;
            showLogin();
        } else if (!authenticated) showLogin();
    }

    @Override protected void onStop() {
        super.onStop();
        handler.removeCallbacks(refresher);
        if (!isChangingConfigurations()) authenticated = false;
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        handler.removeCallbacks(refresher);
        super.onDestroy();
    }

    private void migrateLegacyConfig() {
        if (ConfigStore.get(this, "agent_code", "").isEmpty()) {
            String old = ConfigStore.get(this, "agent_id", "");
            if (!old.isEmpty()) ConfigStore.put(this, "agent_code", old);
        }
        if (ConfigStore.get(this, "camera_rtsp", "").isEmpty()) {
            String old = ConfigStore.get(this, "rtsp_url", "");
            if (!old.isEmpty()) ConfigStore.put(this, "camera_rtsp", old);
        }
        if (ConfigStore.get(this, "camera_user", "").isEmpty()) {
            String old = ConfigStore.get(this, "camera_user", "");
            if (!old.isEmpty()) ConfigStore.put(this, "camera_user", old);
        }
    }

    private void showLockedScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(40, 40, 40, 40);
        TextView title = new TextView(this);
        title.setText("VHB LPR EDGE AGENT\nCấu hình đang khóa");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.rgb(20, 55, 90));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        Button open = button("MỞ CẤU HÌNH");
        open.setOnClickListener(v -> showLogin());
        root.addView(open, paramsTop(36));
        setContentView(root);
    }

    private void showLogin() {
        final EditText pass = new EditText(this);
        pass.setHint("Mật khẩu cấu hình");
        pass.setSingleLine(true);
        pass.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Đăng nhập VHB LPR Edge Agent")
                .setMessage("Nhập mật khẩu cấu hình")
                .setView(pass)
                .setNegativeButton("Thoát", (x, w) -> finish())
                .setPositiveButton("Đăng nhập", null)
                .create();
        d.setCanceledOnTouchOutside(false);
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = pass.getText().toString();
                if (!AuthManager.authenticate(this, value)) {
                    pass.setError("Mật khẩu không đúng");
                    return;
                }
                manufacturerMode = AuthManager.isManufacturer(value);
                authenticated = true;
                d.dismiss();
                buildUi();
            });
            pass.requestFocus();
        });
        d.show();
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 24, 34, 42);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("VHB LPR EDGE AGENT");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(20, 55, 90));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Android TV Box · Version 0.2.2 · " + (manufacturerMode ? "Kỹ thuật VHB" : "Người dùng cấu hình"));
        sub.setTextSize(14);
        root.addView(sub);

        root.addView(section("KẾT NỐI ODOO"));
        root.addView(label("Server VHB / Odoo URL"));
        server = edit(ConfigStore.get(this, "server_url", ""), "http://192.168.100.17:8069", false);
        root.addView(server);
        root.addView(label("Database"));
        database = edit(ConfigStore.get(this, "database", "OdooDB"), "OdooDB", false);
        root.addView(database);
        root.addView(label("Agent ID / Mã Agent trên Odoo"));
        agentCode = edit(ConfigStore.get(this, "agent_code", "VHB-ANDROID-001"), "VHB-ANDROID-001", false);
        root.addView(agentCode);
        root.addView(label("Mã đăng ký một lần từ Odoo"));
        enrollmentCode = edit(ConfigStore.get(this, "enrollment_code", ""), "VHB-...", false);
        root.addView(enrollmentCode);
        autoStart = new CheckBox(this);
        autoStart.setText("Tự khởi động Agent sau khi bật nguồn / reboot");
        autoStart.setChecked(ConfigStore.getBool(this, "auto_start", true));
        root.addView(autoStart, paramsTop(12));

        LinearLayout row1 = row();
        Button health = button("KIỂM TRA ODOO");
        health.setOnClickListener(v -> testOdoo());
        row1.addView(health, cell());
        Button enroll = button("ĐĂNG KÝ VỚI ODOO");
        enroll.setOnClickListener(v -> enroll());
        row1.addView(enroll, cell());
        root.addView(row1, paramsTop(12));

        LinearLayout row2 = row();
        Button hb = button("KIỂM TRA AGENT / HEARTBEAT");
        hb.setOnClickListener(v -> testHeartbeat());
        row2.addView(hb, cell());
        Button sync = button("ĐỒNG BỘ CẤU HÌNH");
        sync.setOnClickListener(v -> syncConfig());
        row2.addView(sync, cell());
        root.addView(row2);

        root.addView(section("CAMERA NHẬN TỪ ODOO"));
        cameraInfo = statusText();
        root.addView(cameraInfo);
        Button testCamera = button("KIỂM TRA CAMERA RTSP");
        testCamera.setOnClickListener(v -> testCamera());
        root.addView(testCamera, paramsTop(10));

        root.addView(section("TRẠNG THÁI VẬN HÀNH"));
        statusRegistration = statusText(); root.addView(statusRegistration);
        statusOdoo = statusText(); root.addView(statusOdoo);
        statusService = statusText(); root.addView(statusService);
        statusCamera = statusText(); root.addView(statusCamera);
        statusConfig = statusText(); root.addView(statusConfig);
        statusHeartbeat = statusText(); root.addView(statusHeartbeat);
        statusError = statusText(); root.addView(statusError);

        LinearLayout row3 = row();
        Button start = button("LƯU & KHỞI ĐỘNG AGENT");
        start.setOnClickListener(v -> { saveFields(); startAgent(); toast("Đã lưu và khởi động Agent"); refreshStatus(); });
        row3.addView(start, cell());
        Button stop = button("DỪNG AGENT");
        stop.setOnClickListener(v -> { stopService(new Intent(this, AgentService.class)); ConfigStore.put(this, "service_status", "STOPPED"); refreshStatus(); });
        row3.addView(stop, cell());
        root.addView(row3, paramsTop(14));

        Button change = button("ĐỔI / KHÔI PHỤC MẬT KHẨU CẤU HÌNH");
        change.setOnClickListener(v -> changePassword());
        root.addView(change, paramsTop(8));

        if (manufacturerMode) {
            root.addView(section("CÔNG CỤ KỸ THUẬT VHB"));
            Button token = button("NHẬP API TOKEN THỦ CÔNG / CỨU HỘ");
            token.setOnClickListener(v -> manualToken());
            root.addView(token);
            Button fallbackCam = button("CẤU HÌNH CAMERA DỰ PHÒNG");
            fallbackCam.setOnClickListener(v -> fallbackCamera());
            root.addView(fallbackCam);
            Button clearReg = button("XÓA ĐĂNG KÝ CỤC BỘ");
            clearReg.setOnClickListener(v -> confirmClearRegistration());
            root.addView(clearReg);
            Button reset = button("ĐẶT LẠI MẬT KHẨU KHÁCH VỀ 1234");
            reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Xác nhận")
                    .setMessage("Đặt lại mật khẩu người dùng về 1234?")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Đặt lại", (d, w) -> { AuthManager.resetUserPassword(this); toast("Đã đặt lại mật khẩu khách"); })
                    .show());
            root.addView(reset);
        }

        Button settings = button("MỞ CÀI ĐẶT HỆ THỐNG ANDROID");
        settings.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        });
        root.addView(settings, paramsTop(12));

        Button lock = button("KHÓA CẤU HÌNH");
        lock.setOnClickListener(v -> { authenticated = false; manufacturerMode = false; showLockedScreen(); });
        root.addView(lock);

        TextView note = new TextView(this);
        note.setText("Quy trình chuẩn: tạo Agent trên Odoo → Tạo mã đăng ký → nhập mã vào Box → Đăng ký → Đồng bộ cấu hình → Kiểm tra Camera → Lưu & khởi động. API Token được lưu nội bộ và không hiển thị cho người dùng thông thường.");
        note.setTextSize(12);
        note.setPadding(0, 22, 0, 0);
        root.addView(note);

        setContentView(sv);
        refreshStatus();
        if (autoStart.isChecked() && ConfigStore.registered(this)) startAgent();
    }

    private void testOdoo() {
        saveFields();
        runAsync("Kiểm tra Odoo", () -> {
            ApiClient.Result r = ApiClient.health(serverText(), dbText());
            ConfigStore.put(this, "health_status", r.ok ? "OK" : r.message);
            return r.ok ? "Odoo API OK · HTTP " + r.code : r.message;
        });
    }

    private void enroll() {
        saveFields();
        String code = enrollmentCode.getText().toString().trim();
        if (serverText().isEmpty()) { server.setError("Nhập Server Odoo"); return; }
        if (dbText().isEmpty()) { database.setError("Nhập Database"); return; }
        if (agentText().isEmpty()) { agentCode.setError("Nhập Agent ID"); return; }
        if (code.isEmpty()) { enrollmentCode.setError("Nhập mã đăng ký từ Odoo"); return; }
        runAsync("Đăng ký Agent", () -> {
            ApiClient.EnrollResult r = ApiClient.enroll(serverText(), dbText(), agentText(), code, this);
            if (!r.ok) return r.message;
            ConfigStore.setToken(this, r.token);
            ConfigStore.clearEnrollmentCode(this);
            ConfigStore.put(this, "registration_status", "ĐÃ ĐĂNG KÝ");
            ApiClient.Result hb = ApiClient.heartbeat(this);
            if (hb.ok) {
                ConfigStore.put(this, "odoo_status", "ONLINE");
                ConfigStore.putLong(this, "last_heartbeat", System.currentTimeMillis());
            }
            return "Đăng ký thành công · Token đã lưu an toàn trong cấu hình ứng dụng";
        }, () -> enrollmentCode.setText(""));
    }

    private void testHeartbeat() {
        saveFields();
        if (!ConfigStore.registered(this)) { toast("Agent chưa đăng ký / chưa có API Token"); return; }
        runAsync("Heartbeat", () -> {
            ApiClient.Result r = ApiClient.heartbeat(this);
            ConfigStore.put(this, "odoo_status", r.ok ? "ONLINE" : "OFFLINE");
            if (r.ok) ConfigStore.putLong(this, "last_heartbeat", System.currentTimeMillis());
            else ConfigStore.put(this, "last_error", r.message);
            return r.ok ? "Heartbeat OK · Odoo đã nhận Agent" : r.message;
        });
    }

    private void syncConfig() {
        saveFields();
        if (!ConfigStore.registered(this)) { toast("Hãy đăng ký Agent trước"); return; }
        runAsync("Đồng bộ cấu hình", () -> {
            ApiClient.ConfigResult cr = ApiClient.fetchConfig(this);
            if (!cr.ok) return cr.message;
            if (cr.revision >= 0) ConfigStore.putInt(this, "config_revision", cr.revision);
            if (!cr.rtspUrl.isEmpty()) {
                String u = cr.cameraUser.isEmpty() ? ConfigStore.get(this, "camera_user", "") : cr.cameraUser;
                String p = cr.cameraPassword.isEmpty() ? ConfigStore.get(this, "camera_password", "") : cr.cameraPassword;
                ConfigStore.saveCamera(this, cr.cameraCode, cr.rtspUrl, u, p, ConfigStore.get(this, "camera_codec", ""));
            }
            ConfigStore.put(this, "config_status", cr.message);
            ConfigStore.putLong(this, "last_config_sync", System.currentTimeMillis());
            return cr.message;
        });
    }

    private void testCamera() {
        String rtsp = ConfigStore.get(this, "camera_rtsp", "");
        if (rtsp.isEmpty()) { toast("Chưa có RTSP. Hãy Đồng bộ cấu hình từ Odoo trước."); return; }
        runAsync("Kiểm tra Camera", () -> {
            RtspProbe.ProbeResult pr = RtspProbe.probe(rtsp, ConfigStore.get(this, "camera_user", ""), ConfigStore.get(this, "camera_password", ""));
            ConfigStore.put(this, "camera_status", pr.ok ? "ONLINE" : "OFFLINE");
            ConfigStore.put(this, "camera_probe_message", pr.message);
            ConfigStore.putLong(this, "last_camera_probe", System.currentTimeMillis());
            if (!pr.codec.isEmpty()) ConfigStore.put(this, "camera_codec", pr.codec);
            return pr.message;
        });
    }

    private void saveFields() {
        if (server == null) return;
        ConfigStore.saveConnection(this, serverText(), dbText(), agentText(), enrollmentCode.getText().toString(), autoStart.isChecked());
    }

    private void startAgent() {
        saveFields();
        Intent i = new Intent(this, AgentService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            ConfigStore.put(this, "service_status", "RUNNING");
        } catch (Exception e) {
            ConfigStore.put(this, "last_error", "StartService: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void manualToken() {
        final EditText e = edit("", "Dán API Token từ Odoo", true);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("API Token thủ công")
                .setMessage("Chỉ dùng khi cơ chế mã đăng ký không khả dụng. Token không hiển thị lại sau khi lưu.")
                .setView(e)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = e.getText().toString().trim();
            if (t.length() < 8) { e.setError("Token không hợp lệ"); return; }
            ConfigStore.setToken(this, t);
            ConfigStore.put(this, "registration_status", "ĐÃ CẤU HÌNH TOKEN THỦ CÔNG");
            d.dismiss(); refreshStatus(); toast("Đã lưu API Token");
        }));
        d.show();
    }

    private void fallbackCamera() {
        LinearLayout l = dialogLayout();
        EditText rtsp = edit(ConfigStore.get(this, "camera_rtsp", ""), "rtsp://camera:554/Streaming/Channels/101", false);
        EditText user = edit(ConfigStore.get(this, "camera_user", ""), "Tài khoản camera", false);
        EditText pass = edit(ConfigStore.get(this, "camera_password", ""), "Mật khẩu camera", true);
        l.addView(label("RTSP dự phòng")); l.addView(rtsp);
        l.addView(label("Tài khoản")); l.addView(user);
        l.addView(label("Mật khẩu")); l.addView(pass);
        new AlertDialog.Builder(this)
                .setTitle("Camera dự phòng")
                .setView(l)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", (d, w) -> {
                    ConfigStore.saveCamera(this, ConfigStore.get(this, "camera_code", "ANDROID-CAMERA"), rtsp.getText().toString().trim(), user.getText().toString().trim(), pass.getText().toString(), ConfigStore.get(this, "camera_codec", ""));
                    refreshStatus();
                }).show();
    }

    private void confirmClearRegistration() {
        new AlertDialog.Builder(this)
                .setTitle("Xóa đăng ký cục bộ")
                .setMessage("Xóa API Token đang lưu trên Android Box? Sau đó cần Tạo mã đăng ký mới trên Odoo.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xóa", (d, w) -> { ConfigStore.clearRegistration(this); refreshStatus(); })
                .show();
    }

    private void changePassword() {
        LinearLayout l = dialogLayout();
        EditText current = edit("", "Mật khẩu hiện tại hoặc mã kỹ thuật", true);
        current.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText n1 = edit("", "Mật khẩu mới (4-12 chữ số)", true);
        n1.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText n2 = edit("", "Nhập lại mật khẩu mới", true);
        n2.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        l.addView(current); l.addView(n1); l.addView(n2);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Đổi / Khôi phục mật khẩu")
                .setView(l)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Lưu", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String c = current.getText().toString(), a = n1.getText().toString(), b = n2.getText().toString();
            if (!AuthManager.authenticate(this, c)) { current.setError("Mật khẩu hiện tại không đúng"); return; }
            if (!a.matches("\\d{4,12}")) { n1.setError("Chỉ dùng 4-12 chữ số"); return; }
            if (AuthManager.isReservedManufacturerPassword(a)) { n1.setError("Không được trùng mã kỹ thuật VHB"); return; }
            if (!a.equals(b)) { n2.setError("Hai mật khẩu chưa khớp"); return; }
            AuthManager.setUserPassword(this, a);
            d.dismiss(); toast("Đã đổi mật khẩu người dùng");
        }));
        d.show();
    }

    private void refreshStatus() {
        if (statusRegistration == null) return;
        String token = ConfigStore.token(this);
        statusRegistration.setText("Đăng ký: " + (token.isEmpty() ? "CHƯA ĐĂNG KÝ" : "ĐÃ ĐĂNG KÝ · Token đã lưu"));
        statusOdoo.setText("Odoo: " + ConfigStore.get(this, "odoo_status", "Chưa kiểm tra"));
        statusService.setText("Agent Service: " + ConfigStore.get(this, "service_status", "STOPPED"));
        statusCamera.setText("Camera: " + ConfigStore.get(this, "camera_status", "Chưa kiểm tra") + " · " + ConfigStore.get(this, "camera_probe_message", ""));
        statusConfig.setText("Config revision: " + ConfigStore.getInt(this, "config_revision", 0) + " · " + ConfigStore.get(this, "config_status", "Chưa đồng bộ"));
        statusHeartbeat.setText("Heartbeat cuối: " + AgentService.time(ConfigStore.getLong(this, "last_heartbeat", 0)) + " · Config sync: " + AgentService.time(ConfigStore.getLong(this, "last_config_sync", 0)));
        String err = ConfigStore.get(this, "last_error", "");
        statusError.setText("Lỗi gần nhất: " + (err.isEmpty() ? "Không có" : err));
        String rtsp = ConfigStore.get(this, "camera_rtsp", "");
        String code = ConfigStore.get(this, "camera_code", "");
        String codec = ConfigStore.get(this, "camera_codec", "");
        cameraInfo.setText("Camera: " + (code.isEmpty() ? "Chưa nhận từ Odoo" : code) + "\nRTSP: " + redactRtsp(rtsp) + (codec.isEmpty() ? "" : "\nCodec: " + codec));
    }

    private String redactRtsp(String url) {
        if (url == null || url.isEmpty()) return "Chưa có";
        int scheme = url.indexOf("://");
        int at = url.indexOf('@');
        if (scheme >= 0 && at > scheme) return url.substring(0, scheme + 3) + "***:***@" + url.substring(at + 1);
        return url;
    }

    private void runAsync(String title, Work work) { runAsync(title, work, null); }
    private void runAsync(String title, Work work, Runnable after) {
        toast(title + "...");
        io.submit(() -> {
            String message;
            try { message = work.run(); }
            catch (Exception e) { message = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage()); }
            final String result = message;
            runOnUiThread(() -> {
                if (after != null) after.run();
                refreshStatus();
                new AlertDialog.Builder(this).setTitle(title).setMessage(result).setPositiveButton("Đóng", null).show();
            });
        });
    }

    private String serverText() { return ConfigStore.cleanServer(server.getText().toString()); }
    private String dbText() { return database.getText().toString().trim(); }
    private String agentText() { return agentCode.getText().toString().trim(); }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(Color.rgb(20, 75, 120));
        t.setPadding(0, 28, 0, 8);
        return t;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, 12, 0, 3);
        return t;
    }

    private TextView statusText() {
        TextView t = new TextView(this);
        t.setTextSize(14);
        t.setPadding(10, 8, 10, 8);
        t.setTextColor(Color.rgb(40, 40, 40));
        return t;
    }

    private EditText edit(String value, String hint, boolean password) {
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(16);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams cell() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f); p.setMargins(4, 4, 4, 4); return p; }
    private LinearLayout.LayoutParams paramsTop(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = top; return p; }
    private LinearLayout dialogLayout() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(38, 8, 38, 0); return l; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private interface Work { String run() throws Exception; }
}
