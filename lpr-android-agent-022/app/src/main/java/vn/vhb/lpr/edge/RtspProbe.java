package vn.vhb.lpr.edge;

import android.util.Base64;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RtspProbe {
    private RtspProbe() {}

    public static class ProbeResult {
        public boolean ok;
        public int status;
        public long latencyMs;
        public String codec = "";
        public String message = "";
    }

    public static ProbeResult probe(String url, String configuredUser, String configuredPassword) {
        ProbeResult out = new ProbeResult();
        long start = System.currentTimeMillis();
        try {
            URI uri = new URI(url);
            if (!"rtsp".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("URL phải bắt đầu bằng rtsp://");
            String host = uri.getHost();
            if (host == null || host.isEmpty()) throw new IllegalArgumentException("Không đọc được IP/Host Camera");
            int port = uri.getPort() > 0 ? uri.getPort() : 554;

            String user = configuredUser == null ? "" : configuredUser;
            String pass = configuredPassword == null ? "" : configuredPassword;
            if ((user.isEmpty() || pass.isEmpty()) && uri.getUserInfo() != null) {
                String[] p = uri.getUserInfo().split(":", 2);
                if (user.isEmpty()) user = p.length > 0 ? p[0] : "";
                if (pass.isEmpty()) pass = p.length > 1 ? p[1] : "";
            }

            Response first = exchange(host, port, request("DESCRIBE", url, 1, null));
            if (first.status == 200) return success(first, start);
            if (first.status != 401) return failure(first.status, start, "Camera trả RTSP " + first.status);

            String challenge = first.headers.get("www-authenticate");
            if (challenge == null || challenge.isEmpty()) return failure(401, start, "Camera yêu cầu xác thực nhưng không gửi WWW-Authenticate");
            if (user.isEmpty()) return failure(401, start, "Camera yêu cầu tài khoản/mật khẩu");

            String authorization;
            if (challenge.toLowerCase(Locale.US).startsWith("basic")) {
                String b64 = Base64.encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                authorization = "Basic " + b64;
            } else if (challenge.toLowerCase(Locale.US).startsWith("digest")) {
                authorization = digestAuthorization(challenge, user, pass, "DESCRIBE", url);
            } else {
                return failure(401, start, "Kiểu xác thực RTSP chưa hỗ trợ: " + challenge);
            }

            Response second = exchange(host, port, request("DESCRIBE", url, 2, authorization));
            if (second.status == 200) return success(second, start);
            return failure(second.status, start, second.status == 401 ? "Sai tài khoản/mật khẩu Camera" : "Camera trả RTSP " + second.status);
        } catch (Exception e) {
            out.ok = false;
            out.status = -1;
            out.latencyMs = System.currentTimeMillis() - start;
            out.message = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
            return out;
        }
    }

    private static ProbeResult success(Response r, long start) {
        ProbeResult out = new ProbeResult();
        out.ok = true;
        out.status = r.status;
        out.latencyMs = System.currentTimeMillis() - start;
        String lower = (r.body == null ? "" : r.body).toLowerCase(Locale.US);
        if (lower.contains("h265") || lower.contains("hevc")) out.codec = "H265";
        else if (lower.contains("h264")) out.codec = "H264";
        out.message = "RTSP Online" + (out.codec.isEmpty() ? "" : " · " + out.codec) + " · " + out.latencyMs + " ms";
        return out;
    }

    private static ProbeResult failure(int status, long start, String message) {
        ProbeResult out = new ProbeResult();
        out.ok = false;
        out.status = status;
        out.latencyMs = System.currentTimeMillis() - start;
        out.message = message;
        return out;
    }

    private static String request(String method, String url, int cseq, String authorization) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq).append("\r\n");
        sb.append("User-Agent: VHB-LPR-Android/0.2.2-TVBOX\r\n");
        sb.append("Accept: application/sdp\r\n");
        if (authorization != null && !authorization.isEmpty()) sb.append("Authorization: ").append(authorization).append("\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    private static Response exchange(String host, int port, String request) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(6000);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        writer.write(request);
        writer.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Response r = new Response();
        String statusLine = reader.readLine();
        if (statusLine == null) throw new IllegalStateException("Camera đóng kết nối RTSP");
        String[] sp = statusLine.split(" ");
        r.status = sp.length > 1 ? Integer.parseInt(sp[1]) : -1;
        int contentLength = 0;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                String k = line.substring(0, idx).trim().toLowerCase(Locale.US);
                String v = line.substring(idx + 1).trim();
                r.headers.put(k, v);
                if ("content-length".equals(k)) {
                    try { contentLength = Integer.parseInt(v); } catch (Exception ignored) {}
                }
            }
        }
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < contentLength; i++) {
            int ch = reader.read();
            if (ch < 0) break;
            body.append((char) ch);
        }
        r.body = body.toString();
        socket.close();
        return r;
    }

    private static String digestAuthorization(String challenge, String user, String pass, String method, String uri) throws Exception {
        Map<String, String> a = parseDigest(challenge);
        String realm = a.get("realm");
        String nonce = a.get("nonce");
        String qop = a.get("qop");
        String opaque = a.get("opaque");
        if (realm == null || nonce == null) throw new IllegalArgumentException("Digest challenge thiếu realm/nonce");
        if (qop != null && qop.contains(",")) qop = qop.split(",")[0].trim();
        if (qop != null && qop.toLowerCase(Locale.US).contains("auth")) qop = "auth";

        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5(method + ":" + uri);
        String nc = "00000001";
        String cnonce = md5(String.valueOf(System.nanoTime())).substring(0, 16);
        String response = qop == null || qop.isEmpty()
                ? md5(ha1 + ":" + nonce + ":" + ha2)
                : md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);

        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(user).append("\"");
        sb.append(", realm=\"").append(realm).append("\"");
        sb.append(", nonce=\"").append(nonce).append("\"");
        sb.append(", uri=\"").append(uri).append("\"");
        sb.append(", response=\"").append(response).append("\"");
        sb.append(", algorithm=MD5");
        if (opaque != null && !opaque.isEmpty()) sb.append(", opaque=\"").append(opaque).append("\"");
        if (qop != null && !qop.isEmpty()) sb.append(", qop=").append(qop).append(", nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\"");
        return sb.toString();
    }

    private static Map<String, String> parseDigest(String challenge) {
        Map<String, String> map = new HashMap<>();
        String value = challenge.replaceFirst("(?i)^Digest\\s+", "");
        Pattern p = Pattern.compile("(\\w+)=((\\\"[^\\\"]*\\\")|([^,]+))");
        Matcher m = p.matcher(value);
        while (m.find()) {
            String k = m.group(1).toLowerCase(Locale.US);
            String v = m.group(2).trim();
            if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            map.put(k, v);
        }
        return map;
    }

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static class Response {
        int status;
        String body = "";
        Map<String, String> headers = new HashMap<>();
    }
}
