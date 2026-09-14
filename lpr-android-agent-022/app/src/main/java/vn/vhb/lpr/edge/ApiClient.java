package vn.vhb.lpr.edge;

import android.content.Context;
import android.os.Build;
import android.app.ActivityManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public final class ApiClient {
    public static final String VERSION = "0.2.2-TVBOX";
    private ApiClient() {}

    public static class Result {
        public boolean ok;
        public int code;
        public String message = "";
        public String body = "";
        public String endpoint = "";
    }

    public static class EnrollResult extends Result {
        public String token = "";
    }

    public static class ConfigResult extends Result {
        public int revision = -1;
        public String cameraCode = "";
        public String rtspUrl = "";
        public String cameraUser = "";
        public String cameraPassword = "";
    }

    public static Result health(String server, String db) {
        String endpoint = endpoint(server, "/vhb_lpr/api/v1/health", db);
        Result r = request("GET", endpoint, db, "", null, false);
        r.ok = r.code >= 200 && r.code < 300;
        r.message = r.ok ? "Odoo API phản hồi HTTP " + r.code : conciseError(r);
        return r;
    }

    public static EnrollResult enroll(String server, String db, String agentCode, String enrollmentCode, Context context) {
        EnrollResult last = new EnrollResult();
        String[] paths = new String[] {
                "/vhb_lpr/api/v1/enroll",
                "/vhb_lpr/api/v1/agent/enroll",
                "/vhb_lpr/api/v1/enrollment"
        };

        String deviceId = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        JSONObject params = new JSONObject();
        try {
            params.put("db", db);
            params.put("database", db);
            params.put("agent_code", agentCode);
            params.put("enrollment_code", enrollmentCode);
            params.put("version", VERSION);
            params.put("agent_version", VERSION);
            params.put("platform", "android");
            params.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
            params.put("device_id", deviceId == null ? "" : deviceId);
        } catch (Exception ignored) {}

        for (String path : paths) {
            String ep = endpoint(server, path, db);
            for (int mode = 0; mode < 2; mode++) {
                String payload = mode == 0 ? params.toString() : rpcPayload(params);
                Result raw = request("POST", ep, db, payload, null, true);
                EnrollResult er = new EnrollResult();
                copy(raw, er);
                er.token = extractToken(raw.body);
                if (raw.code >= 200 && raw.code < 300 && !er.token.isEmpty()) {
                    er.ok = true;
                    er.message = "Đăng ký thành công";
                    return er;
                }
                if (raw.code == 404 || raw.code == 405) {
                    last = er;
                    break;
                }
                last = er;
            }
        }
        last.ok = false;
        last.message = "Không đăng ký được: " + conciseError(last);
        return last;
    }

    public static Result heartbeat(Context context) {
        String server = ConfigStore.get(context, "server_url", "");
        String db = ConfigStore.get(context, "database", "OdooDB");
        String agent = ConfigStore.get(context, "agent_code", "");
        String token = ConfigStore.token(context);
        String endpoint = endpoint(server, "/vhb_lpr/api/v1/heartbeat", db);

        JSONObject payload = new JSONObject();
        try {
            JSONObject metrics = new JSONObject();
            metrics.put("cpu_percent", 0.0);
            metrics.put("ram_percent", ramPercent(context));
            metrics.put("queue_size", 0);
            metrics.put("fps", 0.0);

            JSONArray cameras = new JSONArray();
            String camCode = ConfigStore.get(context, "camera_code", "");
            String rtsp = ConfigStore.get(context, "camera_rtsp", "");
            if (!camCode.isEmpty() || !rtsp.isEmpty()) {
                JSONObject c = new JSONObject();
                c.put("code", camCode.isEmpty() ? "ANDROID-CAMERA" : camCode);
                c.put("online", "ONLINE".equals(ConfigStore.get(context, "camera_status", "")));
                c.put("fps", 0.0);
                c.put("codec", ConfigStore.get(context, "camera_codec", ""));
                cameras.put(c);
            }

            payload.put("agent_code", agent);
            payload.put("version", VERSION);
            payload.put("platform", "android");
            payload.put("metrics", metrics);
            payload.put("cameras", cameras);
        } catch (Exception ignored) {}

        Result r = postWithRpcFallback(endpoint, db, payload, token);
        r.ok = r.code >= 200 && r.code < 300 && !containsError(r.body);
        r.message = r.ok ? "Heartbeat OK" : conciseError(r);
        return r;
    }

    public static ConfigResult fetchConfig(Context context) {
        String server = ConfigStore.get(context, "server_url", "");
        String db = ConfigStore.get(context, "database", "OdooDB");
        String agent = ConfigStore.get(context, "agent_code", "");
        String token = ConfigStore.token(context);
        int currentRevision = ConfigStore.getInt(context, "config_revision", 0);
        String ep = endpoint(server, "/vhb_lpr/api/v1/config", db);

        JSONObject payload = new JSONObject();
        try {
            payload.put("agent_code", agent);
            payload.put("revision", currentRevision);
            payload.put("config_revision", currentRevision);
            payload.put("platform", "android");
        } catch (Exception ignored) {}

        Result raw = postWithRpcFallback(ep, db, payload, token);
        ConfigResult cr = new ConfigResult();
        copy(raw, cr);
        cr.ok = raw.code >= 200 && raw.code < 300 && !containsError(raw.body);
        if (!cr.ok) {
            cr.message = conciseError(raw);
            return cr;
        }

        Object root = parseJson(raw.body);
        cr.revision = findInt(root, new String[]{"config_revision", "revision"}, currentRevision);
        JSONObject camera = findCameraObject(root);
        if (camera != null) {
            cr.cameraCode = first(camera, "code", "camera_code", "name");
            cr.rtspUrl = first(camera, "ai_stream_url", "rtsp_url", "stream_url", "url");
            cr.cameraUser = first(camera, "username", "camera_username", "user", "login");
            cr.cameraPassword = first(camera, "password", "camera_password", "passwd");
            if (cr.rtspUrl.isEmpty()) {
                JSONObject stream = findStreamObject(camera);
                if (stream != null) cr.rtspUrl = first(stream, "url", "rtsp_url", "stream_url");
            }
        }
        cr.message = cr.rtspUrl.isEmpty() ? "Đồng bộ cấu hình thành công; chưa thấy Camera RTSP" : "Đã nhận Camera " + cr.cameraCode;
        return cr;
    }

    public static Result testHeartbeat(String server, String db, String agent, String token, Context context) {
        ConfigStore.saveConnection(context, server, db, agent, ConfigStore.get(context, "enrollment_code", ""), ConfigStore.getBool(context, "auto_start", true));
        if (token != null && !token.trim().isEmpty()) ConfigStore.setToken(context, token.trim());
        return heartbeat(context);
    }

    private static Result postWithRpcFallback(String endpoint, String db, JSONObject params, String token) {
        Result r = request("POST", endpoint, db, params.toString(), token, true);
        if ((r.code >= 200 && r.code < 300 && !containsError(r.body))) return r;
        if (r.code == 400 || r.code == 415 || containsRpcHint(r.body)) {
            Result rpc = request("POST", endpoint, db, rpcPayload(params), token, true);
            if (rpc.code >= 200 && rpc.code < 300) return rpc;
        }
        return r;
    }

    private static Result request(String method, String endpoint, String db, String body, String token, boolean json) {
        Result r = new Result();
        r.endpoint = endpoint;
        HttpURLConnection c = null;
        try {
            URL u = new URL(endpoint);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(7000);
            c.setReadTimeout(10000);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("User-Agent", "VHB-LPR-Android/" + VERSION);
            if (db != null && !db.trim().isEmpty()) c.setRequestProperty("X-Odoo-Database", db.trim());
            if (token != null && !token.trim().isEmpty()) c.setRequestProperty("X-VHB-LPR-Token", token.trim());
            if (json) c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (body != null && !body.isEmpty() && !"GET".equals(method)) {
                c.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
            }
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = read(in);
            r.ok = r.code >= 200 && r.code < 300;
        } catch (Exception e) {
            r.code = -1;
            r.body = "";
            r.message = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    private static String endpoint(String server, String path, String db) {
        String base = ConfigStore.cleanServer(server);
        String sep = path.contains("?") ? "&" : "?";
        try {
            return base + path + (db == null || db.trim().isEmpty() ? "" : sep + "db=" + URLEncoder.encode(db.trim(), "UTF-8"));
        } catch (Exception e) {
            return base + path;
        }
    }

    private static String rpcPayload(JSONObject params) {
        JSONObject rpc = new JSONObject();
        try {
            rpc.put("jsonrpc", "2.0");
            rpc.put("method", "call");
            rpc.put("params", params);
            rpc.put("id", 1);
        } catch (Exception ignored) {}
        return rpc.toString();
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static double ramPercent(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem <= 0) return 0.0;
            return Math.round((100.0 * (mi.totalMem - mi.availMem) / mi.totalMem) * 10.0) / 10.0;
        } catch (Exception e) { return 0.0; }
    }

    private static String extractToken(String body) {
        Object root = parseJson(body);
        return findString(root, new String[]{"api_token", "token", "agent_token", "access_token"});
    }

    private static Object parseJson(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try { return new JSONTokener(body.trim()).nextValue(); }
        catch (Exception e) { return null; }
    }

    private static String findString(Object node, String[] keys) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String key : keys) {
                if (o.has(key) && !o.isNull(key)) {
                    String s = String.valueOf(o.opt(key));
                    if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
                }
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String found = findString(o.opt(it.next()), keys);
                if (!found.isEmpty()) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String found = findString(a.opt(i), keys);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static int findInt(Object node, String[] keys, int def) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String k : keys) if (o.has(k)) return o.optInt(k, def);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                int v = findInt(o.opt(it.next()), keys, Integer.MIN_VALUE);
                if (v != Integer.MIN_VALUE) return v;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                int v = findInt(a.opt(i), keys, Integer.MIN_VALUE);
                if (v != Integer.MIN_VALUE) return v;
            }
        }
        return def;
    }

    private static JSONObject findCameraObject(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (hasAny(o, "ai_stream_url", "rtsp_url", "stream_url") || (o.has("source_type") && "rtsp".equalsIgnoreCase(o.optString("source_type")))) return o;
            if (o.has("cameras")) {
                Object c = o.opt("cameras");
                JSONObject found = findCameraObject(c);
                if (found != null) return found;
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                JSONObject found = findCameraObject(o.opt(it.next()));
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject found = findCameraObject(a.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JSONObject findStreamObject(JSONObject camera) {
        Object streams = camera.opt("streams");
        if (streams instanceof JSONArray) {
            JSONArray a = (JSONArray) streams;
            JSONObject fallback = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.optJSONObject(i);
                if (s == null) continue;
                if (fallback == null) fallback = s;
                String purpose = first(s, "purpose", "stream_purpose", "kind", "name");
                if (purpose.toLowerCase().contains("ai") || purpose.toLowerCase().contains("main")) return s;
            }
            return fallback;
        }
        return streams instanceof JSONObject ? (JSONObject) streams : null;
    }

    private static boolean hasAny(JSONObject o, String... keys) {
        for (String k : keys) if (o.has(k) && !o.optString(k, "").isEmpty()) return true;
        return false;
    }

    private static String first(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
        }
        return "";
    }

    private static boolean containsError(String body) {
        if (body == null) return false;
        String s = body.toLowerCase();
        return (s.contains("\"error\"") && !s.contains("\"error\":null")) || s.contains("traceback");
    }

    private static boolean containsRpcHint(String body) {
        if (body == null) return false;
        String s = body.toLowerCase();
        return s.contains("jsonrpc") || s.contains("invalid json") || s.contains("params");
    }

    private static String conciseError(Result r) {
        if (r.message != null && !r.message.isEmpty()) return r.message;
        String b = r.body == null ? "" : r.body.replace('\n', ' ').trim();
        if (b.length() > 220) b = b.substring(0, 220) + "…";
        return "HTTP " + r.code + (b.isEmpty() ? "" : " · " + b);
    }

    private static void copy(Result from, Result to) {
        to.ok = from.ok; to.code = from.code; to.message = from.message; to.body = from.body; to.endpoint = from.endpoint;
    }
}
