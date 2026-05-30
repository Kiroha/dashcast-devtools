package com.dashcast.devtools.common;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;

import com.dashcast.devtools.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * OTA update checker.
 *
 * Queries the GitHub releases API for the latest release of
 * {@code Kiroha/dashcast-devtools}. If a newer version is found, downloads
 * the APK asset and installs it via {@link PackageInstaller}.
 *
 * With platform.keystore (INSTALL_PACKAGES permission granted by signature),
 * the install is silent. Otherwise {@link InstallResultReceiver} surfaces
 * STATUS_PENDING_USER_ACTION and shows the system install dialog.
 *
 * Transplanted from DashCast (MyBYDApp). Pre-release/single-track only here.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String RELEASES_LATEST_API =
            "https://api.github.com/repos/Kiroha/dashcast-devtools/releases/latest";
    private static final String RELEASES_LIST_API =
            "https://api.github.com/repos/Kiroha/dashcast-devtools/releases?per_page=10";
    private static final String APK_CACHE_NAME = "devtools-update.apk";

    /** Set to {@code true} to also consider GitHub pre-releases as candidates. */
    public static final boolean INCLUDE_PRERELEASES = true;

    // ── Progress callback (all methods dispatched on the main thread) ─────────

    public interface ProgressListener {
        void onUpdateFound(String version, String changelog, String downloadUrl);
        /** -1 = indeterminate (Content-Length unknown). */
        void onDownloadProgress(int percent);
        void onInstalling();
        void onUpToDate();
        void onError(String message);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static void checkUpdate(final Context context, final ProgressListener listener) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                doCheckUpdate(context.getApplicationContext(), listener, ui);
            } catch (Exception e) {
                AppLogger.e(TAG, "OTA check failed: " + e);
                if (listener != null) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    ui.post(() -> listener.onError(msg));
                }
            }
        }, "ota-check").start();
    }

    public static void startDownload(final Context context, final String apkUrl, final ProgressListener listener) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File apkFile = new File(context.getCacheDir(), APK_CACHE_NAME);
                downloadToFile(apkUrl, apkFile, listener, ui);
                AppLogger.i(TAG, "APK downloaded: " + apkFile.length() + " bytes → " + apkFile);
                if (listener != null) ui.post(listener::onInstalling);
                installApk(context, apkFile);
            } catch (Exception e) {
                AppLogger.e(TAG, "OTA download failed: " + e);
                if (listener != null) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    ui.post(() -> listener.onError(msg));
                }
            }
        }, "ota-download").start();
    }

    private static void doCheckUpdate(Context context, ProgressListener listener, Handler ui) throws Exception {
        JSONObject release;
        if (INCLUDE_PRERELEASES) {
            String json = httpGet(RELEASES_LIST_API);
            JSONArray list = new JSONArray(json);
            if (list.length() == 0) {
                AppLogger.i(TAG, "No releases found");
                if (listener != null) ui.post(listener::onUpToDate);
                return;
            }
            release = list.getJSONObject(0);
        } else {
            String json = httpGet(RELEASES_LATEST_API);
            release = new JSONObject(json);
        }
        String tag = release.getString("tag_name");
        String latestVer = tag.startsWith("v") ? tag.substring(1) : tag;

        if (!isNewer(latestVer, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {
            AppLogger.i(TAG, "Up to date (current=" + BuildConfig.VERSION_NAME
                    + "+build" + BuildConfig.VERSION_CODE + " latest=" + latestVer + ")");
            if (listener != null) ui.post(listener::onUpToDate);
            return;
        }

        String changelog = release.optString("body", "No changelog provided.");
        AppLogger.i(TAG, "Update available: " + BuildConfig.VERSION_NAME
                + "+build" + BuildConfig.VERSION_CODE + " → " + latestVer);

        JSONArray assets = release.getJSONArray("assets");
        String apkUrl = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (asset.getString("name").toLowerCase(Locale.ROOT).endsWith(".apk")) {
                apkUrl = asset.getString("browser_download_url");
                break;
            }
        }
        if (apkUrl == null) {
            AppLogger.e(TAG, "No APK asset in release " + latestVer);
            if (listener != null) ui.post(() -> listener.onError("No APK asset in release " + latestVer));
            return;
        }

        final String finalApkUrl = apkUrl;
        if (listener != null) ui.post(() -> listener.onUpdateFound(latestVer, changelog, finalApkUrl));
    }

    // ── Version comparison ────────────────────────────────────────────────────

    static boolean isNewer(String latest, String currentName, int currentCode) {
        int latestBuild = extractBuild(latest);
        String latestBase = stripSuffix(latest);
        String currentBase = stripSuffix(currentName);
        int[] l = parseVer(latestBase);
        int[] c = parseVer(currentBase);
        for (int i = 0; i < Math.max(l.length, c.length); i++) {
            int lv = i < l.length ? l[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (lv != cv) return lv > cv;
        }
        return latestBuild > 0 && latestBuild > currentCode;
    }

    private static int extractBuild(String tag) {
        int dash = tag.indexOf('-');
        if (dash < 0 || dash + 1 >= tag.length()) return -1;
        String suffix = tag.substring(dash + 1);
        if (suffix.startsWith("build")) suffix = suffix.substring(5);
        else if (suffix.startsWith("b") && suffix.length() > 1
                && Character.isDigit(suffix.charAt(1))) suffix = suffix.substring(1);
        try { return Integer.parseInt(suffix); } catch (NumberFormatException e) { return -1; }
    }

    private static String stripSuffix(String v) {
        int dash = v.indexOf('-');
        return dash < 0 ? v : v.substring(0, dash);
    }

    private static int[] parseVer(String v) {
        String[] parts = v.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { nums[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) {}
        }
        return nums;
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = openConnection(urlStr);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code + " for " + urlStr);
            try (InputStream in = conn.getInputStream()) {
                return readStream(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void downloadToFile(String urlStr, File dest,
                                       ProgressListener listener, Handler ui) throws Exception {
        HttpURLConnection conn = openConnection(urlStr);
        try {
            int code = conn.getResponseCode();
            int redirectCount = 0;
            while ((code == 301 || code == 302 || code == 307 || code == 308) && redirectCount < 5) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new Exception("Redirect " + code + " with no Location header");
                if (!location.toLowerCase(Locale.ROOT).startsWith("https://")) {
                    throw new Exception("Insecure redirect target: " + location);
                }
                conn = openConnection(location);
                code = conn.getResponseCode();
                redirectCount++;
            }
            if (redirectCount >= 5) throw new Exception("Too many redirects (" + redirectCount + ")");
            if (code != 200) throw new Exception("Download HTTP " + code);

            long total = conn.getContentLengthLong();
            long downloaded = 0;
            int lastPercent = -2;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (listener != null) {
                        int percent = total > 0 ? (int) (downloaded * 100 / total) : -1;
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final int p = percent;
                            ui.post(() -> listener.onDownloadProgress(p));
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "DashCastDevTools/" + BuildConfig.VERSION_NAME);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static String readStream(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    // ── Install ───────────────────────────────────────────────────────────────

    private static void installApk(Context context, File apkFile) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());

        int sessionId = -1;
        PackageInstaller.Session session = null;
        try {
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (OutputStream out = session.openWrite("update", 0, apkFile.length());
                 FileInputStream in = new FileInputStream(apkFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent resultIntent = new Intent(context, InstallResultReceiver.class);
            // FLAG_IMMUTABLE must NOT be used here — PackageInstaller injects
            // EXTRA_STATUS/EXTRA_STATUS_MESSAGE into the result intent.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(
                    context, sessionId, resultIntent, flags);
            session.commit(pi.getIntentSender());
            AppLogger.i(TAG, "PackageInstaller session committed, id=" + sessionId);
        } catch (Exception e) {
            if (session != null) {
                try { session.abandon(); } catch (Throwable ignore) {}
            } else if (sessionId != -1) {
                try { installer.abandonSession(sessionId); } catch (Throwable ignore) {}
            }
            throw e;
        }
    }
}
