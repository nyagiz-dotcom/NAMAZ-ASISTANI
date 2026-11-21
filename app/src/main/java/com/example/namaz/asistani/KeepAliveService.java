package com.example.namaz.asistani;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.widget.RemoteViews;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class KeepAliveService extends Service {
    public static final String ACTION_UPDATE_STATUS = "com.example.namaz.asistani.UPDATE_FOREGROUND_STATUS";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_END_TIME = "endTime";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "app_background_v2";
    private static final String PREFS = "prayer_service_cache";
    private static final String KEY_DISTRICT = "district_id";
    private static final String KEY_CITY_NAME = "city_name";
    private static final String KEY_DISTRICT_NAME = "district_name";
    private static final String KEY_TIMES = "times_json";
    private PowerManager.WakeLock wakeLock;
    private MediaPlayer prayerPlayback;
    private android.os.Handler handler;
    private boolean refreshRunning = false;
    private boolean foregroundStarted = false;
    private long lastRefreshAt = 0L;
    private long lastPlannedAt = 0L;
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            renderFromCache();
            long now = System.currentTimeMillis();
            if (!refreshRunning && (now - lastRefreshAt > 6L * 60L * 60L * 1000L)) refreshTimesAsync();
            if (now - lastPlannedAt > 30L * 60L * 1000L) { lastPlannedAt = now; renderFromCache(); }
            if (handler != null) handler.postDelayed(this, 60000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        // ONEMLI: startForeground() onCreate icinde ILK is olmali, yoksa
        // ForegroundServiceDidNotStartInTimeException (ANR) alinir.
        ensureChannel();
        publishBootNotification();
        handler = new android.os.Handler(getMainLooper());
        renderFromCache();
        if (!hasCachedTimes()) refreshTimesAsync();
        handler.postDelayed(tickRunnable, 60000L);
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":background");
        wakeLock.acquire();
    }

    private String currentLocationLabel() { android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE); String city = p.getString(KEY_CITY_NAME, ""); String district = p.getString(KEY_DISTRICT_NAME, ""); if (city == null) city = ""; if (district == null) district = ""; if (city.isEmpty()) return district.isEmpty() ? "Konum seçilmedi" : district; if (district.isEmpty() || district.equalsIgnoreCase(city)) return city; return city + ", " + district; }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Sonraki namaz vakti", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String title, String message, long endTimeMillis) {
        return buildNotification(title, message, endTimeMillis, null, null, null);
    }

    // grid: 6 vakit saati (Imsak..Yatsi), kerahat: uyarı metni (yoksa null)
    private Notification buildNotification(String title, String message, long endTimeMillis, String[] grid, String kerahat, String kerahatLeft) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        String safeTitle = title == null || title.isEmpty() ? "Sonraki vakit bekleniyor" : title;
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(safeTitle)
            .setSubText(currentLocationLabel())
            .setContentText(message == null ? "Kalan" : message)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false);
        RemoteViews statusView = new RemoteViews(getPackageName(), R.layout.notification_prayer_status);
        statusView.setTextViewText(R.id.notification_prayer_title, safeTitle);
        statusView.setTextViewText(R.id.notification_prayer_location, currentLocationLabel());
                Intent refreshLocationIntent = new Intent(this, KeepAliveService.class); refreshLocationIntent.setAction(getPackageName() + ".REFRESH_NOTIFICATION_LOCATION_SELF"); refreshLocationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); refreshLocationIntent.putExtra("refresh_location_from_notification", true);
        android.app.PendingIntent refreshLocationPending = android.app.PendingIntent.getService(this, 1017, refreshLocationIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        statusView.setOnClickPendingIntent(R.id.notification_prayer_location, refreshLocationPending);
        if (endTimeMillis > System.currentTimeMillis() && Build.VERSION.SDK_INT >= 24) {
            long countdownBase = SystemClock.elapsedRealtime() + (endTimeMillis - System.currentTimeMillis());
            statusView.setChronometer(R.id.notification_prayer_countdown, countdownBase, "Kalan %s", true);
            statusView.setChronometerCountDown(R.id.notification_prayer_countdown, true);
        } else {
            statusView.setTextViewText(R.id.notification_prayer_countdown, message == null || message.isEmpty() ? "Bekleniyor" : message);
        }
        // Bildirimdeki iki yazı aynı font, aynı kalınlık ve aynı boyutta olsun (layout xml ile birlikte).
        statusView.setTextViewTextSize(R.id.notification_prayer_title, android.util.TypedValue.COMPLEX_UNIT_SP, 19f);
        statusView.setTextViewTextSize(R.id.notification_prayer_countdown, android.util.TypedValue.COMPLEX_UNIT_SP, 19f);
        boolean kerahatActive = kerahat != null && kerahat.length() > 0;
        int liveTimeColor = kerahatActive ? 0xFFFF1744 : 0xFF111827;
        statusView.setTextColor(R.id.notification_prayer_title, liveTimeColor);
        statusView.setTextColor(R.id.notification_prayer_countdown, liveTimeColor);
        statusView.setViewVisibility(R.id.notification_kerahat_warning, android.view.View.GONE);
        renderTimesGrid(statusView, grid);
        builder.setCustomContentView(statusView).setCustomBigContentView(statusView);
        builder.setStyle(new Notification.BigTextStyle().bigText(safeTitle));
        builder.setPriority(Notification.PRIORITY_DEFAULT);
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            android.app.PendingIntent contentIntent = android.app.PendingIntent.getActivity(this, 1001, launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(contentIntent);
        }

        return builder.build();
    }
    // İkinci ve üçüncü satır: satır başına 3 vakit; her hücre "İsim" + alt satırda saati.
    // Böylece saat, kendi isminin tam altında kalır (İmsak altında 06:21 ... Yatsı altında 19:13).
    private void renderTimesGrid(RemoteViews view, String[] grid) {
        if (grid == null || grid.length < 6) {
            view.setViewVisibility(R.id.notification_prayer_grid, android.view.View.GONE);
            return;
        }
        String[] names = new String[] { "İmsak", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı" };
        int[] nameIds = new int[] { R.id.notification_block_1_name, R.id.notification_block_2_name, R.id.notification_block_3_name, R.id.notification_block_4_name, R.id.notification_block_5_name, R.id.notification_block_6_name };
        int[] valueIds = new int[] { R.id.notification_block_1_value, R.id.notification_block_2_value, R.id.notification_block_3_value, R.id.notification_block_4_value, R.id.notification_block_5_value, R.id.notification_block_6_value };
        for (int i = 0; i < 6; i++) {
            String tm = grid[i] == null || grid[i].length() == 0 ? "--:--" : grid[i];
            view.setTextViewText(nameIds[i], names[i]);
            view.setTextViewText(valueIds[i], tm);
        }
        view.setViewVisibility(R.id.notification_prayer_grid, android.view.View.VISIBLE);
    }

    // Metni ortalar; her hücre aynı karakter genişliğinde olduğu için satırlar alt alta tam hizalı görünür.
    private String padCenter(String text, int width) {
        String value = text == null ? "" : text;
        if (value.length() >= width) return value;
        int total = width - value.length();
        int left = total / 2;
        int right = total - left;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < left; i++) sb.append(' ');
        sb.append(value);
        for (int i = 0; i < right; i++) sb.append(' ');
        return sb.toString();
    }

    private void showForegroundNotification(String title, String message, long endTimeMillis) {
        Notification notification = buildNotification(title, message, endTimeMillis);
        startForegroundSafely(notification);
    }

    private void showForegroundNotificationGrid(String title, String message, long endTimeMillis, String[] grid, String kerahat, String kerahatLeft) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("last_status_title", title == null ? "" : title).putString("last_status_message", message == null ? "" : message).putLong("last_status_end", endTimeMillis).apply();
        Notification notification = buildNotification(title, message, endTimeMillis, grid, kerahat, kerahatLeft);
        startForegroundSafely(notification);
    }

    private void playPrayerAudio(String soundUri, String channelType, int durationSeconds) {
        if (soundUri == null || soundUri.isEmpty()) return;
        try {
            if (prayerPlayback != null) { try { prayerPlayback.stop(); } catch (Exception ignored) {} try { prayerPlayback.release(); } catch (Exception ignored) {} prayerPlayback = null; }
            MediaPlayer player = new MediaPlayer(); prayerPlayback = player;
            int usage = "alarm".equals(channelType) ? AudioAttributes.USAGE_ALARM : ("ringtone".equals(channelType) ? AudioAttributes.USAGE_NOTIFICATION_RINGTONE : AudioAttributes.USAGE_MEDIA);
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, Uri.parse(soundUri));
            final int[] remaining = { Math.max(1, Math.min(180, durationSeconds)) };
            player.setOnCompletionListener(mp -> { remaining[0]--; if (remaining[0] > 0) { try { mp.seekTo(0); mp.start(); } catch (Exception e) { try { mp.release(); } catch (Exception ignored) {} if (prayerPlayback == mp) prayerPlayback = null; } } else { try { mp.release(); } catch (Exception ignored) {} if (prayerPlayback == mp) prayerPlayback = null; } });
            player.setOnErrorListener((mp, what, extra) -> { try { mp.release(); } catch (Exception ignored) {} if (prayerPlayback == mp) prayerPlayback = null; return true; });
            player.setOnPreparedListener(MediaPlayer::start);
            player.prepareAsync();
        } catch (Exception e) { if (prayerPlayback != null) { try { prayerPlayback.release(); } catch (Exception ignored) {} prayerPlayback = null; } }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("play_prayer_sound_uri")) playPrayerAudio(intent.getStringExtra("play_prayer_sound_uri"), intent.getStringExtra("play_prayer_sound_channel"), intent.getIntExtra("play_prayer_sound_seconds", 5));
        if (intent != null && intent.hasExtra("kerahat_sync_active")) {
            boolean active = intent.getBooleanExtra("kerahat_sync_active", false);
            long end = intent.getLongExtra("kerahat_sync_end", 0L);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("kerahat_sync_active", active).putLong("kerahat_sync_end", end).putLong("kerahat_sync_at", System.currentTimeMillis()).apply();
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String savedTitle = prefs.getString("last_status_title", "");
            if (savedTitle.length() > 0) showForegroundNotificationGrid(savedTitle, prefs.getString("last_status_message", "Kalan süre"), prefs.getLong("last_status_end", 0L), gridFromCache(), kerahatFromCache(), kerahatLeftFromCache());
            else renderFromCache();
        }
        if (intent != null && intent.hasExtra(EXTRA_TITLE)) {
            // JS köprüsünden gelen başlık/mesaj; vakit ızgarası ve kerahat uyarısı önbellekten eklenir.
            String t = intent.getStringExtra(EXTRA_TITLE);
            String m = intent.getStringExtra(EXTRA_MESSAGE);
            long endMs = intent.getLongExtra(EXTRA_END_TIME, 0L);
            if (m != null && m.length() > 40) m = m.substring(0, 40);
            showForegroundNotificationGrid(t, m, endMs, gridFromCache(), kerahatFromCache(), kerahatLeftFromCache());
        }
        if (intent != null && intent.getStringExtra("district") != null && intent.getStringExtra("district").length() > 0) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_DISTRICT, intent.getStringExtra("district")).apply();
            refreshTimesAsync();
        }
        if (intent != null && intent.getAction() != null && intent.getAction().endsWith(".REFRESH_NOTIFICATION_LOCATION_SELF")) {
            android.content.SharedPreferences spSelf = getSharedPreferences(PREFS, MODE_PRIVATE);
            String ttSelf = spSelf.getString("last_status_title", "");
            if (ttSelf != null && ttSelf.length() > 0) showForegroundNotificationGrid(ttSelf, spSelf.getString("last_status_message", "Kalan"), spSelf.getLong("last_status_end", 0L), gridFromCache(), kerahatFromCache(), kerahatLeftFromCache());
            else renderFromCache();
        }
        if (intent != null && intent.hasExtra("refresh_location_display")) { android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE); String savedTitle = p.getString("last_status_title", ""); if (savedTitle.length() > 0) showForegroundNotificationGrid(savedTitle, p.getString("last_status_message", "Kalan süre"), p.getLong("last_status_end", 0L), gridFromCache(), kerahatFromCache(), kerahatLeftFromCache()); else renderFromCache(); }
        if (!hasCachedTimes()) refreshTimesAsync();
        // Uygulama kapansa veya sistem servisi öldürse bile bildirim ve arka plan devam eder.
        return START_STICKY;
    }

    private boolean hasCachedTimes() {
        String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TIMES, "");
        return json != null && json.length() > 10;
    }

    private String districtId() {
        String id = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DISTRICT, "");
        return id == null || id.length() == 0 ? "9541" : id;
    }

    private JSONObject todayTimes() {
        try {
            String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TIMES, "");
            if (json == null || json.length() < 10) return null;
            JSONArray arr = new JSONArray(json);
            String today = new java.text.SimpleDateFormat("dd.MM.yyyy", new Locale("tr")).format(new java.util.Date());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && today.equals(o.optString("MiladiTarihKisa"))) return o;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String[] gridFromCache() {
        JSONObject t = todayTimes();
        if (t == null) return null;
        String[] keys = new String[] { "Imsak", "Gunes", "Ogle", "Ikindi", "Aksam", "Yatsi" };
        String[] g = new String[6];
        for (int i = 0; i < keys.length; i++) g[i] = t.optString(keys[i], "");
        return g;
    }

    private String kerahatFromCache() {
        return kerahatEndFromCache() > System.currentTimeMillis() ? "KERAHAT VAKTİ" : null;
    }

    private String kerahatLeftFromCache() {
        long end = kerahatEndFromCache();
        if (end <= System.currentTimeMillis()) return null;
        return Math.max(1L, (end - System.currentTimeMillis()) / 60000L) + " dk kaldı";
    }

    private long kerahatEndFromCache() {
        android.content.SharedPreferences syncPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long syncAt = syncPrefs.getLong("kerahat_sync_at", 0L);
        if (syncAt > 0L && System.currentTimeMillis() - syncAt <= 120000L) {
            if (!syncPrefs.getBoolean("kerahat_sync_active", false)) return -1L;
            long syncedEnd = syncPrefs.getLong("kerahat_sync_end", 0L);
            return syncedEnd > System.currentTimeMillis() ? syncedEnd : -1L;
        }
        JSONObject t = todayTimes();
        if (t == null) return -1L;
        long nowMs = System.currentTimeMillis();
        long gunesMs = clockMillis(t.optString("Gunes", ""), nowMs);
        long ogleMs = clockMillis(t.optString("Ogle", ""), nowMs);
        long aksamMs = clockMillis(t.optString("Aksam", ""), nowMs);
        if (gunesMs > 0 && nowMs >= gunesMs && nowMs < gunesMs + 2700000L) return gunesMs + 2700000L;
        if (ogleMs > 0 && nowMs >= ogleMs - 2700000L && nowMs < ogleMs) return ogleMs;
        if (aksamMs > 0 && nowMs >= aksamMs - 2700000L && nowMs < aksamMs) return aksamMs;
        return -1L;
    }

    private long clockMillis(String clock, long fallbackBase) {
        try {
            if (clock == null || clock.length() < 4) return -1L;
            String[] hm = clock.split(":");
            Calendar c = Calendar.getInstance();
            if (fallbackBase > 0) c.setTimeInMillis(fallbackBase);
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0].trim()));
            c.set(Calendar.MINUTE, Integer.parseInt(hm[1].trim()));
            c.set(Calendar.SECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return -1L;
        }
    }

    private void refreshTimesAsync() {
        if (refreshRunning) return;
        refreshRunning = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    URL url = new URL("https://ezanvakti.emushaf.net/vakitler/" + districtId());
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("Accept", "application/json");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    conn.disconnect();
                    String body = sb.toString();
                    if (body.length() > 10) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TIMES, body).apply();
                        lastRefreshAt = System.currentTimeMillis();
                    }
                } catch (Exception ignored) {
                } finally {
                    refreshRunning = false;
                    if (handler != null) handler.post(new Runnable() { @Override public void run() { renderFromCache(); } });
                }
            }
        }).start();
    }

    // Önbellekteki vakitlerden sonraki vakti bulur ve kalıcı bildirimi günceller.
    // Servis baslar baslamaz gosterilecek yedek bildirim (5 saniye kuralini karsilar)
    private android.app.Notification buildBootNotification() {
        String titleTmp = "Namaz Asistanı";
        String label = currentLocationLabel();
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(this, CHANNEL_ID) : new android.app.Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
         .setContentTitle(titleTmp)
         .setContentText("Vakit bilgileri hazırlanıyor")
         .setSubText(label)
         .setOngoing(true)
         .setShowWhen(false)
         .setOnlyAlertOnce(true)
         .setCategory(android.app.Notification.CATEGORY_SERVICE);
        return b.build();
    }

    // Tek noktadan startForeground: SDK 34 tipi ve her turlu hatayi guvenle ele alir
    private void startForegroundSafely(android.app.Notification n) {
        if (n == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                try { startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); foregroundStarted = true; return; } catch (Throwable ignored) { }
            }
            startForeground(NOTIFICATION_ID, n);
            foregroundStarted = true;
        } catch (Throwable ignored) { }
    }

    private void publishBootNotification() {
        try {
            startForegroundSafely(buildBootNotification());
        } catch (Throwable ignored) { }
    }

    private void renderFromCache() {
        try {
            String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TIMES, "");
            if (json == null || json.length() < 10) { showForegroundNotificationGrid("Vakitler", "", 0L, new String[] { "", "", "", "", "", "" }, "", ""); return; }
            JSONArray arr = new JSONArray(json);
            Calendar now = Calendar.getInstance();
            String today = new java.text.SimpleDateFormat("dd.MM.yyyy", new Locale("tr")).format(now.getTime());
            JSONObject todayObj = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && today.equals(o.optString("MiladiTarihKisa"))) { todayObj = o; break; }
            }
            if (todayObj == null) { showForegroundNotificationGrid("Vakitler", "", 0L, new String[] { "", "", "", "", "", "" }, "", ""); return; }
            String[] keys = new String[] { "Imsak", "Gunes", "Ogle", "Ikindi", "Aksam", "Yatsi" };
            String[] names = new String[] { "İmsak", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı" };
            long bestTime = Long.MAX_VALUE;
            String bestName = null;
            String bestClock = null;
            for (int i = 0; i < keys.length; i++) {
                String clock = todayObj.optString(keys[i], "");
                if (clock.length() < 4) continue;
                String[] hm = clock.split(":");
                if (hm.length < 2) continue;
                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0].trim()));
                c.set(Calendar.MINUTE, Integer.parseInt(hm[1].trim()));
                c.set(Calendar.SECOND, 0);
                long t = c.getTimeInMillis();
                if (t > System.currentTimeMillis() && t < bestTime) { bestTime = t; bestName = names[i]; bestClock = clock; }
            }
            if (bestName == null) { String[] gg = gridFromCache(); if (gg == null) gg = new String[] { "", "", "", "", "", "" }; showForegroundNotificationGrid("Bugünün vakitleri tamamlandı", "", 0L, gg, kerahatFromCache(), kerahatLeftFromCache()); return; }
            String[] grid = new String[6];
            for (int i = 0; i < keys.length; i++) grid[i] = todayObj.optString(keys[i], "");
            String kerahat = kerahatFromCache();
            String kerahatLeft = kerahatLeftFromCache();
            showForegroundNotificationGrid(bestName + " " + bestClock, "Kalan", bestTime, grid, kerahat, kerahatLeft);
        } catch (Exception ignored) {
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        try {
            Intent restart = new Intent(getApplicationContext(), KeepAliveService.class);
            android.app.PendingIntent pending = android.app.PendingIntent.getService(getApplicationContext(), 4242, restart, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.AlarmManager alarms = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarms != null) alarms.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1000, pending);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        if (prayerPlayback != null) { try { prayerPlayback.release(); } catch (Exception ignored) {} prayerPlayback = null; }
        if (handler != null) { handler.removeCallbacksAndMessages(null); handler = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try {
            Intent restart = new Intent(getApplicationContext(), KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) getApplicationContext().startForegroundService(restart); else getApplicationContext().startService(restart);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}