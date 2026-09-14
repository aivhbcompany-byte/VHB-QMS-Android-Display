package vn.vhb.lpr.edge;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class AuthManager {
    private static final String PREF = "vhb_lpr_secure";
    private static final String KEY_USER_HASH = "user_password_hash";
    private static final String DEFAULT_USER_HASH = "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4";
    private static final String MANUFACTURER_HASH = "ed946f65d2c785d90e827c5ffd879ce3b49c68d4c88013074176a7e73bc58bcf";

    private AuthManager() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void ensureDefault(Context c) {
        if (!prefs(c).contains(KEY_USER_HASH)) {
            prefs(c).edit().putString(KEY_USER_HASH, DEFAULT_USER_HASH).apply();
        }
    }

    public static boolean isManufacturer(String value) {
        return MANUFACTURER_HASH.equals(hash(clean(value)));
    }

    public static boolean authenticate(Context c, String value) {
        ensureDefault(c);
        String h = hash(clean(value));
        return MANUFACTURER_HASH.equals(h) || h.equals(prefs(c).getString(KEY_USER_HASH, DEFAULT_USER_HASH));
    }

    public static void setUserPassword(Context c, String value) {
        prefs(c).edit().putString(KEY_USER_HASH, hash(clean(value))).apply();
    }

    public static void resetUserPassword(Context c) {
        prefs(c).edit().putString(KEY_USER_HASH, DEFAULT_USER_HASH).apply();
    }

    public static boolean isReservedManufacturerPassword(String value) {
        return MANUFACTURER_HASH.equals(hash(clean(value)));
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    private static String hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
