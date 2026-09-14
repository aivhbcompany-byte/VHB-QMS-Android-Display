package vn.vhb.lpr.edge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AgentService extends Service {
    private static final String CHANNEL = "vhb_lpr_agent_022";
    private static final int NOTIFY_ID = 2202;
    private ScheduledExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private int tick = 0;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFY_ID, notification("Agent đang khởi động..."));
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VHB:LPR_ANDROID_AGENT");
            wakeLock.acquire();
        } catch (Exception ignored) {}
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::cycleSafe, 2, 30, TimeUnit.SECONDS);
        ConfigStore.put(this, "service_status", "RUNNING");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        ConfigStore.put(this, "service_status", "STOPPED");
        if (executor != null) executor.shutdownNow();
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void cycleSafe() {
        try { cycle(); }
        catch (Throwable e) {
            ConfigStore.put(this, "last_error", e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
            updateNotification("Lỗi Agent: " + safe(e.getMessage()));
        }
    }

    private void cycle() {
        tick++;
        if (!ConfigStore.registered(this)) {
            ConfigStore.put(this, "odoo_status", "CHƯA ĐĂNG KÝ");
            updateNotification("Chưa đăng ký với Odoo");
            return;
        }

        ApiClient.Result hb = ApiClient.heartbeat(this);
        if (hb.ok) {
            ConfigStore.put(this, "odoo_status", "ONLINE");
            ConfigStore.putLong(this, "last_heartbeat", System.currentTimeMillis());
            ConfigStore.put(this, "last_error", "");
        } else {
            ConfigStore.put(this, "odoo_status", "OFFLINE");
            ConfigStore.put(this, "last_error", hb.message);
        }

        if (tick == 1 || tick % 2 == 0) syncConfig();
        if (tick == 1 || tick % 2 == 0) probeCamera();

        String cam = ConfigStore.get(this, "camera_status", "CHƯA CẤU HÌNH");
        String agent = ConfigStore.get(this, "agent_code", "");
        updateNotification("Odoo " + ConfigStore.get(this, "odoo_status", "-") + " · Camera " + cam + " · " + agent);
    }

    public void syncConfig() {
        try {
            ApiClient.ConfigResult cr = ApiClient.fetchConfig(this);
            if (cr.ok) {
                if (cr.revision >= 0) ConfigStore.putInt(this, "config_revision", cr.revision);
                if (!cr.rtspUrl.isEmpty()) {
                    String currentUser = cr.cameraUser.isEmpty() ? ConfigStore.get(this, "camera_user", "") : cr.cameraUser;
                    String currentPassword = cr.cameraPassword.isEmpty() ? ConfigStore.get(this, "camera_password", "") : cr.cameraPassword;
                    ConfigStore.saveCamera(this, cr.cameraCode, cr.rtspUrl, currentUser, currentPassword, ConfigStore.get(this, "camera_codec", ""));
                }
                ConfigStore.put(this, "config_status", cr.message);
                ConfigStore.putLong(this, "last_config_sync", System.currentTimeMillis());
            } else {
                ConfigStore.put(this, "config_status", "Lỗi: " + cr.message);
            }
        } catch (Exception e) {
            ConfigStore.put(this, "config_status", "Lỗi: " + safe(e.getMessage()));
        }
    }

    public void probeCamera() {
        String rtsp = ConfigStore.get(this, "camera_rtsp", "");
        if (rtsp.isEmpty()) {
            ConfigStore.put(this, "camera_status", "CHƯA CẤU HÌNH");
            return;
        }
        RtspProbe.ProbeResult pr = RtspProbe.probe(
                rtsp,
                ConfigStore.get(this, "camera_user", ""),
                ConfigStore.get(this, "camera_password", "")
        );
        ConfigStore.put(this, "camera_status", pr.ok ? "ONLINE" : "OFFLINE");
        ConfigStore.put(this, "camera_probe_message", pr.message);
        ConfigStore.putLong(this, "last_camera_probe", System.currentTimeMillis());
        if (!pr.codec.isEmpty()) ConfigStore.put(this, "camera_codec", pr.codec);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "VHB LPR Agent", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Dịch vụ nền VHB LPR Edge Agent");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("VHB LPR Edge Agent 0.2.2")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY_ID, notification(text)); }
        catch (Exception ignored) {}
    }

    public static String time(long ms) {
        if (ms <= 0) return "Chưa có";
        return new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(new Date(ms));
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
