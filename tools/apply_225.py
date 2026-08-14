from pathlib import Path

java_path = Path('app/src/main/java/vn/com/vhb/qmsdisplay/MainActivity.java')
gradle_path = Path('app/build.gradle')

src = java_path.read_text(encoding='utf-8')

src = src.replace('import android.widget.LinearLayout;\n', 'import android.widget.LinearLayout;\nimport android.widget.ScrollView;\n')
if 'import android.view.inputmethod.InputMethodManager;' not in src:
    src = src.replace('import android.view.WindowManager;\n', 'import android.view.WindowManager;\nimport android.view.inputmethod.InputMethodManager;\n')
src = src.replace('private static final String VERSION = "2.2.4";', 'private static final String VERSION = "2.2.5";')

start = src.index('    private void showAdmin() {')
end = src.index('    private void requestHomeRole() {', start)

new_method = r'''    private void showAdmin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        box.setFocusableInTouchMode(true);

        EditText url = new EditText(this);
        url.setHint("URL hệ thống VHB QMS");
        url.setText(prefs.getString("url", ""));

        EditText pin = new EditText(this);
        pin.setHint("PIN quản trị");
        pin.setText(prefs.getString("admin_pin", "1234"));
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        Button hideIme = new Button(this);
        hideIme.setText("Ẩn bàn phím");
        hideIme.setOnClickListener(v -> {
            url.clearFocus();
            pin.clearFocus();
            box.requestFocus();
            hideKeyboard(v);
        });

        CheckBox auto = new CheckBox(this);
        auto.setText("Tự khởi động khi bật Android Box");
        auto.setChecked(prefs.getBoolean("auto_start", true));
        auto.setOnClickListener(v -> hideKeyboard(v));

        CheckBox home = new CheckBox(this);
        home.setText("Dùng VHB QMS làm HOME Launcher chuyên dụng");
        home.setChecked(prefs.getBoolean("dedicated_home", true));
        home.setOnClickListener(v -> hideKeyboard(v));

        TextView homeState = label(14, false);
        homeState.setTextColor(Color.DKGRAY);
        homeState.setText(isDefaultHome()
                ? "HOME hiện tại: VHB QMS đang là HOME mặc định"
                : "HOME hiện tại: chưa đặt VHB QMS làm HOME mặc định");

        Button setHome = new Button(this);
        setHome.setText("Đặt / đổi HOME mặc định");
        setHome.setOnClickListener(v -> {
            url.clearFocus();
            pin.clearFocus();
            hideKeyboard(v);
            requestHomeRole();
        });

        Button settings = new Button(this);
        settings.setText("Mở cài đặt Android");
        settings.setOnClickListener(v -> {
            url.clearFocus();
            pin.clearFocus();
            hideKeyboard(v);
            openAndroidSettings();
        });

        box.addView(url);
        box.addView(pin);
        box.addView(hideIme);
        box.addView(auto);
        box.addView(home);
        box.addView(homeState);
        box.addView(setHome);
        box.addView(settings);

        box.setOnTouchListener((v, event) -> {
            if (event != null && event.getAction() == MotionEvent.ACTION_DOWN) {
                View focused = getCurrentFocus();
                if (focused instanceof EditText) {
                    focused.clearFocus();
                    box.requestFocus();
                    hideKeyboard(focused);
                }
            }
            return false;
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(box, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        scroll.setOnTouchListener((v, event) -> {
            if (event != null && event.getAction() == MotionEvent.ACTION_DOWN) {
                View focused = getCurrentFocus();
                if (focused instanceof EditText) {
                    focused.clearFocus();
                    box.requestFocus();
                    hideKeyboard(focused);
                }
            }
            return false;
        });

        AlertDialog adminDialog = new AlertDialog.Builder(this)
                .setTitle("VHB QMS Display 2.2.5 • HOME Launcher")
                .setView(scroll)
                .setPositiveButton("Lưu & mở", (dialog, which) -> {
                    hideKeyboard(url);
                    String normalized = normalize(url.getText().toString());
                    if (normalized.isEmpty()) {
                        Toast.makeText(this, "URL không hợp lệ", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String newPin = pin.getText().toString().trim();
                    if (newPin.isEmpty()) newPin = "1234";

                    prefs.edit()
                            .putString("url", normalized)
                            .putString("admin_pin", newPin)
                            .putBoolean("auto_start", auto.isChecked())
                            .putBoolean("dedicated_home", home.isChecked())
                            .apply();

                    try {
                        createSession();
                        loadConfigured();
                    } catch (Throwable ignored) {
                        recover();
                    }

                    if (home.isChecked() && !isDefaultHome()) {
                        main.postDelayed(this::requestHomeRole, 350L);
                    }
                })
                .setNegativeButton("Đóng", (dialog, which) -> hideKeyboard(url))
                .create();

        adminDialog.setCanceledOnTouchOutside(false);
        adminDialog.setCancelable(true);
        adminDialog.setOnShowListener(ignored -> {
            try {
                if (adminDialog.getWindow() != null) {
                    adminDialog.getWindow().setSoftInputMode(
                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                    adminDialog.getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
                    adminDialog.getWindow().getDecorView().setOnTouchListener((v, event) -> {
                        if (event != null && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                            View focused = adminDialog.getCurrentFocus();
                            if (focused != null) focused.clearFocus();
                            box.requestFocus();
                            hideKeyboard(focused != null ? focused : v);
                            return true;
                        }
                        return false;
                    });
                }
            } catch (Throwable ignored2) { }
        });
        adminDialog.show();
    }

'''

src = src[:start] + new_method + src[end:]

if '    private void hideKeyboard(View anchor) {' not in src:
    marker = '    @Override public boolean onKeyDown(int code, KeyEvent event) {'
    helper = r'''    private void hideKeyboard(View anchor) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                View target = anchor != null ? anchor : getWindow().getDecorView();
                imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
            }
        } catch (Throwable ignored) { }
    }

'''
    if marker not in src:
        raise RuntimeError('Cannot locate onKeyDown insertion marker')
    src = src.replace(marker, helper + marker, 1)

java_path.write_text(src, encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
gradle = gradle.replace("versionCode 2020400", "versionCode 2020500")
gradle = gradle.replace("versionName '2.2.4'", "versionName '2.2.5'")
gradle_path.write_text(gradle, encoding='utf-8')

print('Applied VHB QMS Display 2.2.5 admin dialog/keyboard fix')
