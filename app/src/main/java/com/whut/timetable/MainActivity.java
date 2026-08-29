package com.whut.timetable;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** App 主界面：本地课表、提醒、桌面小组件和更新入口。 */
public class MainActivity extends Activity {
    private static final int REQUEST_IMPORT = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;
    static final String IMPORT_FILE_NAME = "latest_import.json";
    static final String NATIVE_SCHEDULE_FILE_NAME = "native_schedule.json";

    private WebView webView;
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars(false);
        UpdateSchedule.scheduleDaily(this);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setDatabaseEnabled(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);

        WebView.setWebContentsDebuggingEnabled(false);
        webView.addJavascriptInterface(new AppBridge(), "WhutBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                checkUpdate(false);
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void configureSystemBars(boolean dark) {
        if (dark) {
            getWindow().setStatusBarColor(Color.rgb(17, 22, 34));
            getWindow().setNavigationBarColor(Color.rgb(17, 22, 34));
            getWindow().getDecorView().setSystemUiVisibility(0);
        } else {
            getWindow().setStatusBarColor(Color.rgb(247, 249, 254));
            getWindow().setNavigationBarColor(Color.WHITE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            );
        }
    }

    private final class AppBridge {
        @JavascriptInterface
        public void openWhutLogin() {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, ImportActivity.class);
                startActivityForResult(intent, REQUEST_IMPORT);
            });
        }

        @JavascriptInterface
        public String getAppVersion() {
            return UpdateManager.currentVersion(MainActivity.this);
        }

        @JavascriptInterface
        public void saveNativeSchedule(String json) {
            try {
                File file = new File(getFilesDir(), NATIVE_SCHEDULE_FILE_NAME);
                try (FileOutputStream stream = new FileOutputStream(file, false)) {
                    stream.write(json.getBytes(StandardCharsets.UTF_8));
                }
                ReminderScheduler.reschedule(MainActivity.this, json);
                TodayWidgetProvider.updateAll(MainActivity.this);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void clearNativeSchedule() {
            try {
                File file = new File(getFilesDir(), NATIVE_SCHEDULE_FILE_NAME);
                if (file.exists()) file.delete();
                ReminderScheduler.cancelExisting(MainActivity.this);
                TodayWidgetProvider.updateAll(MainActivity.this);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void configureReminders(boolean enabled, int minutesBefore) {
            runOnUiThread(() -> requestNotificationPermissionIfNeeded());
            String json = readNativeScheduleQuietly();
            ReminderScheduler.configure(MainActivity.this, enabled, minutesBefore,
                    json == null ? "{}" : json);
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(() -> requestNotificationPermissionIfNeeded());
        }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(() -> MainActivity.this.checkUpdate(true));
        }

        @JavascriptInterface
        public void installUpdate(String apkUrl, String version) {
            runOnUiThread(() -> UpdateManager.downloadAndInstall(MainActivity.this, apkUrl, version));
        }

        @JavascriptInterface
        public void setDarkMode(boolean dark) {
            runOnUiThread(() -> configureSystemBars(dark));
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void checkUpdate(boolean force) {
        UpdateManager.check(this, UpdateManager.currentVersion(this), force, (success, result) ->
                runOnUiThread(() -> deliverUpdateResult(result))
        );
    }

    private void deliverUpdateResult(JSONObject result) {
        if (!pageReady || webView == null) return;
        String quoted = JSONObject.quote(result.toString());
        webView.evaluateJavascript(
                "window.WhutSchedule && window.WhutSchedule.receiveUpdateResult(" + quoted + ");",
                null
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return;

        File importFile = new File(getFilesDir(), IMPORT_FILE_NAME);
        try {
            String json = readUtf8(importFile);
            String quoted = JSONObject.quote(json);
            webView.evaluateJavascript(
                    "window.WhutSchedule && window.WhutSchedule.receiveNativeImport(" + quoted + ");",
                    null
            );
        } catch (IOException e) {
            Toast.makeText(this, "读取同步结果失败，请重新同步", Toast.LENGTH_LONG).show();
        }
    }

    private String readNativeScheduleQuietly() {
        try {
            File file = new File(getFilesDir(), NATIVE_SCHEDULE_FILE_NAME);
            return file.exists() ? readUtf8(file) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readUtf8(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("WhutBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
