package com.example.namaz.asistani;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import java.util.HashSet;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Set;
public class PrayerNotificationReceiver extends BroadcastReceiver {
    private static int stableId(String title, long at, int remaining) { return (title + "|" + at + "|" + remaining).hashCode() & 0x7fffffff; }
    public static Uri selectedSystemSound(Context context, String channelType, String soundMode) {
        int type = "alarm".equals(channelType) ? RingtoneManager.TYPE_ALARM : ("ringtone".equals(channelType) ? RingtoneManager.TYPE_RINGTONE : RingtoneManager.TYPE_NOTIFICATION);
        int choice = "notification5".equals(soundMode) ? 4 : ("notification4".equals(soundMode) ? 3 : ("alarm".equals(soundMode) ? 2 : ("ringtone".equals(soundMode) ? 1 : 0)));
        RingtoneManager sounds = new RingtoneManager(context); sounds.setType(type);
        android.database.Cursor cursor = sounds.getCursor();
        try { int count = cursor == null ? 0 : cursor.getCount(); return count == 0 ? RingtoneManager.getDefaultUri(type) : sounds.getRingtoneUri(Math.min(choice, count - 1)); }
        finally { if (cursor != null) cursor.close(); }
    }
    public static Uri resolveSound(Context context, String channelType, String soundMode, String customUri) {
        if ("off".equals(soundMode)) return null;
        if ("file".equals(soundMode)) return customUri == null || customUri.isEmpty() ? null : Uri.parse(customUri);
        if (soundMode != null && soundMode.startsWith("bundled_")) {
            String fileName;
            switch (soundMode) {
                case "bundled_assalatu": fileName = "assalatu_khairum_minan.mp3"; break;
                case "bundled_azan1": fileName = "azan_1.mp3"; break;
                case "bundled_azan2": fileName = "azan_2.mp3"; break;
                case "bundled_azan": fileName = "azan.mp3"; break;
                case "bundled_bestazan": fileName = "best_azan.mp3"; break;
                case "bundled_ezan": fileName = "ezan.mp3"; break;
                case "bundled_fajar": fileName = "fajar_alarm.mp3"; break;
                case "bundled_islamic": fileName = "islamic_ringtone.mp3"; break;
                case "bundled_labbaik": fileName = "labbaik_allahuma_lab.mp3"; break;
                case "bundled_muhammad": fileName = "muhammad_nabeena.mp3"; break;
                case "bundled_rehman": fileName = "rehman_ya_rehman.mp3"; break;
                case "bundled_yanabi": fileName = "ya_nabi_salam.mp3"; break;
                default: return null;
            }
            try {
                File folder = new File(context.getFilesDir(), "notification_sounds"); if (!folder.exists()) folder.mkdirs();
                File target = new File(folder, "bundled_" + fileName);
                if (!target.exists()) try (InputStream input = context.getAssets().open("notification_sounds/" + fileName); FileOutputStream output = new FileOutputStream(target)) { byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count); }
                return Uri.fromFile(target);
            } catch (Exception ignored) { return null; }
        }
        return selectedSystemSound(context, channelType, soundMode);
    }    @Override public void onReceive(Context context, Intent intent) {
        boolean canPostNotifications = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
        String title = intent.getStringExtra("title"), message = intent.getStringExtra("message"), soundMode = intent.getStringExtra("soundMode"), channelType = intent.getStringExtra("channelType");
        if (title == null) title = context.getString(R.string.app_name);
        if (message == null) message = "Namaz vakti";
        Uri sound = null;
        String custom = intent.getStringExtra("soundUri");
        sound = resolveSound(context, channelType, soundMode, custom);
        String channelId = "prayer_notice_v3_" + (soundMode == null ? "notification" : soundMode) + "_" + (channelType == null ? "media" : channelType) + "_" + (custom == null ? 0 : custom.hashCode());
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            String channelName = "Namaz vakti - " + ("alarm".equals(channelType) ? "Alarm" : ("ringtone".equals(channelType) ? "Zil" : "Medya")); NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, channelId) : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(message).setWhen(System.currentTimeMillis()).setOnlyAlertOnce(false).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER);
boolean playbackStarted = false;
        if (sound != null) { try { Intent playback = new Intent(context, PrayerAudioService.class); playback.putExtra("play_prayer_sound_uri", sound.toString()); playback.putExtra("play_prayer_sound_channel", channelType); playback.putExtra("play_prayer_sound_seconds", intent.getIntExtra("durationSeconds", 5)); if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(playback); else context.startService(playback); playbackStarted = true; } catch (Exception ignored) {} }
        if (Build.VERSION.SDK_INT >= 26) {
            try { for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) { String activeChannel = active.getNotification().getChannelId(); if (activeChannel != null && activeChannel.startsWith("prayer_notice_v3_")) manager.cancel(active.getTag(), active.getId()); } } catch (Exception ignored) {}
        }
        int notificationId = 1702;
        if (canPostNotifications) {
            if (sound != null && !playbackStarted) {
                String fallbackChannelId = "prayer_notice_audio_v4_" + (channelType == null ? "media" : channelType) + "_" + (soundMode == null ? "notification" : soundMode) + "_" + sound.toString().hashCode();
                int stream = "alarm".equals(channelType) ? android.media.AudioManager.STREAM_ALARM : ("ringtone".equals(channelType) ? android.media.AudioManager.STREAM_RING : android.media.AudioManager.STREAM_MUSIC);
                AudioAttributes audioAttributes = new AudioAttributes.Builder().setLegacyStreamType(stream).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
                if (Build.VERSION.SDK_INT >= 26) {
                    String fallbackName = "Namaz bildirimi - " + ("alarm".equals(channelType) ? "Alarm" : ("ringtone".equals(channelType) ? "Zil" : "Medya"));
                    NotificationChannel fallbackChannel = new NotificationChannel(fallbackChannelId, fallbackName, NotificationManager.IMPORTANCE_HIGH);
                    fallbackChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                    fallbackChannel.setSound(sound, audioAttributes);
                    manager.createNotificationChannel(fallbackChannel);
                    builder = new Notification.Builder(context, fallbackChannelId);
                } else builder.setSound(sound);
                builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(message).setWhen(System.currentTimeMillis()).setOnlyAlertOnce(false).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER);
            }
            manager.notify(notificationId, builder.build());
        }

    }
}