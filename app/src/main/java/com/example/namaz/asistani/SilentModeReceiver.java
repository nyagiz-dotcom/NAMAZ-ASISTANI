package com.example.namaz.asistani;
import android.media.AudioManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;
public class SilentModeReceiver extends BroadcastReceiver {
    private static final String PREFS = "prayer_silent_windows";
    private static final String ACTIVE = "active_windows";
    @Override public void onReceive(Context context, Intent intent) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        String key = intent.getStringExtra("windowKey"); if (key == null || key.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> active = new HashSet<>(prefs.getStringSet(ACTIVE, new HashSet<>()));
        boolean wasActive = active.contains(key);
        if ("start".equals(intent.getAction())) {
            if (active.isEmpty()) prefs.edit().putInt("previous_ringer_mode", audio.getRingerMode()).apply();
            active.add(key);
        } else if (wasActive) active.remove(key);
        prefs.edit().putStringSet(ACTIVE, active).apply();
        try {
            if (!active.isEmpty()) audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            else if (wasActive) audio.setRingerMode(prefs.getInt("previous_ringer_mode", AudioManager.RINGER_MODE_NORMAL));
        } catch (SecurityException ignored) {}
    }
}