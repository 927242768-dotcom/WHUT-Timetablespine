package com.whut.timetable;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class UpdateManager {
    static final String REPO = "927242768-dotcom/WHUT-Timetablespine";
    static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String RELEASES_API_URL = "https://api.github.com/repos/" + REPO + "/releases?per_page=1";
    private static final String RELEASES_WEB_URL = "https://github.com/" + REPO + "/releases/latest";
    private static final String CHANNEL_ID = "app_updates";
    private static final String PREFS = "whut_timetable_prefs";
    private static final String KEY_LAST_CHECK = "last_update_check";

    interface Callback {
        void onResult(boolean success, JSONObject result);
    }

    private UpdateManager() {}

    static String currentVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.3.6";
        }
    }

    static void check(Context context, String currentVersion, boolean force, Callback callback) {
        long now = System.currentTimeMillis();
        long last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L);
        if (!force && now - last < 30L * 60L * 1000L) return;

        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                JSONObject release = fetchLatestRelease(force);
                String tag = release.optString("tag_name", "").replaceFirst("^[vV]", "");
                String notes = release.optString("body", "");
                String pageUrl = release.optString("html_url", "https://github.com/" + REPO + "/releases/latest");
                String apkUrl = "";
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "");
                            break;
                        }
                    }
                }
                boolean available = !tag.isBlank() && compareVersions(tag, currentVersion) > 0;
                result.put("success", true);
                result.put("available", available);
                result.put("version", tag);
                result.put("notes", notes);
                result.put("apkUrl", apkUrl);
                result.put("pageUrl", pageUrl);
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_CHECK, now).apply();
                if (available && !force) showUpdateNotification(context, tag);
                if (callback != null) callback.onResult(true, result);
            } catch (Exception e) {
                try {
                    result.put("success", false);
                    result.put("available", false);
                    result.put("message", "检查更新失败，请检查网络后重试");
                    result.put("detail", e.getMessage() == null ? "unknown" : e.getMessage());
                } catch (Exception ignored) {}
                if (callback != null) callback.onResult(false, result);
            }
        }).start();
    }

    private static JSONObject fetchLatestRelease(boolean force) throws Exception {
        String cacheBust = force ? ((API_URL.contains("?") ? "&" : "?") + "_=" + System.currentTimeMillis()) : "";
        Exception firstError = null;
        try {
            return readReleaseObject(API_URL + cacheBust);
        } catch (Exception e) {
            firstError = e;
        }

        try {
            String separator = RELEASES_API_URL.contains("?") ? "&" : "?";
            String url = RELEASES_API_URL + (force ? separator + "_=" + System.currentTimeMillis() : "");
            JSONArray releases = readJsonArray(url);
            if (releases.length() > 0 && releases.optJSONObject(0) != null) return releases.getJSONObject(0);
        } catch (Exception ignored) {
        }

        try {
            return releaseFromWebRedirect(force);
        } catch (Exception webError) {
            String first = firstError == null ? "unknown" : firstError.getMessage();
            throw new IllegalStateException("GitHub update endpoints unavailable: " + first, webError);
        }
    }

    private static JSONObject readReleaseObject(String url) throws Exception {
        return new JSONObject(readText(url, true));
    }

    private static JSONArray readJsonArray(String url) throws Exception {
        return new JSONArray(readText(url, true));
    }

    private static String readText(String url, boolean githubApi) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setRequestProperty("User-Agent", "WHUT-Timetable-Android");
        if (githubApi) connection.setRequestProperty("Accept", "application/vnd.github+json");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        try (InputStream input = connection.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject releaseFromWebRedirect(boolean force) throws Exception {
        String url = RELEASES_WEB_URL + (force ? "?_=" + System.currentTimeMillis() : "");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setRequestProperty("User-Agent", "WHUT-Timetable-Android");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 400) throw new IllegalStateException("HTTP " + code);
        String finalUrl = connection.getURL().toString();
        connection.disconnect();
        int marker = finalUrl.indexOf("/releases/tag/");
        if (marker < 0) throw new IllegalStateException("无法解析 GitHub latest tag");
        String tagRaw = finalUrl.substring(marker + "/releases/tag/".length());
        int query = tagRaw.indexOf('?');
        if (query >= 0) tagRaw = tagRaw.substring(0, query);
        if (tagRaw.isBlank()) throw new IllegalStateException("GitHub latest tag 为空");

        JSONObject release = new JSONObject();
        release.put("tag_name", tagRaw);
        release.put("body", "");
        release.put("html_url", finalUrl);
        JSONObject asset = new JSONObject();
        asset.put("name", "WHUT-Timetable-" + tagRaw + ".apk");
        asset.put("browser_download_url", "https://github.com/" + REPO + "/releases/download/" + tagRaw + "/WHUT-Timetable-" + tagRaw + ".apk");
        JSONArray assets = new JSONArray();
        assets.put(asset);
        release.put("assets", assets);
        return release;
    }

    static void downloadAndInstall(Activity activity, String apkUrl, String version) {
        if (apkUrl == null || apkUrl.isBlank()) {
            Toast.makeText(activity, "当前版本没有可用安装包", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(permission);
            Toast.makeText(activity, "请允许武理课表安装更新，返回后再点一次更新", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(activity, "正在下载更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File file = null;
            try {
                File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new IllegalStateException("无法访问下载目录");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建下载目录");
                file = new File(dir, "WHUT-Timetable-" + (version == null ? "update" : version) + ".apk");
                HttpURLConnection connection = (HttpURLConnection) new URL(apkUrl).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "WHUT-Timetable-Android");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("下载失败：HTTP " + code);
                try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream output = new FileOutputStream(file, false)) {
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                File finalFile = file;
                activity.runOnUiThread(() -> launchInstaller(activity, finalFile));
            } catch (Exception e) {
                activity.runOnUiThread(() -> Toast.makeText(activity, "更新下载失败，请稍后重试", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static void launchInstaller(Activity activity, File file) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "无法打开安装程序", Toast.LENGTH_LONG).show();
        }
    }

    private static void showUpdateNotification(Context context, String version) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "版本更新", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("有新版本时提醒你");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 22, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("武理课表有新版本")
                .setContentText("v" + version + " 已发布，点这里更新")
                .setAutoCancel(true)
                .setContentIntent(pending);
        manager.notify(2026, builder.build());
    }

    private static int compareVersions(String a, String b) {
        String[] aa = a.split("[.-]");
        String[] bb = b.split("[.-]");
        int length = Math.max(aa.length, bb.length);
        for (int i = 0; i < length; i++) {
            int av = i < aa.length ? parsePart(aa[i]) : 0;
            int bv = i < bb.length ? parsePart(bb[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int parsePart(String value) {
        try { return Integer.parseInt(value.replaceAll("\\D+", "")); }
        catch (Exception e) { return 0; }
    }
}
