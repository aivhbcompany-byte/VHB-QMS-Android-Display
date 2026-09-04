from pathlib import Path

JAVA = Path('app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java')
AUDIO = Path('app/src/main/java/vn/com/vhb/qmsdisplay/CounterAudioEdge.java')
GRADLE = Path('app/build.gradle')

def once(text, old, new, label):
    if text.count(old) != 1:
        raise SystemExit(f'{label}: expected exactly one match, got {text.count(old)}')
    return text.replace(old, new, 1)

# 2.3.1 -> 2.3.2 metadata / admin title
src = JAVA.read_text(encoding='utf-8')
src = once(src,
    'private static final String VERSION = "2.3.1";',
    'private static final String VERSION = "2.3.2";',
    'MainActivity VERSION')
src = once(src,
    'VHB QMS Display 2.3.1 • WAV COUNTER AUDIO',
    'VHB QMS Display 2.3.2 • AUDIO PACK MANAGER',
    'admin title')
JAVA.write_text(src, encoding='utf-8')

gradle = GRADLE.read_text(encoding='utf-8')
gradle = once(gradle, 'versionCode 2030100', 'versionCode 2030200', 'versionCode')
gradle = once(gradle, "versionName '2.3.1'", "versionName '2.3.2'", 'versionName')
GRADLE.write_text(gradle, encoding='utf-8')

s = AUDIO.read_text(encoding='utf-8')
if 'Counter-local WAV audio runtime for VHB QMS Android Display 2.3.1.' not in s:
    raise SystemExit('Expected CounterAudioEdge 2.3.1 baseline')

s = once(s, 'import android.content.Intent;\n',
         'import android.content.Intent;\nimport android.database.Cursor;\n', 'Cursor import')
s = once(s, 'import android.os.Handler;\n',
         'import android.os.Handler;\nimport android.provider.OpenableColumns;\n', 'OpenableColumns import')
s = once(s, 'import java.io.RandomAccessFile;\n',
         'import java.io.RandomAccessFile;\nimport java.text.SimpleDateFormat;\n', 'SimpleDateFormat import')
s = once(s, 'import java.util.UUID;\n',
         'import java.util.UUID;\nimport java.util.Date;\n', 'Date import')
s = once(s,
         '/** Counter-local WAV audio runtime for VHB QMS Android Display 2.3.1. */',
         '/** Counter-local WAV audio runtime for VHB QMS Android Display 2.3.2. */',
         'audio version comment')

s = once(s,
'''    static final String PREF_VOLUME = "counter_audio_volume";\n''',
'''    static final String PREF_VOLUME = "counter_audio_volume";\n    static final String PREF_PACK_NAME = "counter_audio_pack_name";\n    static final String PREF_PACK_UPDATED_AT = "counter_audio_pack_updated_at";\n    static final String PREF_PACK_IMPORTED_COUNT = "counter_audio_pack_imported_count";\n''', 'pack preference keys')

s = once(s,
'''    private String packStatus = "Chưa kiểm tra";\n''',
'''    private String packStatus = "Chưa kiểm tra";\n    private AdminControls activeAdminControls;\n''', 'active admin control field')

s = once(s,
'''    AdminControls createAdminControls() {\n        return new AdminControls(this);\n    }\n''',
'''    AdminControls createAdminControls() {\n        activeAdminControls = new AdminControls(this);\n        return activeAdminControls;\n    }\n''', 'createAdminControls')

s = once(s,
'''    private void refreshPackStatus() {\n        File[] files = packDir().listFiles((d, name) -> name != null && name.matches("[a-z0-9_]+\\\\.wav"));\n        int count = files == null ? 0 : files.length;\n        String missing = firstMissingCore(null);\n        packReady = missing == null;\n        packStatus = packReady\n                ? "Sẵn sàng • " + count + " WAV • " + PACK_CODE\n                : "Chưa sẵn sàng • thiếu " + missing + " • hãy import audio ZIP";\n    }\n''',
'''    private int wavCount() {\n        File[] files = packDir().listFiles((d, name) -> name != null && name.matches("[a-z0-9_]+\\\\.wav"));\n        return files == null ? 0 : files.length;\n    }\n\n    private void refreshPackStatus() {\n        int count = wavCount();\n        String missing = firstMissingCore(null);\n        packReady = missing == null;\n        packStatus = packReady\n                ? "Sẵn sàng • " + count + " WAV • " + PACK_CODE\n                : "Chưa sẵn sàng • thiếu " + missing + " • hãy import audio ZIP";\n    }\n\n    private String adminPackSummary() {\n        refreshPackStatus();\n        String packName = prefs == null ? "" : prefs.getString(PREF_PACK_NAME, "");\n        long updatedAt = prefs == null ? 0L : prefs.getLong(PREF_PACK_UPDATED_AT, 0L);\n        int imported = prefs == null ? 0 : prefs.getInt(PREF_PACK_IMPORTED_COUNT, 0);\n        if (packName == null || packName.trim().isEmpty()) {\n            packName = wavCount() > 0 ? "Đã nạp từ phiên bản trước" : "Chưa chọn file ZIP";\n        }\n        String updatedText = updatedAt > 0L\n                ? new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date(updatedAt))\n                : "Chưa có dữ liệu";\n        String statusText = packReady ? "✓ Sẵn sàng" : "⚠ " + packStatus;\n        String changed = imported > 0 ? " • lần gần nhất " + imported + " WAV" : "";\n        return "Bộ âm thanh hiện tại: " + packName\n                + "\\nTrạng thái: " + statusText\n                + "\\nTổng file hiện có: " + wavCount() + " WAV" + changed\n                + "\\nCập nhật lần cuối: " + updatedText\n                + "\\nNguồn phát: WAV cục bộ • " + PACK_CODE + " • không dùng Android TTS";\n    }\n''', 'pack status / summary')

