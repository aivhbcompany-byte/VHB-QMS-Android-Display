package vn.com.vhb.qmsdisplay;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Counter-local audio runtime for VHB QMS Android Display.
 *
 * <p>Design constraints:
 * <ul>
 *   <li>Disabled by default so install-over upgrades cannot duplicate the existing Agent/central audio.</li>
 *   <li>Observes only the already configured GeckoSession through a built-in WebExtension.</li>
 *   <li>Validates the sender against the configured display origin before accepting page state.</li>
 *   <li>Primes the current ticket without speaking after boot/reload/reconnect.</li>
 *   <li>Dedupe uses server call_id when available; otherwise counter + ticket.</li>
 *   <li>Serial queue prevents overlapping announcements.</li>
 * </ul>
 */
final class CounterAudioEdge {
    static final String PREF_ENABLED = "counter_audio_enabled";
    static final String PREF_REPEAT = "counter_audio_repeat";
    static final String PREF_GAP_MS = "counter_audio_gap_ms";
    static final String PREF_VOLUME = "counter_audio_volume";

    private static final String EXTENSION_URI = "resource://android/assets/counter_audio/";
    private static final String EXTENSION_ID = "counter-audio@vhb.vn";
    private static final String NATIVE_APP = "vhb_qms_counter_audio";
    private static final int QUEUE_MAX = 5;

    private final Activity activity;
    private final Handler main;
    private SharedPreferences prefs;
    private GeckoSession activeSession;
    private String trustedUrl = "";

    private TextToSpeech tts;
    private boolean ttsInitializing;
    private boolean ttsReady;
    private String ttsStatus = "Chưa khởi tạo";

    private final ArrayDeque<Announcement> queue = new ArrayDeque<>();
    private final Set<String> queuedKeys = new HashSet<>();
    private Announcement current;
    private int currentRepeat;
    private String currentUtteranceId = "";
    private String lastObservedKey = "";
    private long utteranceSeq;

    CounterAudioEdge(Activity activity, Handler main) {
        this.activity = activity;
        this.main = main;
    }

