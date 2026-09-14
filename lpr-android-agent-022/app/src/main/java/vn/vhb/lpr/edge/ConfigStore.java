package vn.vhb.lpr.edge;

import android.content.Context;
import android.content.SharedPreferences;

public final class ConfigStore {
    private static final String PREF = "vhb_lpr_config";
    private ConfigStore() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String get(Context c, String key, String def) { return p(c).getString(key, def); }
    public static boolean getBool(Context c, String key, boolean def) { return p(c).getBoolean(key, def); }
    public static long getLong(Context c, String key, long def) { return p(c).getLong(key, def); }
    public static int getInt(Context c, String key, int def) { return p(c).getInt(key, def); }

    public static void put(Context c, String key, String value) { p(c).edit().putString(key, value == null ? "" : value).apply(); }
    public static void putBool(Context c, String key, boolean value) { p(c).edit().putBoolean(key, value).apply(); }
    public static void putLong(Context c, String key, long value) { p(c).edit().putLong(key, value).apply(); }
    public static void putInt(Context c, String key, int value) { p(c).edit().putInt(key, value).apply(); }

    public static void saveConnection(Context c, String server, String db, String agentCode, String enrollmentCode, boolean autoStart) {
        p(c).edit()
                .putString("server_url", cleanServer(server))
                .putString("database", safe(db).trim())
                .putString("agent_code", safe(agentCode).trim())
                .putString("enrollment_code", safe(enrollmentCode).trim())
                .putBoolean("auto_start", autoStart)
                .apply();
    }

    public static void setToken(Context c, String token) {
        p(c).edit().putString("api_token", safe(token).trim()).putBoolean("registered", !safe(token).trim().isEmpty()).apply();
    }

    public static String token(Context c) { return get(c, "api_token", ""); }
    public static boolean registered(Context c) { return !token(c).isEmpty(); }

    public static void clearEnrollmentCode(Context c) { put(c, "enrollment_code", ""); }

    public static void clearRegistration(Context c) {
        p(c).edit()
                .remove("api_token")
                .putBoolean("registered", false)
                .putString("odoo_status", "Chưa đăng ký")
                .apply();
    }

    public static void saveCamera(Context c, String code, String rtsp, String user, String password, String codec) {
        p(c).edit()
                .putString("camera_code", safe(code))
                .putString("camera_rtsp", safe(rtsp))
                .putString("camera_user", safe(user))
                .putString("camera_password", safe(password))
                .putString("camera_codec", safe(codec))
                .apply();
    }

    public static String cleanServer(String s) {
        String v = safe(s).trim();
        if (v.isEmpty()) return "";
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://" + v;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