s = once(s,
'''    private void importAudioZip(Uri uri) {\n        Toast.makeText(activity, "Đang kiểm tra và cập nhật bộ âm thanh...", Toast.LENGTH_SHORT).show();\n        new Thread(() -> {\n            ImportResult result = importAudioZipWorker(uri);\n            main.post(() -> {\n                refreshPackStatus();\n                Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show();\n            });\n        }, "vhb-audio-import").start();\n    }\n\n    private ImportResult importAudioZipWorker(Uri uri) {\n''',
'''    private void importAudioZip(Uri uri) {\n        final String sourceName = queryDisplayName(uri);\n        Toast.makeText(activity, "Đang kiểm tra và cập nhật bộ âm thanh...", Toast.LENGTH_SHORT).show();\n        new Thread(() -> {\n            ImportResult result = importAudioZipWorker(uri);\n            main.post(() -> {\n                if (result.ok && prefs != null) {\n                    prefs.edit()\n                            .putString(PREF_PACK_NAME, sourceName.isEmpty() ? "audio.zip" : sourceName)\n                            .putLong(PREF_PACK_UPDATED_AT, System.currentTimeMillis())\n                            .putInt(PREF_PACK_IMPORTED_COUNT, result.importedCount)\n                            .apply();\n                }\n                refreshPackStatus();\n                if (activeAdminControls != null) activeAdminControls.refreshState();\n                Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show();\n            });\n        }, "vhb-audio-import").start();\n    }\n\n    private String queryDisplayName(Uri uri) {\n        if (uri == null) return "";\n        try (Cursor cursor = activity.getContentResolver().query(\n                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {\n            if (cursor != null && cursor.moveToFirst()) {\n                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);\n                if (index >= 0) {\n                    String name = cursor.getString(index);\n                    return name == null ? "" : name.trim();\n                }\n            }\n        } catch (Throwable ignored) { }\n        String tail = uri.getLastPathSegment();\n        if (tail == null) return "";\n        int cut = Math.max(tail.lastIndexOf('/'), tail.lastIndexOf(':'));\n        return (cut >= 0 ? tail.substring(cut + 1) : tail).trim();\n    }\n\n    private void showAudioFolderInfo() {\n        refreshPackStatus();\n        File[] files = packDir().listFiles((d, name) -> name != null && name.matches("[a-z0-9_]+\\\\.wav"));\n        ArrayList<File> list = new ArrayList<>();\n        if (files != null) {\n            java.util.Collections.addAll(list, files);\n            list.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));\n        }\n        StringBuilder text = new StringBuilder();\n        text.append(adminPackSummary()).append("\\n\\n");\n        text.append("Thư mục nội bộ: files/audio/").append(PACK_CODE).append("\\n");\n        text.append("Android bảo vệ thư mục này; danh sách WAV thực tế đang dùng:\\n");\n        for (File file : list) {\n            long kb = Math.max(1L, (file.length() + 1023L) / 1024L);\n            text.append("• ").append(file.getName()).append("  (").append(kb).append(" KB)\\n");\n        }\n        if (list.isEmpty()) text.append("(chưa có WAV)\\n");\n        new android.app.AlertDialog.Builder(activity)\n                .setTitle("Thư mục âm thanh")\n                .setMessage(text.toString())\n                .setPositiveButton("ĐÓNG", null)\n                .show();\n    }\n\n    private ImportResult importAudioZipWorker(Uri uri) {\n''', 'audio import metadata and folder viewer')

s = once(s,
'''            return new ImportResult(true,\n                    "Đã cập nhật " + stagedNames.size() + " file WAV. File cùng tên đã được thay thế.");\n''',
'''            return new ImportResult(true,\n                    "Đã cập nhật " + stagedNames.size() + " file WAV. File cùng tên đã được thay thế.",\n                    stagedNames.size());\n''', 'successful import count')

