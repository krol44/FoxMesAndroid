package org.telegram.messenger.foxmes;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BetaUpdate;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class FoxMesUpdater {

    private static final String MANIFEST_URL = "https://github.com/krol44/FoxMesAndroid/releases/latest/download/version.json";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final long CHECK_INTERVAL = 60 * 60 * 1000L;
    private static final long CHECK_INTERVAL_PAUSED = 24 * 60 * 60 * 1000L;

    private static volatile FoxMesUpdater instance;

    public static FoxMesUpdater getInstance() {
        if (instance == null) {
            synchronized (FoxMesUpdater.class) {
                if (instance == null) {
                    instance = new FoxMesUpdater();
                }
            }
        }
        return instance;
    }

    public static boolean isSupported() {
        return FoxMesFeatureGate.enabled && !BuildVars.DEBUG_VERSION;
    }

    private final DispatchQueue queue = new DispatchQueue("FoxMesUpdater");
    private final Runnable scheduledUpdateCheck = () -> checkForUpdate(false, null);

    private String version;
    private int versionCode;
    private String apkUrl;
    private String sha256;
    private String path;
    private long lastCheck;

    private boolean firstCheck = true;
    private boolean checkingForUpdate;
    private volatile boolean downloading;
    private float downloadingProgress;
    private volatile Call downloadCall;

    private FoxMesUpdater() {
        load();
    }

    private SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("foxmes_update", Context.MODE_PRIVATE);
    }

    private void load() {
        final SharedPreferences prefs = preferences();
        version = prefs.getString("version", null);
        versionCode = prefs.getInt("versionCode", 0);
        apkUrl = prefs.getString("apkUrl", null);
        sha256 = prefs.getString("sha256", null);
        path = prefs.getString("path", null);
        lastCheck = prefs.getLong("lastCheck", 0L);

        if (currentVersionCode() >= versionCode) {
            clearUpdate();
        } else if (!TextUtils.isEmpty(path) && !new File(path).exists()) {
            path = null;
            save();
        }
    }

    private void save() {
        preferences().edit()
                .putString("version", version)
                .putInt("versionCode", versionCode)
                .putString("apkUrl", apkUrl)
                .putString("sha256", sha256)
                .putString("path", path)
                .putLong("lastCheck", lastCheck)
                .apply();
    }

    private void clearUpdate() {
        deleteDownloadedFile();
        version = null;
        versionCode = 0;
        apkUrl = null;
        sha256 = null;
        save();
    }

    private void deleteDownloadedFile() {
        if (!TextUtils.isEmpty(path)) {
            new File(path).delete();
        }
        path = null;
    }


    public void checkForUpdate(boolean force, Runnable whenDone) {
        if (checkingForUpdate) {
            return;
        }
        if (firstCheck) {
            force = true;
        }
        long interval = ApplicationLoader.mainInterfacePaused ? CHECK_INTERVAL_PAUSED : CHECK_INTERVAL;
        if (!force && System.currentTimeMillis() - lastCheck < interval) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }
        checkingForUpdate = true;
        firstCheck = false;
        final Release known = version == null ? null : new Release(version, versionCode, apkUrl, sha256);
        queue.postRunnable(() -> {
            Release release = null;
            boolean failed = false;
            try {
                release = fetchRelease(known);
            } catch (Exception e) {
                failed = true;
                FoxMesLog.e("update check failed", e);
            }
            final Release result = release;
            final boolean checkFailed = failed;
            AndroidUtilities.runOnUIThread(() -> onCheckFinished(result, checkFailed, whenDone));
        });
    }

    private static Release fetchRelease(Release known) throws Exception {
        HttpUrl versionUrl = FoxMesHttp.url("/android/version", null);
        JSONObject allowed = new JSONObject(get(versionUrl, false));
        int allowedCode = allowed.optInt("version_code", 0);
        if (allowedCode <= currentVersionCode()) {
            return null;
        }
        if (known != null && known.versionCode == allowedCode && !TextUtils.isEmpty(known.url)) {
            return known;
        }

        JSONObject manifest = new JSONObject(get(HttpUrl.get(MANIFEST_URL), true));
        int manifestCode = manifest.getInt("version_code");
        if (manifestCode != allowedCode) {
            throw new IOException("version.json has version_code " + manifestCode + ", allowed version_code is " + allowedCode);
        }
        JSONObject android = manifest.getJSONObject("android");
        String url = android.getString("url");
        String digest = android.getString("sha256").toLowerCase(Locale.US);
        if (!isAllowedHost(HttpUrl.parse(url))) {
            throw new IOException("APK URL outside the GitHub allowlist: " + url);
        }
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IOException("version.json carries an invalid sha256");
        }
        return new Release(manifest.getString("version"), manifestCode, url, digest);
    }

    private void onCheckFinished(Release release, boolean failed, Runnable whenDone) {
        checkingForUpdate = false;
        final int oldVersionCode = versionCode;
        if (!failed) {
            lastCheck = System.currentTimeMillis();
            if (release == null) {
                clearUpdate();
            } else if (release.versionCode != versionCode) {
                deleteDownloadedFile();
                version = release.version;
                versionCode = release.versionCode;
                apkUrl = release.url;
                sha256 = release.sha256;
            }
            save();
        }
        if (versionCode != oldVersionCode) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
        }

        AndroidUtilities.cancelRunOnUIThread(scheduledUpdateCheck);
        AndroidUtilities.runOnUIThread(scheduledUpdateCheck, CHECK_INTERVAL);
        if (whenDone != null) {
            whenDone.run();
        } else if (versionCode != oldVersionCode && !ApplicationLoader.mainInterfacePaused) {
            final Context context = LaunchActivity.instance != null ? LaunchActivity.instance : ApplicationLoader.applicationContext;
            final BetaUpdate update = getUpdate();
            if (context != null && update != null) {
                ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(context, update, UserConfig.selectedAccount);
            }
        }
    }

    public BetaUpdate getUpdate() {
        if (version == null || versionCode <= currentVersionCode()) {
            return null;
        }
        return new BetaUpdate(version, versionCode, null);
    }


    public void downloadUpdate() {
        if (downloading || !TextUtils.isEmpty(path) || TextUtils.isEmpty(apkUrl)) {
            return;
        }
        downloading = true;
        downloadingProgress = 0f;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);

        final String url = apkUrl;
        final String digest = sha256;
        final int expectedCode = versionCode;
        final File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/foxmes-update");
        queue.postRunnable(() -> {
            File result = null;
            try {
                result = download(url, digest, expectedCode, dir);
            } catch (Exception e) {
                FoxMesLog.e("update download failed", e);
            }
            final File downloaded = result;
            AndroidUtilities.runOnUIThread(() -> onDownloadFinished(downloaded, expectedCode));
        });
    }

    private File download(String url, String digest, int expectedCode, File dir) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File[] stale = dir.listFiles();
        if (stale != null) {
            for (File file : stale) {
                file.delete();
            }
        }
        File partial = new File(dir, "FoxMes-" + expectedCode + ".apk.part");
        File target = new File(dir, "FoxMes-" + expectedCode + ".apk");

        HttpUrl httpUrl = HttpUrl.get(url);
        Call call = FoxMesHttp.clientFor(httpUrl, true).newCall(new Request.Builder().url(httpUrl).get().build());
        downloadCall = call;
        if (!downloading) {
            call.cancel();
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (Response response = call.execute()) {
            checkRedirectChain(response);
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("empty body for " + url);
            }
            long total = body.contentLength();
            long received = 0;
            long lastReport = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(partial)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    sha.update(buffer, 0, read);
                    received += read;
                    long now = System.currentTimeMillis();
                    if (total > 0 && now - lastReport > 200) {
                        lastReport = now;
                        final float progress = received / (float) total;
                        AndroidUtilities.runOnUIThread(() -> onDownloadProgress(progress));
                    }
                }
            }
        } catch (Exception e) {
            partial.delete();
            throw e;
        }

        String actual = hex(sha.digest());
        if (!actual.equals(digest)) {
            partial.delete();
            throw new IOException("SHA-256 mismatch: expected " + digest + ", got " + actual);
        }
        String rejection = verifyPackage(partial, expectedCode);
        if (rejection != null) {
            partial.delete();
            throw new IOException(rejection);
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            throw new IOException("cannot move the APK into place");
        }
        return target;
    }

    private void onDownloadProgress(float progress) {
        if (!downloading) {
            return;
        }
        downloadingProgress = progress;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
    }

    private void onDownloadFinished(File downloaded, int expectedCode) {
        downloadCall = null;
        if (!downloading || expectedCode != versionCode) {
            if (downloaded != null) {
                downloaded.delete();
            }
            return;
        }
        downloading = false;
        if (downloaded != null) {
            path = downloaded.getAbsolutePath();
            downloadingProgress = 1f;
            save();
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    public void cancelDownloadingUpdate() {
        if (!downloading) {
            return;
        }
        downloading = false;
        if (downloadCall != null) {
            downloadCall.cancel();
            downloadCall = null;
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    public boolean isDownloading() {
        return downloading;
    }

    public float getDownloadingProgress() {
        return downloadingProgress;
    }

    public File getDownloadedFile() {
        if (path == null) {
            return null;
        }
        File file = new File(path);
        if (!file.exists()) {
            path = null;
            save();
            return null;
        }
        return file;
    }


    public void install(Activity activity) {
        File file = getDownloadedFile();
        if (file == null || activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            AlertsCreator.createApkRestrictedDialog(activity, null).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 24) {
            intent.setDataAndType(FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file), APK_MIME);
        } else {
            intent.setDataAndType(Uri.fromFile(file), APK_MIME);
        }
        try {
            activity.startActivityForResult(intent, 500);
        } catch (Exception e) {
            FoxMesLog.e("cannot open the package installer", e);
        }
    }


    private static String verifyPackage(File apk, int expectedCode) {
        Context context = ApplicationLoader.applicationContext;
        PackageManager pm = context.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), signatureFlags());
        if (archive == null) {
            return "the downloaded file is not a readable APK";
        }
        if (!context.getPackageName().equals(archive.packageName)) {
            return "the APK is " + archive.packageName + ", not " + context.getPackageName();
        }
        if (buildCode(archive) != expectedCode) {
            return "the APK is build " + buildCode(archive) + ", expected " + expectedCode;
        }
        try {
            Set<Signature> installed = signatures(pm.getPackageInfo(context.getPackageName(), signatureFlags()));
            Set<Signature> downloaded = signatures(archive);
            if (installed.isEmpty() || !installed.equals(downloaded)) {
                return "the APK is signed with a different certificate";
            }
        } catch (PackageManager.NameNotFoundException e) {
            return "cannot read the installed signature";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static int signatureFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES
                : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static Set<Signature> signatures(PackageInfo info) {
        Signature[] list = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            list = info.signingInfo.getApkContentsSigners();
        }
        if (list == null) {
            list = info.signatures;
        }
        return list == null ? new HashSet<>() : new HashSet<>(Arrays.asList(list));
    }

    private static void checkRedirectChain(Response response) throws IOException {
        for (Response hop = response; hop != null; hop = hop.priorResponse()) {
            if (!isAllowedHost(hop.request().url())) {
                throw new IOException("download left the GitHub allowlist: " + hop.request().url());
            }
        }
    }

    private static boolean isAllowedHost(HttpUrl url) {
        if (url == null || !url.isHttps()) {
            return false;
        }
        String host = url.host();
        return host.equals("github.com") || host.endsWith(".githubusercontent.com");
    }

    private static String get(HttpUrl url, boolean github) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = FoxMesHttp.clientFor(url, false).newCall(request).execute()) {
            if (github) {
                checkRedirectChain(response);
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("empty body for " + url);
            }
            return body.string();
        }
    }

    @SuppressWarnings("deprecation")
    private static int buildCode(PackageInfo info) {
        long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        return (int) (code / 10);
    }

    private static int currentVersionCode() {
        try {
            Context context = ApplicationLoader.applicationContext;
            return buildCode(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
        } catch (Exception e) {
            FoxMesLog.e("cannot read the installed version", e);
            return Integer.MAX_VALUE;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format(Locale.US, "%02x", b));
        }
        return builder.toString();
    }

    private static final class Release {
        final String version;
        final int versionCode;
        final String url;
        final String sha256;

        Release(String version, int versionCode, String url, String sha256) {
            this.version = version;
            this.versionCode = versionCode;
            this.url = url;
            this.sha256 = sha256;
        }
    }
}
