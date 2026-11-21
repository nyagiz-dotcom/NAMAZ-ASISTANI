package com.example.namaz.asistani;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import android.util.Base64;

public class MainActivity extends AppCompatActivity {
    private boolean pendingNotificationLocationRefresh = false;
    private void refreshLocationOnPage(WebView view, String url) { if (!pendingNotificationLocationRefresh || url == null || !url.endsWith("/index.html")) return; pendingNotificationLocationRefresh = false; view.postDelayed(() -> view.evaluateJavascript("document.getElementById('currentLocationBtn').click()", null), 350); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (intent != null && intent.getBooleanExtra("refresh_location_from_notification", false)) { pendingNotificationLocationRefresh = true; if (myWebView != null) myWebView.loadUrl("file:///android_asset/index.html"); } }
    private WebView myWebView;
    private RelativeLayout rootLayout;
    private View splashOverlay;
    private ValueCallback<Uri[]> uploadMessage;
    private String pendingQuranUrl = null;
    private int quranRetries = 0;
    private boolean quranOnline = false;
    private void showQuranBlockedPage(WebView view, String failedUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>");
        sb.append("body{background:#0f172a;color:#e2e8f0;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center;padding:24px}").append("h2{margin:10px 0 6px}p{font-size:14px;color:#94a3b8;margin:0 0 6px}button{margin-top:12px;padding:13px 24px;border:0;border-radius:16px;background:#0e7490;color:#fff;font:700 15px sans-serif}").append("</style></head><body>");
        sb.append("<h2>Kuran sayfası açılamadı</h2>");
        sb.append("<p>Diyanet sitesine bağlanılamadı. İnternet bağlantınızı kontrol edip tekrar deneyin.</p>");
        sb.append("<button onclick=\"AndroidBridge.retryQuranPage()\">Tekrar Dene</button>");
        sb.append("<button onclick=\"AndroidBridge.closeQuranPage()\">Geri Dön</button>");
        sb.append("</body></html>");
        view.loadDataWithBaseURL("file:///android_asset/", sb.toString(), "text/html", "UTF-8", null);
        pendingQuranUrl = null;
    }
    private android.os.Handler keepAliveHandler = null;
    private boolean appInForeground = true;
    private final Runnable keepAliveRunnable = new Runnable() { @Override public void run() { try { keepServiceAlive(); } catch (Exception ignored) {} if (keepAliveHandler != null) keepAliveHandler.postDelayed(this, appInForeground ? 60000 : 900000); } };
    private android.webkit.GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private void requestInitialAppPermissions() { android.content.SharedPreferences p = getSharedPreferences("first_launch_permissions", MODE_PRIVATE); if (p.getBoolean("asked", false)) return; p.edit().putBoolean("asked", true).apply();
        if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != android.content.pm.PackageManager.PERMISSION_GRANTED && checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[] { "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION" }, 2104); return; }
        requestBackgroundRunPermission();
    }
    private void requestBackgroundRunPermission() { if (Build.VERSION.SDK_INT >= 23) { android.os.PowerManager pm = (android.os.PowerManager)getSystemService(POWER_SERVICE); if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) { try { startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {} } } }
    private Intent pendingBackgroundIntent;
    private boolean notificationPermissionRequested = false;
    private void ensureBackgroundServiceStarted(Intent updateIntent) {
        if (updateIntent != null) pendingBackgroundIntent = updateIntent;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) { if (!notificationPermissionRequested) { notificationPermissionRequested = true; runOnUiThread(() -> requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 2102)); } }
        Intent serviceIntent = pendingBackgroundIntent != null ? pendingBackgroundIntent : new Intent(this, KeepAliveService.class);
        pendingBackgroundIntent = null;
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent); else startService(serviceIntent); } catch (Exception e) { e.printStackTrace(); }
    }
    private void keepServiceAlive() { try { ensureBackgroundServiceStarted(null); } catch (Exception ignored) {} }
    private final static int FILE_CHOOSER_RESULT_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingNotificationLocationRefresh = getIntent() != null && getIntent().getBooleanExtra("refresh_location_from_notification", false);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { WindowManager.LayoutParams cutoutParams = getWindow().getAttributes(); cutoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES; getWindow().setAttributes(cutoutParams); }
        rootLayout = new RelativeLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        myWebView = new WebView(this);
        myWebView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebSettings ws = myWebView.getSettings();
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setJavaScriptCanOpenWindowsAutomatically(true); ws.setSupportMultipleWindows(false);
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setGeolocationEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        myWebView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { super.onPageStarted(view, url, favicon); if (url != null && url.startsWith("https://kuran.diyanet.gov.tr/")) quranOnline = false; }
            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) { super.onReceivedError(view, request, error); if (request != null && request.isForMainFrame() && pendingQuranUrl != null) { quranOnline = false; if (quranRetries >= 3) showQuranBlockedPage(view, request.getUrl() == null ? null : request.getUrl().toString()); } }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("https".equals(scheme) && "kuran.diyanet.gov.tr".equalsIgnoreCase(uri.getHost())) return false;
                if ("file".equals(scheme) && uri.getPath() != null && uri.getPath().startsWith("/android_asset/")) return false;
                if ("https".equals(scheme) || "http".equals(scheme)) { try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {} }
                return true;
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) { super.doUpdateVisitedHistory(view, url, isReload); if (url != null && url.startsWith("https://kuran.diyanet.gov.tr/")) getSharedPreferences("quran_page_state", MODE_PRIVATE).edit().putString("last_url", url).apply(); }
            @Override public void onPageFinished(WebView view, String url) { if (url != null && url.startsWith("https://kuran.diyanet.gov.tr/")) { quranOnline = true; pendingQuranUrl = null; quranRetries = 0; } refreshLocationOnPage(view, url); super.onPageFinished(view, url); }
        });
        myWebView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                if (url != null && url.startsWith("blob:")) {
                    return;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "İndirme başlatılamadı", Toast.LENGTH_SHORT).show();
                }
            }
        });
        final ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        RelativeLayout.LayoutParams pbParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        pbParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        progressBar.setLayoutParams(pbParams);
        progressBar.setMax(100);
        myWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (false) {
                    if (newProgress == 100) progressBar.setVisibility(View.GONE);
                    else {
                        if (progressBar.getVisibility() == View.GONE) progressBar.setVisibility(View.VISIBLE);
                        progressBar.setProgress(newProgress);
                    }
                }
                super.onProgressChanged(view, newProgress);
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) { boolean allowed = checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == android.content.pm.PackageManager.PERMISSION_GRANTED || checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") == android.content.pm.PackageManager.PERMISSION_GRANTED; if (allowed) callback.invoke(origin, true, false); else { pendingGeoOrigin = origin; pendingGeoCallback = callback; requestPermissions(new String[] { "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION" }, 2101); } }
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                }
                uploadMessage = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    uploadMessage = null;
                    return false;
                }
                return true;
            }
        });
        myWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface public void setPrayerDistrict(String districtId, String cityName, String districtName) { try { if (districtId == null || districtId.length() == 0) return; android.content.SharedPreferences prefs = getSharedPreferences("prayer_service_cache", MODE_PRIVATE); prefs.edit().putString("district_id", districtId).putString("city_name", cityName == null ? "" : cityName).putString("district_name", districtName == null ? "" : districtName).remove("times_json").apply(); Intent serviceIntent = new Intent(MainActivity.this, KeepAliveService.class); serviceIntent.putExtra("district", districtId); serviceIntent.putExtra("refresh_location_display", true); if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent); else startService(serviceIntent); } catch (Exception ignored) {} }
            @JavascriptInterface public boolean previewPrayerNotificationSound(String soundMode, String soundUri, String channelType, int seconds) { try { Uri selected = PrayerNotificationReceiver.resolveSound(MainActivity.this, channelType, soundMode, soundUri); if (selected == null) return false; Intent playback = new Intent(MainActivity.this, PrayerAudioService.class); playback.putExtra("play_prayer_sound_uri", selected.toString()); playback.putExtra("play_prayer_sound_channel", channelType); playback.putExtra("play_prayer_sound_seconds", Math.max(1, Math.min(30, seconds))); playback.putExtra("play_prayer_sound_preview", true); if (Build.VERSION.SDK_INT >= 26) startForegroundService(playback); else startService(playback); return true; } catch (Exception e) { return false; } }
            @JavascriptInterface public void stopPrayerNotificationPreview() { try { PrayerAudioService.stopPreview(); } catch (Exception ignored) {} }
            @JavascriptInterface public String savePrayerNotificationSound(String fileName, String base64Data) { try { String safeName = fileName == null ? "sound.bin" : fileName.replaceAll("[^A-Za-z0-9._-]", "_"); File folder = new File(getFilesDir(), "notification_sounds"); if (!folder.exists()) folder.mkdirs(); File target = new File(folder, safeName); try (FileOutputStream out = new FileOutputStream(target)) { out.write(Base64.decode(base64Data, Base64.DEFAULT)); } Uri uri = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".notificationfiles", target); grantUriPermission("com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); return uri.toString(); } catch (Exception e) { return ""; } }
            @JavascriptInterface public void cancelConfiguredPrayerNotifications() { android.content.SharedPreferences prefs = getSharedPreferences("configured_prayer_alarms", MODE_PRIVATE); java.util.Set<String> ids = prefs.getStringSet("ids", java.util.Collections.emptySet()); AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE); for (String raw : ids) { try { int id = Integer.parseInt(raw); PendingIntent pending = PendingIntent.getBroadcast(MainActivity.this, id, new Intent(MainActivity.this, PrayerNotificationReceiver.class), PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE); if (pending != null) { alarms.cancel(pending); pending.cancel(); } } catch (Exception ignored) {} } prefs.edit().clear().apply(); }
            @JavascriptInterface public void scheduleConfiguredNotification(String title, String message, long whenMillis, String soundMode, int durationSeconds, int intervalMinutes, String soundUri, String channelType) { try { if (whenMillis <= System.currentTimeMillis() || soundMode == null || soundMode.equals("off")) return; Intent intent = new Intent(MainActivity.this, PrayerNotificationReceiver.class); intent.putExtra("title", title); intent.putExtra("message", message); intent.putExtra("soundMode", soundMode); intent.putExtra("soundUri", soundUri); intent.putExtra("channelType", channelType); intent.putExtra("durationSeconds", Math.max(1, Math.min(30, durationSeconds))); intent.putExtra("repeatIntervalMinutes", Math.max(0, intervalMinutes)); int requestId = (title + "|" + whenMillis + "|" + soundMode).hashCode() & 0x7fffffff; intent.putExtra("notificationId", requestId); PendingIntent pending = PendingIntent.getBroadcast(MainActivity.this, requestId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); java.util.Set<String> ids = new java.util.HashSet<>(getSharedPreferences("configured_prayer_alarms", MODE_PRIVATE).getStringSet("ids", java.util.Collections.emptySet())); ids.add(String.valueOf(requestId)); getSharedPreferences("configured_prayer_alarms", MODE_PRIVATE).edit().putStringSet("ids", ids).apply(); AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE); if (Build.VERSION.SDK_INT >= 31 && alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending); else if (Build.VERSION.SDK_INT >= 23) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending); else alarms.set(AlarmManager.RTC_WAKEUP, whenMillis, pending); } catch (Exception ignored) {} }
            @JavascriptInterface
            public boolean hasNotificationPermission() { return Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED; }
            @JavascriptInterface
            public void requestNotificationPermission() { runOnUiThread(() -> { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 2102); }); }
            @JavascriptInterface
            public void showNotification(String title, String message) {
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 2102); return; }
                    String channelId = "user_notifications";
                    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(channelId, "Uygulama bildirimleri", NotificationManager.IMPORTANCE_DEFAULT));
                    Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(MainActivity.this, channelId) : new Notification.Builder(MainActivity.this);
                    builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title == null ? getString(R.string.app_name) : title).setContentText(message == null ? "" : message);
                    manager.notify((int)(System.currentTimeMillis() & 0x7fffffff), builder.build());
                });
            }
            @JavascriptInterface
            public boolean hasNotificationPolicyAccess() { return true; }
            @JavascriptInterface
            public void requestNotificationPolicyAccess() {  }
            @JavascriptInterface
            public void scheduleSilentMode(String windowKey, long startMillis, long endMillis) { if (startMillis <= 0 || endMillis <= 0) { cancelSilentAlarm(windowKey, true); cancelSilentAlarm(windowKey, false); scheduleSilentAlarm(windowKey, false, System.currentTimeMillis()); return; } scheduleSilentAlarm(windowKey, true, startMillis); scheduleSilentAlarm(windowKey, false, endMillis); }
            private void cancelSilentAlarm(String windowKey, boolean start) { Intent intent = new Intent(MainActivity.this, SilentModeReceiver.class); intent.setAction(start ? "start" : "end"); intent.putExtra("windowKey", windowKey); int requestId = 700000 + Math.abs(windowKey.hashCode() % 100000) * 2 + (start ? 0 : 1); PendingIntent pending = PendingIntent.getBroadcast(MainActivity.this, requestId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); ((AlarmManager) getSystemService(ALARM_SERVICE)).cancel(pending); pending.cancel(); }
            private void scheduleSilentAlarm(String windowKey, boolean start, long whenMillis) { Intent intent = new Intent(MainActivity.this, SilentModeReceiver.class); intent.setAction(start ? "start" : "end"); intent.putExtra("windowKey", windowKey); int requestId = 700000 + Math.abs(windowKey.hashCode() % 100000) * 2 + (start ? 0 : 1); PendingIntent pending = PendingIntent.getBroadcast(MainActivity.this, requestId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE); long trigger = Math.max(whenMillis, System.currentTimeMillis()); if (Build.VERSION.SDK_INT >= 31 && alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending); else if (Build.VERSION.SDK_INT >= 23) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending); else alarms.set(AlarmManager.RTC_WAKEUP, trigger, pending); }
            @JavascriptInterface
            public void cancelScheduledNotifications() { android.content.SharedPreferences prefs = getSharedPreferences("scheduled_prayer_alarms", MODE_PRIVATE); java.util.Set<String> ids = prefs.getStringSet("ids", java.util.Collections.emptySet()); AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE); for (String raw : ids) { try { int id = Integer.parseInt(raw); Intent alarmIntent = new Intent(MainActivity.this, NotificationReceiver.class); PendingIntent pending = PendingIntent.getBroadcast(MainActivity.this, id, alarmIntent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE); if (pending != null) { alarms.cancel(pending); pending.cancel(); } } catch (Exception ignored) {} } prefs.edit().clear().apply(); }
            @JavascriptInterface
            public void scheduleNotification(String title, String message, long whenMillis) {
                Intent intent = new Intent(MainActivity.this, NotificationReceiver.class); intent.putExtra("title", title); intent.putExtra("message", message);
                int requestId = (title + "|" + whenMillis).hashCode() & 0x7fffffff; PendingIntent pending = PendingIntent.getBroadcast(MainActivity.this, requestId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); android.content.SharedPreferences prefs = getSharedPreferences("scheduled_prayer_alarms", MODE_PRIVATE); java.util.Set<String> ids = new java.util.HashSet<>(prefs.getStringSet("ids", java.util.Collections.emptySet())); ids.add(String.valueOf(requestId)); prefs.edit().putStringSet("ids", ids).apply();
                AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE); long trigger = Math.max(whenMillis, System.currentTimeMillis());
                if (Build.VERSION.SDK_INT >= 31 && alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending); else if (Build.VERSION.SDK_INT >= 23) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending); else alarms.set(AlarmManager.RTC_WAKEUP, trigger, pending);
            }
            @JavascriptInterface
            public void updatePrayerStatus(String title, String message) {
                String statusTitle = title == null ? getString(R.string.app_name) : title;
                String statusText = message == null ? "" : message;
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 2102); return; }
                String statusChannel = "prayer_status"; NotificationManager statusManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 26) { NotificationChannel channel = new NotificationChannel(statusChannel, "Namaz vakitleri", NotificationManager.IMPORTANCE_LOW); channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); statusManager.createNotificationChannel(channel); }
                Notification.Builder statusBuilder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(MainActivity.this, statusChannel) : new Notification.Builder(MainActivity.this);
                statusBuilder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(statusTitle).setContentText(statusText).setStyle(new Notification.BigTextStyle().bigText(statusText)).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_REMINDER);
                statusManager.notify(1701, statusBuilder.build());
                getSharedPreferences("widget_prayer", MODE_PRIVATE).edit().putString("status", statusTitle + "\n" + statusText).apply();
                android.appwidget.AppWidgetManager widgetManager = android.appwidget.AppWidgetManager.getInstance(MainActivity.this);
                int[] widgetIds = widgetManager.getAppWidgetIds(new android.content.ComponentName(MainActivity.this, AppWidgetProvider.class));
                for (int widgetId : widgetIds) { android.widget.RemoteViews widgetViews = new android.widget.RemoteViews(getPackageName(), R.layout.widget_layout); widgetViews.setTextViewText(R.id.widget_title, statusTitle + "\n" + statusText); android.content.Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName()); if (launch != null) widgetViews.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(MainActivity.this, 1702, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)); widgetManager.updateAppWidget(widgetId, widgetViews); }
            }
            @JavascriptInterface
            public void openQuranPage() { runOnUiThread(() -> { String lastUrl = getSharedPreferences("quran_page_state", MODE_PRIVATE).getString("last_url", "https://kuran.diyanet.gov.tr/"); pendingQuranUrl = lastUrl; quranRetries = 0; tryQuranUrl(lastUrl); }); }
            private void tryQuranUrl(String url) { if (quranRetries++ > 3) { showQuranOfflineCard(); return; } myWebView.loadUrl(url); final String probe = url; myWebView.postDelayed(() -> { if (pendingQuranUrl != null && pendingQuranUrl.equals(probe) && !quranOnline) { if (quranRetries >= 3) showQuranOfflineCard(); else tryQuranUrl(probe); } }, 12000); }
            private void showQuranOfflineCard() { String html = "<html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>body{background:#0f172a;color:#e2e8f0;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center;padding:24px}h2{margin:10px 0 6px}p{font-size:14px;color:#94a3b8;margin:0 0 6px}button{margin-top:14px;padding:13px 24px;border:0;border-radius:16px;background:#0e7490;color:#fff;font:700 15px sans-serif}</style></head><body><div style=font-size:40px>KURAN</div><h2>Kur an sayfası açılamadı</h2><p>Diyanet sitesine bağlanılamadı.</p><p>İnternet bağlantınızı kontrol edip tekrar deneyin.</p><button onclick=AndroidBridge.retryQuranPage()>Tekrar Dene</button><br><button onclick=AndroidBridge.closeQuranPage()>Geri Dön</button></body></html>"; myWebView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null); pendingQuranUrl = null; }
            @JavascriptInterface public void retryQuranPage() { runOnUiThread(() -> { quranRetries = 0; String lastUrl = getSharedPreferences("quran_page_state", MODE_PRIVATE).getString("last_url", "https://kuran.diyanet.gov.tr/"); pendingQuranUrl = lastUrl; tryQuranUrl(lastUrl); }); }
            @JavascriptInterface public void closeQuranPage() { runOnUiThread(() -> myWebView.loadUrl("file:///android_asset/index.html")); }
            @JavascriptInterface
            public String reverseGeocode(double latitude, double longitude) {
                try { java.util.List<android.location.Address> addresses = new android.location.Geocoder(MainActivity.this, new java.util.Locale("tr", "TR")).getFromLocation(latitude, longitude, 1); if (addresses == null || addresses.isEmpty()) return ""; android.location.Address address = addresses.get(0); org.json.JSONObject result = new org.json.JSONObject(); result.put("country", address.getCountryName() == null ? "" : address.getCountryName()); result.put("countryCode", address.getCountryCode() == null ? "" : address.getCountryCode()); result.put("adminArea", address.getAdminArea() == null ? "" : address.getAdminArea()); result.put("subAdminArea", address.getSubAdminArea() == null ? "" : address.getSubAdminArea()); result.put("locality", address.getLocality() == null ? "" : address.getLocality()); result.put("subLocality", address.getSubLocality() == null ? "" : address.getSubLocality()); return result.toString(); } catch (Exception e) { return ""; }
            }
            @JavascriptInterface
            public void updateKerahatStatus(boolean active, long endTimeMillis) {
                Intent updateIntent = new Intent(MainActivity.this, KeepAliveService.class); updateIntent.putExtra("kerahat_sync_active", active); updateIntent.putExtra("kerahat_sync_end", endTimeMillis); ensureBackgroundServiceStarted(updateIntent);
            }
            public void updateForegroundPrayerStatus(String title, String message, long endTimeMillis) {
                Intent updateIntent = new Intent(MainActivity.this, KeepAliveService.class); updateIntent.setAction(KeepAliveService.ACTION_UPDATE_STATUS); updateIntent.putExtra(KeepAliveService.EXTRA_TITLE, title); updateIntent.putExtra(KeepAliveService.EXTRA_MESSAGE, message); updateIntent.putExtra(KeepAliveService.EXTRA_END_TIME, endTimeMillis);
                ensureBackgroundServiceStarted(updateIntent);
            }
            @JavascriptInterface
            public void saveFile(String fileName, String mimeType, String base64Data) {
                Uri createdUri = null;
                try {
                    String safeName = fileName == null ? "indirilen_dosya" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
                    if (Build.VERSION.SDK_INT < 29) {
                        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        if (!downloads.exists() && !downloads.mkdirs()) throw new Exception("İndirilenler klasörü oluşturulamadı");
                        File target = new File(downloads, safeName);
                        try (FileOutputStream legacy = new FileOutputStream(target)) { legacy.write(Base64.decode(base64Data, Base64.DEFAULT)); }
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Dosya İndirilenler klasörüne kaydedildi ✅", Toast.LENGTH_LONG).show()); return;
                    }
                    ContentResolver resolver = getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    createdUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (createdUri == null) throw new Exception("Dosya kaydı başlatılamadı");
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    try (OutputStream os = resolver.openOutputStream(createdUri)) {
                        if (os == null) throw new Exception("Dosya yazma akışı açılamadı");
                        os.write(bytes);
                    }
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Dosya İndirilenler klasörüne kaydedildi ✅", Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    if (createdUri != null) getContentResolver().delete(createdUri, null, null);
                    e.printStackTrace();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Kayıt hatası: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        }, "AndroidBridge");
        myWebView.loadUrl("file:///android_asset/index.html");
        rootLayout.addView(myWebView);
        rootLayout.addView(progressBar);
        setContentView(rootLayout);
        requestInitialAppPermissions();
        keepAliveHandler = new android.os.Handler(getMainLooper()); keepAliveHandler.post(keepAliveRunnable);
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[] { "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE" }, 2103);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (getWindow().getInsetsController() != null) {
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    @Override
    protected void onResume() { super.onResume(); appInForeground = true; try { ensureBackgroundServiceStarted(null); } catch (Exception ignored) {} }
    @Override
    protected void onPause() { super.onPause(); appInForeground = false; }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 2104) { requestBackgroundRunPermission(); }
        if (requestCode == 2101 && pendingGeoCallback != null) { boolean granted = false; for (int result : grantResults) if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) granted = true; pendingGeoCallback.invoke(pendingGeoOrigin, granted, false); pendingGeoCallback = null; pendingGeoOrigin = null; }
        if (requestCode == 2102) { notificationPermissionRequested = false; boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED; if (granted) ensureBackgroundServiceStarted(null); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (uploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && intent != null) {
                if (intent.getClipData() != null) {
                    int count = intent.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) results[i] = intent.getClipData().getItemAt(i).getUri();
                } else if (intent.getData() != null) {
                    results = new Uri[] { intent.getData() };
                }
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (myWebView != null && myWebView.canGoBack()) myWebView.goBack(); else super.onBackPressed();
    }
}