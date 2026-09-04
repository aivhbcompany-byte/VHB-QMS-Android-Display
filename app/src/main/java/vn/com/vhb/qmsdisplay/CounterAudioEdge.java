package vn.com.vhb.qmsdisplay;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Counter-local WAV audio engine for VHB QMS Android Display 2.3.1.
 *
 * Audio policy:
 * - Uses local WAV files, never Android TTS.
 * - Audio is disabled by default to avoid duplicating the existing central/Agent speaker.
 * - A full audio ZIP is imported once. Later ZIPs may contain only replacement WAV files.
 * - Replacement is by exact lower-case filename, so changing a voice does not require code changes.
 * - Queue is serialized and bounded so rapid calls never overlap indefinitely.
 * - Initial page state only primes the detector; it is not replayed after reload/reboot.
 */
final class CounterAudioEdge {
    static final String PREF_ENABLED = "counter_audio_enabled";
    static final String PREF_REPEAT = "counter_audio_repeat";
    static final String PREF_GAP_MS = "counter_audio_gap_ms";
    static final String PREF_VOLUME = "counter_audio_volume";
    static final int REQ_IMPORT_AUDIO_ZIP = 2311;

    private static final String EXTENSION_URI = "resource://android/assets/counter_audio/";
    private static final String EXTENSION_ID = "counter-audio@vhb.vn";
    private static final String NATIVE_APP = "vhb_qms_counter_audio";
    private static final String PACK_CODE = "vi_female";
    private static final int QUEUE_MAX = 5;
    private static final int TOKEN_GAP_MS = 20;
    private static final long MAX_WAV_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_ZIP_AUDIO_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRIES = 128;

    private final Activity activity;
    private final Handler main;
    private SharedPreferences prefs;
    private GeckoSession activeSession;
    private String trustedUrl = "";

    private final ArrayDeque<Announcement> queue = new ArrayDeque<>();
    private final Set<String> queuedKeys = new HashSet<>();
    private Announcement current;
    private int currentRepeat;
    private int currentTokenIndex;
    private MediaPlayer player;
    private String lastObservedKey = "";
    private boolean packReady;
    private String packStatus = "Chưa kiểm tra";

    CounterAudioEdge(Activity activity, Handler main) {
        this.activity = activity;
        this.main = main;
    }

    void initialize(SharedPreferences prefs) {
        this.prefs = prefs;
        refreshPackStatus();
    }

    void bind(GeckoRuntime runtime, GeckoSession session, String configuredUrl) {
        if (runtime == null || session == null) return;
        activeSession = session;
        trustedUrl = configuredUrl == null ? "" : configuredUrl.trim();
        final GeckoSession targetSession = session;
        try {
            runtime.getWebExtensionController()
                    .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
                    .accept(extension -> {
                        if (activeSession != targetSession) return;
                        try {
                            targetSession.getWebExtensionController().setMessageDelegate(
                                    extension, messageDelegate, NATIVE_APP);
                        } catch (Throwable ignored) { }
                    }, error -> { });
        } catch (Throwable ignored) { }
    }

    AdminControls createAdminControls() {
        return new AdminControls(this);
    }

    void resetObservation() {
        lastObservedKey = "";
        clearPending(false);
    }

    void shutdown() {
        clearPending(true);
        activeSession = null;
    }

    String status() {
        refreshPackStatus();
        return packStatus;
    }

    boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMPORT_AUDIO_ZIP) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        importAudioZip(data.getData());
        return true;
    }

    void requestImportAudioZip() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed", "application/octet-stream"
            });
            activity.startActivityForResult(intent, REQ_IMPORT_AUDIO_ZIP);
        } catch (Throwable error) {
            Toast.makeText(activity, "Không mở được trình chọn file ZIP", Toast.LENGTH_LONG).show();
        }
    }

    private final WebExtension.MessageDelegate messageDelegate = new WebExtension.MessageDelegate() {
        @Override public GeckoResult<Object> onMessage(
                String nativeApp, Object message, WebExtension.MessageSender sender) {
            if (!NATIVE_APP.equals(nativeApp)) return null;
            if (!(message instanceof JSONObject) || sender == null) return null;
            if (sender.session != activeSession || !sender.isTopLevel()) return null;
            if (!isTrustedSender(sender.url)) return null;

            JSONObject json = (JSONObject) message;
            String type = json.optString("type", "");
            if ("counter_idle".equals(type)) {
                main.post(() -> lastObservedKey = "");
                return null;
            }
            if (!"counter_state".equals(type)) return null;

            final String ticket = safeToken(json.optString("ticket", ""), 32);
            final String counter = safeText(json.optString("counter", ""), 80);
            final String callId = safeToken(json.optString("callId", ""), 96);
            final boolean initial = json.optBoolean("initial", false);
            main.post(() -> handleState(ticket, counter, callId, initial));
            return null;
        }
    };

    private boolean isTrustedSender(String senderUrl) {
        try {
            if (trustedUrl.isEmpty() || senderUrl == null || senderUrl.trim().isEmpty()) return false;
            Uri expected = Uri.parse(trustedUrl);
            Uri actual = Uri.parse(senderUrl);
            if (expected.getHost() == null || actual.getHost() == null) return false;
            if (!expected.getHost().equalsIgnoreCase(actual.getHost())) return false;
            if (!lower(expected.getScheme()).equals(lower(actual.getScheme()))) return false;
            return effectivePort(expected) == effectivePort(actual);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int effectivePort(Uri uri) {
        int port = uri.getPort();
        if (port >= 0) return port;
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void handleState(String ticket, String counter, String callId, boolean initial) {
        if (ticket.isEmpty() || ticket.matches("0+")) return;
        String key = !callId.isEmpty()
                ? "call:" + callId
                : "ticket:" + lower(counter) + "|" + lower(ticket);

        if (initial) {
            lastObservedKey = key;
            return;
        }
        if (key.equals(lastObservedKey)) return;
        lastObservedKey = key;

        if (prefs == null || !prefs.getBoolean(PREF_ENABLED, false)) return;
        refreshPackStatus();
        if (!packReady) return;

        int repeats = clamp(prefs.getInt(PREF_REPEAT, 1), 1, 3);
        int gapMs = clamp(prefs.getInt(PREF_GAP_MS, 600), 0, 5000);
        int volume = clamp(prefs.getInt(PREF_VOLUME, 100), 0, 100);
        List<String> tokens = buildTokens(ticket, counter);
        if (tokens.isEmpty()) return;
        enqueue(key, tokens, repeats, gapMs, volume, false);
    }

    private List<String> buildTokens(String ticket, String counter) {
        ArrayList<String> out = new ArrayList<>();
        if (hasFile("xin_moi_so.wav")) {
            out.add("xin_moi_so.wav");
        } else {
            out.add("xin_moi.wav");
            out.add("so.wav");
        }
        appendCodeTokens(out, ticket);

        String counterCode = extractCounterCode(counter);
        if (!counterCode.isEmpty()) {
            if (hasFile("den_quay_so.wav")) {
                out.add("den_quay_so.wav");
            } else {
                out.add("den_quay.wav");
                out.add("so.wav");
            }
            appendCodeTokens(out, counterCode);
        }
        return out;
    }

    private void appendCodeTokens(List<String> out, String value) {
        if (value == null) return;
        String upper = value.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            String token = tokenFor(c);
            if (token != null) out.add(token);
        }
    }

    private String tokenFor(char c) {
        switch (c) {
            case '0': return "khong.wav";
            case '1': return "mot.wav";
            case '2': return "hai.wav";
            case '3': return "ba.wav";
            case '4': return "bon.wav";
            case '5': return "nam.wav";
            case '6': return "sau.wav";
            case '7': return "bay.wav";
            case '8': return "tam.wav";
            case '9': return "chin.wav";
            default:
                if (c >= 'A' && c <= 'Z') return Character.toLowerCase(c) + ".wav";
                return null;
        }
    }

    private String extractCounterCode(String counter) {
        if (counter == null) return "";
        String cleaned = counter.trim()
                .replaceAll("(?i)quầy", " ")
                .replaceAll("(?i)quay", " ")
                .replaceAll("(?i)số", " ")
                .replaceAll("(?i)so", " ")
                .trim();
        String[] parts = cleaned.split("\\s+");
        for (int i = parts.length - 1; i >= 0; i--) {
            String token = safeToken(parts[i], 24);
            if (!token.isEmpty()) return token;
        }
        return "";
    }

    private void enqueue(String key, List<String> tokens, int repeats, int gapMs, int volume, boolean userTest) {
        if (key == null || key.isEmpty() || tokens == null || tokens.isEmpty()) return;
        if (!userTest && ((current != null && key.equals(current.key)) || queuedKeys.contains(key))) return;

        for (String token : tokens) {
            if (!hasFile(token)) {
                packStatus = "Thiếu file âm thanh: " + token;
                if (userTest) Toast.makeText(activity, packStatus, Toast.LENGTH_LONG).show();
                return;
            }
        }

        while (queue.size() >= QUEUE_MAX) {
            Announcement dropped = queue.pollFirst();
            if (dropped != null) queuedKeys.remove(dropped.key);
        }

        Announcement item = new Announcement(
                key,
                new ArrayList<>(tokens),
                clamp(repeats, 1, 3),
                clamp(gapMs, 0, 5000),
                clamp(volume, 0, 100));
        queue.addLast(item);
        queuedKeys.add(key);
        playNext();
    }

    private void playNext() {
        if (current != null || player != null) return;
        current = queue.pollFirst();
        if (current == null) return;
        currentRepeat = 1;
        currentTokenIndex = 0;
        playCurrentToken();
    }

    private void playCurrentToken() {
        if (current == null) return;
        if (currentTokenIndex >= current.tokens.size()) {
            if (currentRepeat < current.repeats) {
                currentRepeat++;
                currentTokenIndex = 0;
                main.postDelayed(this::playCurrentToken, current.gapMs);
            } else {
                finishCurrent();
            }
            return;
        }

        String token = current.tokens.get(currentTokenIndex);
        File wav = audioFile(token);
        if (!wav.isFile()) {
            packStatus = "Thiếu file âm thanh: " + token;
            finishCurrent();
            return;
        }

        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(wav.getAbsolutePath());
            float v = current.volume / 100.0f;
            mp.setVolume(v, v);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(done -> {
                releasePlayer(done);
                currentTokenIndex++;
                main.postDelayed(this::playCurrentToken, TOKEN_GAP_MS);
            });
            mp.setOnErrorListener((failed, what, extra) -> {
                releasePlayer(failed);
                packStatus = "Lỗi phát file: " + token;
                finishCurrent();
                return true;
            });
            mp.prepareAsync();
        } catch (Throwable error) {
            releasePlayer(player);
            packStatus = "Lỗi phát file: " + token;
            finishCurrent();
        }
    }

    private void finishCurrent() {
        if (current != null) queuedKeys.remove(current.key);
        current = null;
        currentRepeat = 0;
        currentTokenIndex = 0;
        releasePlayer(player);
        main.postDelayed(this::playNext, 80L);
    }

    private void releasePlayer(MediaPlayer candidate) {
        if (candidate == null) return;
        try { candidate.setOnPreparedListener(null); } catch (Throwable ignored) { }
        try { candidate.setOnCompletionListener(null); } catch (Throwable ignored) { }
        try { candidate.setOnErrorListener(null); } catch (Throwable ignored) { }
        try { candidate.stop(); } catch (Throwable ignored) { }
        try { candidate.reset(); } catch (Throwable ignored) { }
        try { candidate.release(); } catch (Throwable ignored) { }
        if (player == candidate) player = null;
    }

    private void clearPending(boolean stopCurrent) {
        queue.clear();
        queuedKeys.clear();
        if (stopCurrent) {
            releasePlayer(player);
            current = null;
            currentRepeat = 0;
            currentTokenIndex = 0;
        } else if (current != null) {
            queuedKeys.add(current.key);
        }
    }

    private File packDir() {
        File dir = new File(new File(activity.getFilesDir(), "audio"), PACK_CODE);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private File audioFile(String name) {
        return new File(packDir(), name);
    }

    private boolean hasFile(String name) {
        return audioFile(name).isFile();
    }

    private void refreshPackStatus() {
        File dir = packDir();
        File[] files = dir.listFiles((d, name) -> name != null && name.matches("[a-z0-9_]+\\.wav"));
        int count = files == null ? 0 : files.length;
        String missing = firstMissingCore(null);
        packReady = missing == null;
        packStatus = packReady
                ? "Sẵn sàng • " + count + " WAV • " + PACK_CODE
                : "Chưa sẵn sàng • thiếu " + missing + " • hãy import audio ZIP";
    }

    private String firstMissingCore(Set<String> stagedNames) {
        String[] digits = {
                "khong.wav", "mot.wav", "hai.wav", "ba.wav", "bon.wav",
                "nam.wav", "sau.wav", "bay.wav", "tam.wav", "chin.wav"
        };
        for (String name : digits) if (!available(name, stagedNames)) return name;
        if (!available("xin_moi_so.wav", stagedNames)) {
            if (!available("xin_moi.wav", stagedNames)) return "xin_moi.wav";
            if (!available("so.wav", stagedNames)) return "so.wav";
        }
        if (!available("den_quay_so.wav", stagedNames)) {
            if (!available("den_quay.wav", stagedNames)) return "den_quay.wav";
            if (!available("so.wav", stagedNames)) return "so.wav";
        }
        return null;
    }

    private boolean available(String name, Set<String> stagedNames) {
        return (stagedNames != null && stagedNames.contains(name)) || hasFile(name);
    }

    private void importAudioZip(Uri uri) {
        Toast.makeText(activity, "Đang kiểm tra và cập nhật bộ âm thanh...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            ImportResult result = importAudioZipWorker(uri);
            main.post(() -> {
                refreshPackStatus();
                Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show();
            });
        }, "vhb-audio-import").start();
    }

    private ImportResult importAudioZipWorker(Uri uri) {
        File stage = new File(activity.getCacheDir(), "audio_import_" + UUID.randomUUID());
        if (!stage.mkdirs()) return new ImportResult(false, "Không tạo được thư mục tạm để import audio");

        LinkedHashSet<String> stagedNames = new LinkedHashSet<>();
        long totalBytes = 0L;
        int entries = 0;
        try (InputStream raw = activity.getContentResolver().openInputStream(uri);
             ZipInputStream zip = raw == null ? null : new ZipInputStream(new BufferedInputStream(raw))) {
            if (zip == null) return new ImportResult(false, "Không đọc được file ZIP");
            ZipEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) return new ImportResult(false, "ZIP có quá nhiều file");
                if (entry.isDirectory()) continue;

                String normalized = entry.getName().replace('\\', '/');
                String base = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (!base.matches("[a-z0-9_]+\\.wav")) continue;

                File temp = new File(stage, base);
                long fileBytes = 0L;
                try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        fileBytes += read;
                        totalBytes += read;
                        if (fileBytes > MAX_WAV_BYTES || totalBytes > MAX_ZIP_AUDIO_BYTES) {
                            return new ImportResult(false, "Bộ âm thanh vượt giới hạn dung lượng an toàn");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                if (!validateWav(temp)) {
                    return new ImportResult(false, "Sai chuẩn WAV PCM mono 16-bit/22050 Hz: " + base);
                }
                stagedNames.add(base);
            }
        } catch (Throwable error) {
            deleteTree(stage);
            return new ImportResult(false, "Không import được audio ZIP: " + safeMessage(error));
        }

        if (stagedNames.isEmpty()) {
            deleteTree(stage);
            return new ImportResult(false, "ZIP không có file WAV hợp lệ");
        }

        String missing = firstMissingCore(stagedNames);
        if (missing != null) {
            deleteTree(stage);
            return new ImportResult(false, "Bộ âm thanh chưa đủ file cốt lõi, thiếu: " + missing);
        }

        try {
            main.post(() -> clearPending(true));
            File targetDir = packDir();
            for (String name : stagedNames) {
                File source = new File(stage, name);
                File target = new File(targetDir, name);
                File swap = new File(targetDir, name + ".new");
                copyFile(source, swap);
                if (target.exists() && !target.delete()) {
                    swap.delete();
                    throw new IllegalStateException("Không ghi đè được " + name);
                }
                if (!swap.renameTo(target)) {
                    copyFile(swap, target);
                    swap.delete();
                }
            }
            deleteTree(stage);
            return new ImportResult(true,
                    "Đã cập nhật " + stagedNames.size() + " file WAV. File cùng tên đã được thay thế.");
        } catch (Throwable error) {
            deleteTree(stage);
            return new ImportResult(false, "Lỗi ghi bộ âm thanh: " + safeMessage(error));
        }
    }

    private static void copyFile(File source, File target) throws Exception {
        byte[] buffer = new byte[32768];
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) out.write(buffer, 0, read);
            }
        }
    }

    private static boolean validateWav(File file) {
        if (file == null || !file.isFile() || file.length() < 44L) return false;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (!"RIFF".equals(readFourCC(in))) return false;
            readLe32(in);
            if (!"WAVE".equals(readFourCC(in))) return false;

            boolean fmtOk = false;
            boolean hasData = false;
            while (in.getFilePointer() + 8L <= in.length()) {
                String id = readFourCC(in);
                long size = readLe32(in) & 0xffffffffL;
                long start = in.getFilePointer();
                if (size < 0L || start + size > in.length()) return false;

                if ("fmt ".equals(id) && size >= 16L) {
                    int format = readLe16(in);
                    int channels = readLe16(in);
                    long sampleRate = readLe32(in) & 0xffffffffL;
                    readLe32(in);
                    readLe16(in);
                    int bits = readLe16(in);
                    fmtOk = format == 1 && channels == 1 && sampleRate == 22050L && bits == 16;
                } else if ("data".equals(id)) {
                    hasData = size > 0L;
                }

                long next = start + size + (size & 1L);
                if (next > in.length()) break;
                in.seek(next);
                if (fmtOk && hasData) return true;
            }
            return fmtOk && hasData;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String readFourCC(RandomAccessFile in) throws Exception {
        byte[] bytes = new byte[4];
        in.readFully(bytes);
        return new String(bytes, "US-ASCII");
    }

    private static int readLe16(RandomAccessFile in) throws Exception {
        int a = in.readUnsignedByte();
        int b = in.readUnsignedByte();
        return a | (b << 8);
    }

    private static int readLe32(RandomAccessFile in) throws Exception {
        int a = in.readUnsignedByte();
        int b = in.readUnsignedByte();
        int c = in.readUnsignedByte();
        int d = in.readUnsignedByte();
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        try { file.delete(); } catch (Throwable ignored) { }
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().trim().isEmpty()) return "không xác định";
        String text = error.getMessage().replaceAll("[\\r\\n\\t]+", " ").trim();
        return text.length() > 120 ? text.substring(0, 120) : text;
    }

    private static String safeToken(String value, int max) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("[^A-Za-z0-9._:-]", "");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String safeText(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static int parseIntOr(String value, int fallback) {
        try { return Integer.parseInt(value == null ? "" : value.trim()); }
        catch (Throwable ignored) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Announcement {
        final String key;
        final List<String> tokens;
        final int repeats;
        final int gapMs;
        final int volume;

        Announcement(String key, List<String> tokens, int repeats, int gapMs, int volume) {
            this.key = key;
            this.tokens = tokens;
            this.repeats = repeats;
            this.gapMs = gapMs;
            this.volume = volume;
        }
    }

    private static final class ImportResult {
        final boolean ok;
        final String message;

        ImportResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    static final class AdminControls {
        private final CounterAudioEdge edge;
        private final CheckBox enabled;
        private final EditText repeats;
        private final EditText gapMs;
        private final EditText volume;
        private final TextView title;
        private final TextView state;
        private final TextView note;
        private final Button importZip;
        private final Button test;

        AdminControls(CounterAudioEdge edge) {
            this.edge = edge;
            Activity activity = edge.activity;
            SharedPreferences prefs = edge.prefs;

            title = text(activity, 16, true);
            title.setTextColor(android.graphics.Color.rgb(4, 80, 130));
            title.setGravity(Gravity.START);
            title.setText("ÂM THANH GỌI SỐ TẠI QUẦY • WAV");

            enabled = new CheckBox(activity);
            enabled.setText("Phát loa gọi số tại màn hình quầy này");
            enabled.setChecked(prefs != null && prefs.getBoolean(PREF_ENABLED, false));

            state = text(activity, 13, false);
            state.setTextColor(android.graphics.Color.DKGRAY);
            state.setGravity(Gravity.START);
            state.setText("Bộ âm thanh: " + edge.status()
                    + "\nNguồn phát: file WAV cục bộ, không sử dụng Android TTS.");

            repeats = new EditText(activity);
            repeats.setHint("Số lần đọc (1-3)");
            repeats.setInputType(InputType.TYPE_CLASS_NUMBER);
            repeats.setText(String.valueOf(clamp(prefs != null ? prefs.getInt(PREF_REPEAT, 1) : 1, 1, 3)));

            gapMs = new EditText(activity);
            gapMs.setHint("Khoảng nghỉ giữa lần đọc, ms (0-5000)");
            gapMs.setInputType(InputType.TYPE_CLASS_NUMBER);
            gapMs.setText(String.valueOf(clamp(prefs != null ? prefs.getInt(PREF_GAP_MS, 600) : 600, 0, 5000)));

            volume = new EditText(activity);
            volume.setHint("Âm lượng 0-100 (%)");
            volume.setInputType(InputType.TYPE_CLASS_NUMBER);
            volume.setText(String.valueOf(clamp(prefs != null ? prefs.getInt(PREF_VOLUME, 100) : 100, 0, 100)));

            importZip = new Button(activity);
            importZip.setText("CẬP NHẬT BỘ ÂM THANH ZIP");
            importZip.setOnClickListener(v -> edge.requestImportAudioZip());

            test = new Button(activity);
            test.setText("KIỂM TRA LOA: SỐ 105 ĐẾN QUẦY 1");
            test.setOnClickListener(v -> {
                edge.refreshPackStatus();
                if (!edge.packReady) {
                    Toast.makeText(activity, edge.packStatus, Toast.LENGTH_LONG).show();
                    return;
                }
                edge.enqueue(
                        "test-" + System.currentTimeMillis(),
                        edge.buildTokens("105", "Quầy 1"),
                        clamp(parseIntOr(repeats.getText().toString(), 1), 1, 3),
                        clamp(parseIntOr(gapMs.getText().toString(), 600), 0, 5000),
                        clamp(parseIntOr(volume.getText().toString(), 100), 0, 100),
                        true);
            });

            note = text(activity, 12, false);
            note.setTextColor(android.graphics.Color.DKGRAY);
            note.setGravity(Gravity.START);
            note.setText("Cách đổi giọng: thay WAV mới nhưng GIỮ NGUYÊN tên file, nén ZIP rồi chọn Cập nhật. "
                    + "Có thể import ZIP đầy đủ hoặc chỉ các WAV cần thay; file cùng tên được ghi đè. "
                    + "Chuẩn bắt buộc: WAV PCM, mono, 16-bit, 22050 Hz. "
                    + "Gọi lại cùng một số chính xác tuyệt đối khi Odoo cung cấp call_id.");
        }

        void addTo(LinearLayout box) {
            box.addView(title);
            box.addView(enabled);
            box.addView(state);
            box.addView(repeats);
            box.addView(gapMs);
            box.addView(volume);
            box.addView(importZip);
            box.addView(test);
            box.addView(note);
        }

        void save() {
            if (edge.prefs == null) return;
            edge.prefs.edit()
                    .putBoolean(PREF_ENABLED, enabled.isChecked())
                    .putInt(PREF_REPEAT, clamp(parseIntOr(repeats.getText().toString(), 1), 1, 3))
                    .putInt(PREF_GAP_MS, clamp(parseIntOr(gapMs.getText().toString(), 600), 0, 5000))
                    .putInt(PREF_VOLUME, clamp(parseIntOr(volume.getText().toString(), 100), 0, 100))
                    .apply();
            edge.resetObservation();
        }

        private static TextView text(Activity activity, int sp, boolean bold) {
            TextView view = new TextView(activity);
            view.setTextSize(sp);
            view.setGravity(Gravity.START);
            if (bold) view.setTypeface(null, 1);
            int pad = Math.round(8 * activity.getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad, pad, pad);
            return view;
        }
    }
}