    void initialize(SharedPreferences prefs) {
        this.prefs = prefs;
        initTts();
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
                    }, error -> {
                        // Fail closed. The display remains operational if the local bridge fails.
                    });
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
        try {
            if (tts != null) tts.shutdown();
        } catch (Throwable ignored) { }
        tts = null;
        ttsReady = false;
        ttsInitializing = false;
        activeSession = null;
    }

    String status() {
        return ttsStatus;
    }

    private final WebExtension.MessageDelegate messageDelegate = new WebExtension.MessageDelegate() {
        @Override public GeckoResult<Object> onMessage(
                String nativeApp, Object message, WebExtension.MessageSender sender) {
            if (!NATIVE_APP.equals(nativeApp)) return null;
            if (!(message instanceof JSONObject) || sender == null) return null;
            if (sender.session != activeSession || !sender.isTopLevel()) return null;
            if (!isTrustedSender(sender.url)) return null;

            JSONObject json = (JSONObject) message;
            if (!"counter_state".equals(json.optString("type", ""))) return null;

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

        // Initial state only establishes a baseline. Never replay after reboot/reload.
        if (initial) {
            lastObservedKey = key;
            return;
        }
        if (key.equals(lastObservedKey)) return;
        lastObservedKey = key;

        // Keep observation state current even while audio is disabled, so enabling it
        // does not unexpectedly replay the ticket already on screen.
        if (prefs == null || !prefs.getBoolean(PREF_ENABLED, false)) return;

        int repeats = clamp(prefs.getInt(PREF_REPEAT, 1), 1, 3);
        int gapMs = clamp(prefs.getInt(PREF_GAP_MS, 600), 0, 5000);
        int volume = clamp(prefs.getInt(PREF_VOLUME, 100), 0, 100);
        enqueue(key, buildPhrase(ticket, counter), repeats, gapMs, volume, false);
    }

    private String buildPhrase(String ticket, String counter) {
        String spokenTicket = speakableTicket(ticket);
        if (counter == null || counter.trim().isEmpty()) {
            return "Xin mời số " + spokenTicket;
        }
        return "Xin mời số " + spokenTicket + " đến " + counter.trim();
    }

    private String speakableTicket(String ticket) {
        if (ticket == null) return "";
        StringBuilder out = new StringBuilder();
        String upper = ticket.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            String spoken = pronunciation(upper.charAt(i));
            if (spoken == null) continue;
            if (out.length() > 0) out.append(", ");
            out.append(spoken);
        }
        return out.length() > 0 ? out.toString() : ticket.trim();
    }

    private String pronunciation(char c) {
        switch (c) {
            case '0': return "không";
            case '1': return "một";
            case '2': return "hai";
            case '3': return "ba";
            case '4': return "bốn";
            case '5': return "năm";
            case '6': return "sáu";
            case '7': return "bảy";
            case '8': return "tám";
            case '9': return "chín";
            case 'A': return "A";
            case 'B': return "Bê";
            case 'C': return "Xê";
            case 'D': return "Dê";
            case 'E': return "E";
            case 'F': return "Ép";
            case 'G': return "Gờ";
            case 'H': return "Hát";
            case 'I': return "I";
            case 'J': return "Giây";
            case 'K': return "Ca";
            case 'L': return "E lờ";
            case 'M': return "Em mờ";
            case 'N': return "En nờ";
            case 'O': return "O";
            case 'P': return "Pê";
            case 'Q': return "Quy";
            case 'R': return "E rờ";
            case 'S': return "Ét";
            case 'T': return "Tê";
            case 'U': return "U";
            case 'V': return "Vê";
            case 'W': return "Đắp liu";
            case 'X': return "Ích";
            case 'Y': return "I dài";
            case 'Z': return "Dét";
            default: return null;
        }
    }

    private void initTts() {
        if (tts != null || ttsInitializing) return;
        ttsInitializing = true;
        ttsStatus = "Đang khởi tạo";
        try {
            tts = new TextToSpeech(activity.getApplicationContext(), status -> main.post(() -> {
                ttsInitializing = false;
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    ttsReady = false;
                    ttsStatus = "Không khởi tạo được TTS";
                    return;
                }

                int lang = tts.setLanguage(new Locale("vi", "VN"));
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsReady = false;
                    ttsStatus = "Thiếu giọng tiếng Việt";
                    return;
                }

                tts.setSpeechRate(0.92f);
                tts.setPitch(1.0f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onDone(String utteranceId) {
                        main.post(() -> completeUtterance(utteranceId, true));
                    }
                    @Override public void onError(String utteranceId) {
                        main.post(() -> completeUtterance(utteranceId, false));
                    }
                    @Override public void onError(String utteranceId, int errorCode) {
                        main.post(() -> completeUtterance(utteranceId, false));
                    }
                });
                ttsReady = true;
                ttsStatus = "Sẵn sàng tiếng Việt";
                playNext();
            }));
        } catch (Throwable ignored) {
            ttsInitializing = false;
            ttsReady = false;
            ttsStatus = "Lỗi TTS";
            tts = null;
        }
    }

    private void enqueue(String key, String text, int repeats, int gapMs, int volume, boolean userTest) {
        if (key == null || key.isEmpty() || text == null || text.trim().isEmpty()) return;
        if (!userTest) {
            if ((current != null && key.equals(current.key)) || queuedKeys.contains(key)) return;
        }

        // Drop the oldest pending item first. A fresh call is more useful than stale backlog.
        while (queue.size() >= QUEUE_MAX) {
            Announcement dropped = queue.pollFirst();
            if (dropped != null) queuedKeys.remove(dropped.key);
        }

        Announcement item = new Announcement(
                key,
                text.trim(),
                clamp(repeats, 1, 3),
                clamp(gapMs, 0, 5000),
                clamp(volume, 0, 100));
        queue.addLast(item);
        queuedKeys.add(key);

        if (!ttsReady) {
            initTts();
            if (userTest) {
                Toast.makeText(activity,
                        ttsStatus.startsWith("Thiếu")
                                ? "Android Box chưa có giọng đọc tiếng Việt"
                                : "Đang khởi tạo giọng đọc tiếng Việt",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        playNext();
    }

    private void playNext() {
        if (!ttsReady || tts == null || current != null) return;
        current = queue.pollFirst();
        if (current == null) return;
        currentRepeat = 0;
        speakCurrent();
    }

    private void speakCurrent() {
        if (current == null || !ttsReady || tts == null) return;
        currentRepeat++;
        currentUtteranceId = "vhb-counter-" + (++utteranceSeq);
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, current.volume / 100.0f);
        int result;
        try {
            result = tts.speak(current.text, TextToSpeech.QUEUE_FLUSH, params, currentUtteranceId);
        } catch (Throwable ignored) {
            result = TextToSpeech.ERROR;
        }
        if (result == TextToSpeech.ERROR) completeUtterance(currentUtteranceId, false);
    }

    private void completeUtterance(String utteranceId, boolean success) {
        if (current == null || utteranceId == null || !utteranceId.equals(currentUtteranceId)) return;
        Announcement item = current;
        if (success && currentRepeat < item.repeats) {
            main.postDelayed(this::speakCurrent, item.gapMs);
            return;
        }

        queuedKeys.remove(item.key);
        current = null;
        currentUtteranceId = "";
        main.postDelayed(this::playNext, 120L);
    }

    private void clearPending(boolean stopCurrent) {
        queue.clear();
        queuedKeys.clear();
        if (stopCurrent) {
            try { if (tts != null) tts.stop(); } catch (Throwable ignored) { }
            current = null;
            currentUtteranceId = "";
        } else if (current != null) {
            queuedKeys.add(current.key);
        }
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
        final String text;
        final int repeats;
        final int gapMs;
        final int volume;

        Announcement(String key, String text, int repeats, int gapMs, int volume) {
            this.key = key;
            this.text = text;
            this.repeats = repeats;
            this.gapMs = gapMs;
            this.volume = volume;
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
        private final Button test;

        AdminControls(CounterAudioEdge edge) {
            this.edge = edge;
            Activity activity = edge.activity;
            SharedPreferences prefs = edge.prefs;

            title = text(activity, 16, true);
            title.setTextColor(android.graphics.Color.rgb(4, 80, 130));
            title.setGravity(Gravity.START);
            title.setText("ÂM THANH GỌI SỐ TẠI QUẦY");

            enabled = new CheckBox(activity);
            enabled.setText("Phát loa gọi số tại màn hình quầy này");
            enabled.setChecked(prefs != null && prefs.getBoolean(PREF_ENABLED, false));

            state = text(activity, 13, false);
            state.setTextColor(android.graphics.Color.DKGRAY);
            state.setGravity(Gravity.START);
            state.setText("Giọng đọc Android: " + edge.status() + "\n"
                    + "Chỉ phát số của chính màn hình/quầy đang mở. Mặc định TẮT để tránh trùng loa trung tâm.");

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

            test = new Button(activity);
            test.setText("KIỂM TRA LOA TẠI QUẦY");
            test.setOnClickListener(v -> edge.enqueue(
                    "test-" + System.currentTimeMillis(),
                    "Kiểm tra âm thanh VHB QMS tại quầy",
                    clamp(parseIntOr(repeats.getText().toString(), 1), 1, 3),
                    clamp(parseIntOr(gapMs.getText().toString(), 600), 0, 5000),
                    clamp(parseIntOr(volume.getText().toString(), 100), 0, 100),
                    true));

            note = text(activity, 12, false);
            note.setTextColor(android.graphics.Color.DKGRAY);
            note.setGravity(Gravity.START);
            note.setText("Chống phát trùng theo call_id khi trang Odoo cung cấp; nếu chưa có call_id thì theo số vé. "
                    + "Gọi lại cùng một số sẽ chính xác tuyệt đối khi phía Odoo bổ sung call_id.");
        }

        void addTo(LinearLayout box) {
            box.addView(title);
            box.addView(enabled);
            box.addView(state);
            box.addView(repeats);
            box.addView(gapMs);
            box.addView(volume);
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
