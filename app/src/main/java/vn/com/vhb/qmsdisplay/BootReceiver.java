package vn.com.vhb.qmsdisplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences("vhb_qms_display_settings", Context.MODE_PRIVATE);
        if (!p.getBoolean("auto_start", true)) return;
        PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent i = new Intent(app, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                app.startActivity(i);
            } catch (Throwable ignored) { } finally { pending.finish(); }
        }, 5000L);
    }
}