s = once(s,
'''    private static final class ImportResult {\n        final boolean ok;\n        final String message;\n\n        ImportResult(boolean ok, String message) {\n            this.ok = ok;\n            this.message = message;\n        }\n    }\n''',
'''    private static final class ImportResult {\n        final boolean ok;\n        final String message;\n        final int importedCount;\n\n        ImportResult(boolean ok, String message) {\n            this(ok, message, 0);\n        }\n\n        ImportResult(boolean ok, String message, int importedCount) {\n            this.ok = ok;\n            this.message = message;\n            this.importedCount = importedCount;\n        }\n    }\n''', 'ImportResult metadata')

s = once(s,
'''        private final EditText volume;\n        private final TextView title;\n''',
'''        private final EditText volume;\n        private final TextView repeatsLabel;\n        private final TextView gapLabel;\n        private final TextView volumeLabel;\n        private final TextView title;\n''', 'admin labels fields')
s = once(s,
'''        private final Button importZip;\n        private final Button test;\n''',
'''        private final Button importZip;\n        private final Button openFolder;\n        private final Button test;\n''', 'open folder field')

s = once(s,
'''            state.setText("Bộ âm thanh: " + edge.status()\n                    + "\\nNguồn phát: file WAV cục bộ, không sử dụng Android TTS. Mặc định TẮT để tránh trùng loa trung tâm.");\n\n            repeats = new EditText(activity);\n            repeats.setHint("Số lần đọc (1-3)");\n''',
'''            state.setText(edge.adminPackSummary());\n\n            repeatsLabel = text(activity, 12, true);\n            repeatsLabel.setText("Số lần đọc (1-3)");\n            repeats = new EditText(activity);\n            repeats.setHint("Ví dụ: 1");\n''', 'state and repeat label')

s = once(s,
'''            gapMs = new EditText(activity);\n            gapMs.setHint("Khoảng nghỉ giữa lần đọc, ms (0-5000)");\n''',
'''            gapLabel = text(activity, 12, true);\n            gapLabel.setText("Khoảng nghỉ giữa các lần đọc (ms, 0-5000)");\n            gapMs = new EditText(activity);\n            gapMs.setHint("Ví dụ: 600");\n''', 'gap label')

s = once(s,
'''            volume = new EditText(activity);\n            volume.setHint("Âm lượng 0-100 (%)");\n''',
'''            volumeLabel = text(activity, 12, true);\n            volumeLabel.setText("Âm lượng loa tại quầy (0-100%)");\n            volume = new EditText(activity);\n            volume.setHint("Ví dụ: 100");\n''', 'volume label')

s = once(s,
'''            importZip.setText("CẬP NHẬT BỘ ÂM THANH (.ZIP)");\n            importZip.setOnClickListener(v -> edge.requestImportAudioZip());\n\n            test = new Button(activity);\n''',
'''            importZip.setText("CẬP NHẬT BỘ ÂM THANH (.ZIP)");\n            importZip.setOnClickListener(v -> edge.requestImportAudioZip());\n\n            openFolder = new Button(activity);\n            openFolder.setText("MỞ THƯ MỤC ÂM THANH");\n            openFolder.setOnClickListener(v -> edge.showAudioFolderInfo());\n\n            test = new Button(activity);\n''', 'open folder button')

s = once(s,
'''            note.setText("Lần đầu chọn audio(1).zip để nạp bộ WAV. Khi đổi giọng, chỉ cần thay WAV mới nhưng GIỮ NGUYÊN tên file rồi import ZIP lại. "\n                    + "ZIP có thể đầy đủ hoặc chỉ chứa các WAV cần thay; file cùng tên được ghi đè. Chuẩn: WAV PCM, mono, 16-bit, 22050 Hz. "\n                    + "Gọi lại cùng một số chính xác tuyệt đối khi Odoo cung cấp call_id.");\n''',
'''            note.setText("Khi đổi giọng, thay WAV mới nhưng GIỮ NGUYÊN tên file rồi import ZIP lại. "\n                    + "ZIP có thể đầy đủ hoặc chỉ chứa các WAV cần thay; file cùng tên được ghi đè. "\n                    + "Chuẩn: WAV PCM, mono, 16-bit, 22050 Hz. Nút Thư mục âm thanh hiển thị đúng danh sách WAV app đang sử dụng.");\n''', 'admin note')

s = once(s,
'''            box.addView(state);\n            box.addView(repeats);\n            box.addView(gapMs);\n            box.addView(volume);\n            box.addView(importZip);\n            box.addView(test);\n''',
'''            box.addView(state);\n            box.addView(repeatsLabel);\n            box.addView(repeats);\n            box.addView(gapLabel);\n            box.addView(gapMs);\n            box.addView(volumeLabel);\n            box.addView(volume);\n            box.addView(importZip);\n            box.addView(openFolder);\n            box.addView(test);\n''', 'admin layout')

s = once(s,
'''        void save() {\n''',
'''        void refreshState() {\n            state.setText(edge.adminPackSummary());\n        }\n\n        void save() {\n''', 'refresh state method')

AUDIO.write_text(s, encoding='utf-8')
print('Applied VHB QMS Android Display 2.3.2 Audio Pack Manager')
