package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.AvoidUsingVolatile",
        "PMD.AvoidCatchingGenericException",
        "PMD.SignatureDeclareThrowsException",
        "PMD.AvoidFileStream",
        "PMD.ExceptionAsFlowControl",
        "PMD.AvoidSynchronizedStatement",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public final class AppUpdateManager {

    private static final int TIRAMISU_API = 33;
    private static final String CACHE_PREFS_NAME = "app_update_cache";
    private static final String RELEASES_URL = "https://api.github.com/repos/WINGS-N/WINGSV/releases/latest";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String PREFERRED_APK_ASSET_NAME = "app-release.apk";
    private static final String KEY_LAST_CHECK_AT = "last_check_at";
    private static final String KEY_LAST_RELEASE_ETAG = "last_release_etag";
    private static final String KEY_LAST_RELEASE_JSON = "last_release_json";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long AUTO_CHECK_MIN_INTERVAL_MS = 60L * 60L * 1000L;
    private static final long MIN_CONTENT_LENGTH_BYTES = 1L;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 250L;
    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("\\d+");

    private static volatile AppUpdateManager instance;

    private final Context appContext;
    private final SharedPreferences cachePreferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

    private volatile UpdateState state;
    private volatile boolean checkInFlight;
    private volatile boolean downloadInFlight;
    private volatile boolean cancelRequested;

    private AppUpdateManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.cachePreferences = appContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE);
        this.state = loadPersistedState();
    }

    public static AppUpdateManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AppUpdateManager.class) {
                if (instance == null) {
                    instance = new AppUpdateManager(context);
                }
            }
        }
        return instance;
    }

    public void registerListener(@NonNull Listener listener) {
        listeners.add(listener);
        dispatchToListener(listener, state);
    }

    public void unregisterListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    @NonNull
    public UpdateState getState() {
        return state;
    }

    public void checkForUpdates() {
        requestUpdateCheck(true);
    }

    public void checkForUpdatesIfStale() {
        requestUpdateCheck(false);
    }

    private void requestUpdateCheck(boolean forceRefresh) {
        if (checkInFlight || downloadInFlight) {
            return;
        }
        if (!forceRefresh) {
            UpdateState cachedState = resolveFreshCachedState();
            if (cachedState != null) {
                updateState(cachedState);
                return;
            }
        }
        checkInFlight = true;
        updateState(UpdateState.checking(state.releaseInfo));
        executor.execute(() -> {
            try {
                updateState(resolveLatestState(true, forceRefresh));
            } catch (Exception error) {
                updateState(UpdateState.error(describeThrowable(error), state.releaseInfo));
            } finally {
                checkInFlight = false;
            }
        });
    }

    @NonNull
    public UpdateState queryLatestStateBlocking() {
        try {
            return resolveLatestState(false, false);
        } catch (Exception error) {
            return UpdateState.error(describeThrowable(error), state.releaseInfo);
        }
    }

    public void applyBackgroundState(@NonNull UpdateState newState) {
        if (checkInFlight || downloadInFlight) {
            return;
        }
        updateState(newState);
    }

    public void startDownload() {
        UpdateState currentState = state;
        ReleaseInfo releaseInfo = currentState.releaseInfo;
        if (downloadInFlight || releaseInfo == null || !releaseInfo.hasInstallableAsset()) {
            return;
        }
        File cachedApk = buildTargetApkFile(releaseInfo);
        if (cachedApk.isFile() && cachedApk.length() > 0L) {
            updateState(UpdateState.downloaded(releaseInfo, cachedApk));
            return;
        }

        downloadInFlight = true;
        cancelRequested = false;
        updateState(
            UpdateState.downloading(releaseInfo, 0L, releaseInfo.apkAssetSize, 0L, releaseInfo.apkAssetSize, 0)
        );
        executor.execute(() -> {
            File tempFile = buildTempApkFile(releaseInfo);
            File targetFile = buildTargetApkFile(releaseInfo);
            HttpURLConnection connection = null;
            try {
                deleteQuietly(tempFile);
                targetFile.getParentFile().mkdirs();

                connection = openConnection(releaseInfo.apkAssetUrl);
                activeConnection.set(connection);
                connection.connect();
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 400) {
                    throw new IllegalStateException("GitHub release asset HTTP " + responseCode);
                }

                long totalBytes = connection.getContentLengthLong();
                if (totalBytes < MIN_CONTENT_LENGTH_BYTES) {
                    totalBytes = releaseInfo.apkAssetSize;
                }

                long startedAt = System.currentTimeMillis();
                long lastPublishedAt = 0L;
                long downloadedBytes = 0L;

                try (
                    InputStream inputStream = connection.getInputStream();
                    FileOutputStream outputStream = new FileOutputStream(tempFile)
                ) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    read = inputStream.read(buffer);
                    while (read != -1) {
                        if (cancelRequested) {
                            throw new DownloadCancelledException();
                        }
                        outputStream.write(buffer, 0, read);
                        downloadedBytes += read;

                        long now = System.currentTimeMillis();
                        if (now - lastPublishedAt >= PROGRESS_UPDATE_INTERVAL_MS) {
                            long elapsedMs = Math.max(1L, now - startedAt);
                            long speedBytesPerSecond = (downloadedBytes * 1000L) / elapsedMs;
                            long remainingBytes = totalBytes > 0L ? Math.max(0L, totalBytes - downloadedBytes) : -1L;
                            int progressPercent =
                                totalBytes > 0L ? (int) Math.min(100L, (downloadedBytes * 100L) / totalBytes) : 0;
                            updateState(
                                UpdateState.downloading(
                                    releaseInfo,
                                    downloadedBytes,
                                    totalBytes,
                                    speedBytesPerSecond,
                                    remainingBytes,
                                    progressPercent
                                )
                            );
                            lastPublishedAt = now;
                        }
                        read = inputStream.read(buffer);
                    }
                    outputStream.flush();
                }

                if (cancelRequested) {
                    throw new DownloadCancelledException();
                }
                if (targetFile.exists() && !targetFile.delete()) {
                    throw new IllegalStateException("Не удалось заменить старый APK");
                }
                if (!tempFile.renameTo(targetFile)) {
                    throw new IllegalStateException("Не удалось сохранить APK");
                }
                updateState(UpdateState.downloaded(releaseInfo, targetFile));
            } catch (DownloadCancelledException ignored) {
                deleteQuietly(tempFile);
                updateState(UpdateState.updateAvailable(releaseInfo, "Загрузка отменена"));
            } catch (Exception error) {
                deleteQuietly(tempFile);
                updateState(UpdateState.error(describeThrowable(error), releaseInfo));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                activeConnection.set(null);
                cancelRequested = false;
                downloadInFlight = false;
            }
        });
    }

    public void cancelDownload() {
        if (!downloadInFlight) {
            return;
        }
        cancelRequested = true;
        HttpURLConnection connection = activeConnection.getAndSet(null);
        if (connection != null) {
            connection.disconnect();
        }
    }

    @NonNull
    public static String getApkMimeType() {
        return APK_MIME_TYPE;
    }

    private HttpURLConnection openConnection(String urlString) throws Exception {
        HttpURLConnection connection = DirectNetworkConnection.openHttpConnection(appContext, new URL(urlString), true);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "WINGSV/" + resolveCurrentVersionName());
        return connection;
    }

    @NonNull
    private UpdateState resolveLatestState(boolean trackActiveConnection, boolean forceRefresh) throws Exception {
        if (!forceRefresh) {
            UpdateState cachedState = resolveFreshCachedState();
            if (cachedState != null) {
                return cachedState;
            }
        }
        ReleaseInfo releaseInfo = fetchLatestRelease(trackActiveConnection);
        if (releaseInfo == null) {
            ReleaseInfo cachedRelease = readCachedReleaseInfo();
            if (cachedRelease != null) {
                return buildStateFromReleaseInfo(cachedRelease);
            }
            return UpdateState.error("GitHub не вернул опубликованный релиз", null);
        }
        return buildStateFromReleaseInfo(releaseInfo);
    }

    @NonNull
    private UpdateState buildStateFromReleaseInfo(@NonNull ReleaseInfo releaseInfo) {
        if (!releaseInfo.hasInstallableAsset()) {
            return UpdateState.error("В релизе не найден APK-артефакт", releaseInfo);
        }
        boolean releaseIsNewer = isRemoteVersionNewer(
            releaseInfo.versionName,
            releaseInfo.versionCode,
            resolveCurrentVersionName(),
            resolveCurrentVersionCode()
        );
        if (!releaseIsNewer) {
            return UpdateState.upToDate(releaseInfo);
        }
        File cachedApk = resolveReadyDownloadedApk(releaseInfo);
        if (cachedApk != null) {
            return UpdateState.downloaded(releaseInfo, cachedApk);
        }
        return UpdateState.updateAvailable(releaseInfo);
    }

    @Nullable
    private ReleaseInfo fetchLatestRelease(boolean trackActiveConnection) throws Exception {
        HttpURLConnection connection = openConnection(RELEASES_URL);
        String cachedEtag = cachePreferences.getString(KEY_LAST_RELEASE_ETAG, "");
        if (!TextUtils.isEmpty(cachedEtag)) {
            connection.setRequestProperty("If-None-Match", cachedEtag);
        }
        if (trackActiveConnection) {
            activeConnection.set(connection);
        }
        try {
            connection.connect();
            int responseCode = connection.getResponseCode();
            String body = readResponseBody(connection);
            if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                persistLastCheckedAt();
                return readCachedReleaseInfo();
            }
            if (responseCode < 200 || responseCode >= 400) {
                if (isRateLimitResponse(connection, body)) {
                    persistLastCheckedAt();
                    ReleaseInfo cachedRelease = readCachedReleaseInfo();
                    if (cachedRelease != null) {
                        return cachedRelease;
                    }
                    throw new IllegalStateException("GitHub API rate limit exceeded");
                }
                String message = extractGithubMessage(body);
                if (TextUtils.isEmpty(message)) {
                    message = "GitHub releases HTTP " + responseCode;
                }
                throw new IllegalStateException(message);
            }

            ReleaseInfo releaseInfo = parseReleaseInfo(new JSONObject(body));
            persistReleaseCache(body, connection.getHeaderField("ETag"));
            return releaseInfo;
        } finally {
            connection.disconnect();
            if (trackActiveConnection) {
                activeConnection.compareAndSet(connection, null);
            }
        }
    }

    @Nullable
    private UpdateState resolveFreshCachedState() {
        if (isAutoCheckStale()) {
            return null;
        }
        ReleaseInfo cachedRelease = readCachedReleaseInfo();
        if (cachedRelease == null) {
            return null;
        }
        return buildStateFromReleaseInfo(cachedRelease);
    }

    private boolean isAutoCheckStale() {
        long lastCheckAt = cachePreferences.getLong(KEY_LAST_CHECK_AT, 0L);
        return lastCheckAt <= 0L || System.currentTimeMillis() - lastCheckAt >= AUTO_CHECK_MIN_INTERVAL_MS;
    }

    private void persistLastCheckedAt() {
        cachePreferences.edit().putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply();
    }

    private void persistReleaseCache(@Nullable String releaseJson, @Nullable String etag) {
        cachePreferences
            .edit()
            .putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
            .putString(KEY_LAST_RELEASE_JSON, releaseJson == null ? "" : releaseJson)
            .putString(KEY_LAST_RELEASE_ETAG, etag == null ? "" : etag)
            .apply();
    }

    @Nullable
    private ReleaseInfo readCachedReleaseInfo() {
        String cachedJson = cachePreferences.getString(KEY_LAST_RELEASE_JSON, "");
        if (TextUtils.isEmpty(cachedJson)) {
            return null;
        }
        try {
            return parseReleaseInfo(new JSONObject(cachedJson));
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    private UpdateState loadPersistedState() {
        ReleaseInfo cachedRelease = readCachedReleaseInfo();
        if (cachedRelease == null) {
            return UpdateState.idle();
        }
        return buildStateFromReleaseInfo(cachedRelease);
    }

    private static boolean isRateLimitResponse(HttpURLConnection connection, String body) {
        String message = extractGithubMessage(body);
        if (!TextUtils.isEmpty(message) && message.toLowerCase(Locale.US).contains("rate limit")) {
            return true;
        }
        String remaining = connection.getHeaderField("X-RateLimit-Remaining");
        return "0".equals(remaining);
    }

    @NonNull
    private static ReleaseInfo parseReleaseInfo(@NonNull JSONObject root) {
        JSONArray assets = root.optJSONArray("assets");
        String selectedAssetName = "";
        String selectedAssetUrl = "";
        long selectedAssetSize = 0L;
        if (assets != null) {
            for (int index = 0; index < assets.length(); index++) {
                JSONObject asset = assets.optJSONObject(index);
                if (asset == null) {
                    continue;
                }
                String assetName = asset.optString("name", "");
                String assetUrl = asset.optString("browser_download_url", "");
                if (TextUtils.isEmpty(assetName) || TextUtils.isEmpty(assetUrl)) {
                    continue;
                }
                if (!assetName.toLowerCase(Locale.US).endsWith(".apk")) {
                    continue;
                }
                selectedAssetName = assetName;
                selectedAssetUrl = assetUrl;
                selectedAssetSize = asset.optLong("size", 0L);
                if (PREFERRED_APK_ASSET_NAME.equals(assetName)) {
                    break;
                }
            }
        }

        return new ReleaseInfo(
            root.optString("tag_name", ""),
            root.optString("name", ""),
            root.optString("html_url", ""),
            root.optString("body", ""),
            root.optString("published_at", ""),
            selectedAssetName,
            selectedAssetUrl,
            selectedAssetSize
        );
    }

    private static String readResponseBody(HttpURLConnection connection) throws Exception {
        try (
            InputStream inputStream = openResponseStream(connection);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        ) {
            if (inputStream == null) {
                return "";
            }
            byte[] buffer = new byte[4096];
            int read;
            read = inputStream.read(buffer);
            while (read != -1) {
                outputStream.write(buffer, 0, read);
                read = inputStream.read(buffer);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Nullable
    private static InputStream openResponseStream(HttpURLConnection connection) throws IOException {
        try {
            return connection.getResponseCode() >= 200 && connection.getResponseCode() < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
        } catch (IOException ignored) {
            return connection.getErrorStream();
        }
    }

    private static String extractGithubMessage(String body) {
        if (TextUtils.isEmpty(body)) {
            return "";
        }
        try {
            JSONObject object = new JSONObject(body);
            return object.optString("message", "");
        } catch (org.json.JSONException ignored) {
            return "";
        }
    }

    private void updateState(@NonNull UpdateState newState) {
        state = newState;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onUpdateStateChanged(newState);
            }
        });
    }

    private void dispatchToListener(@NonNull Listener listener, @NonNull UpdateState currentState) {
        mainHandler.post(() -> listener.onUpdateStateChanged(currentState));
    }

    private File buildTargetApkFile(ReleaseInfo releaseInfo) {
        File directory = new File(appContext.getCacheDir(), "updates");
        return new File(directory, "WINGSV-" + sanitizeFileComponent(releaseInfo.tagName) + ".apk");
    }

    private File buildTempApkFile(ReleaseInfo releaseInfo) {
        File directory = new File(appContext.getCacheDir(), "updates");
        return new File(directory, "WINGSV-" + sanitizeFileComponent(releaseInfo.tagName) + ".apk.part");
    }

    @Nullable
    private File resolveReadyDownloadedApk(ReleaseInfo releaseInfo) {
        File cachedApk = buildTargetApkFile(releaseInfo);
        if (!cachedApk.isFile() || cachedApk.length() <= 0L) {
            return null;
        }
        if (releaseInfo.apkAssetSize > 0L && cachedApk.length() != releaseInfo.apkAssetSize) {
            return null;
        }
        return cachedApk;
    }

    private static String sanitizeFileComponent(String value) {
        String sanitized = TextUtils.isEmpty(value) ? "latest" : value.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return TextUtils.isEmpty(sanitized) ? "latest" : sanitized;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static boolean isRemoteVersionNewer(
        String remoteVersion,
        long remoteVersionCode,
        String currentVersion,
        long currentVersionCode
    ) {
        if (remoteVersionCode > 0L && currentVersionCode > 0L && remoteVersionCode != currentVersionCode) {
            return remoteVersionCode > currentVersionCode;
        }
        return compareVersions(remoteVersion, currentVersion) > 0;
    }

    private static int compareVersions(String left, String right) {
        List<Long> leftParts = extractVersionParts(left);
        List<Long> rightParts = extractVersionParts(right);
        int maxSize = Math.max(leftParts.size(), rightParts.size());
        for (int index = 0; index < maxSize; index++) {
            long leftValue = index < leftParts.size() ? leftParts.get(index) : 0L;
            long rightValue = index < rightParts.size() ? rightParts.get(index) : 0L;
            if (leftValue != rightValue) {
                return leftValue > rightValue ? 1 : -1;
            }
        }
        return 0;
    }

    @NonNull
    private static List<Long> extractVersionParts(String value) {
        List<Long> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        Matcher matcher = VERSION_NUMBER_PATTERN.matcher(value);
        while (matcher.find()) {
            try {
                result.add(Long.parseLong(matcher.group()));
            } catch (NumberFormatException ignored) {
                result.add(0L);
            }
        }
        return result;
    }

    private static String describeThrowable(Exception error) {
        String message = error.getMessage();
        if (!TextUtils.isEmpty(message)) {
            return message;
        }
        return error.getClass().getSimpleName();
    }

    private String resolveCurrentVersionName() {
        try {
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= TIRAMISU_API) {
                packageInfo = appContext
                    .getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                packageInfo = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            }
            if (packageInfo.versionName != null) {
                return packageInfo.versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // No-op.
        }
        return "0";
    }

    private long resolveCurrentVersionCode() {
        try {
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= TIRAMISU_API) {
                packageInfo = appContext
                    .getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                packageInfo = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return packageInfo.getLongVersionCode();
            }
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            return 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onUpdateStateChanged(@NonNull UpdateState state);
    }

    public enum Status {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        DOWNLOADING,
        DOWNLOADED,
        ERROR,
    }

    public static final class ReleaseInfo {

        @NonNull
        public final String tagName;

        @NonNull
        public final String releaseName;

        @NonNull
        public final String releaseUrl;

        @NonNull
        public final String releaseBody;

        @NonNull
        public final String publishedAt;

        @NonNull
        public final String apkAssetName;

        @NonNull
        public final String apkAssetUrl;

        public final long apkAssetSize;

        @NonNull
        public final String versionName;

        public final long versionCode;

        ReleaseInfo(
            @Nullable String tagName,
            @Nullable String releaseName,
            @Nullable String releaseUrl,
            @Nullable String releaseBody,
            @Nullable String publishedAt,
            @Nullable String apkAssetName,
            @Nullable String apkAssetUrl,
            long apkAssetSize
        ) {
            this.tagName = safe(tagName);
            this.releaseName = safe(releaseName);
            this.releaseUrl = safe(releaseUrl);
            this.releaseBody = safe(releaseBody);
            this.publishedAt = safe(publishedAt);
            this.apkAssetName = safe(apkAssetName);
            this.apkAssetUrl = safe(apkAssetUrl);
            this.apkAssetSize = Math.max(0L, apkAssetSize);
            this.versionName = normalizeVersionName(this.tagName);
            this.versionCode = deriveVersionCode(this.versionName);
        }

        public boolean hasInstallableAsset() {
            return !TextUtils.isEmpty(apkAssetUrl) && !TextUtils.isEmpty(apkAssetName);
        }

        private static String normalizeVersionName(String tagName) {
            if (TextUtils.isEmpty(tagName)) {
                return "";
            }
            if (tagName.startsWith("v") || tagName.startsWith("V")) {
                return tagName.substring(1);
            }
            return tagName;
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }

        private static long deriveVersionCode(String versionName) {
            List<Long> parts = extractVersionParts(versionName);
            if (parts.isEmpty()) {
                return 0L;
            }
            long major = parts.get(0);
            long minor = parts.size() > 1 ? parts.get(1) : 0L;
            long patch = parts.size() > 2 ? parts.get(2) : 0L;
            if (major < 0L || minor < 0L || patch < 0L || minor > 99L || patch > 99L) {
                return 0L;
            }
            return major * 10_000L + minor * 100L + patch;
        }
    }

    public static final class UpdateState {

        @NonNull
        public final Status status;

        @Nullable
        public final ReleaseInfo releaseInfo;

        @Nullable
        public final String message;

        public final long downloadedBytes;
        public final long totalBytes;
        public final long speedBytesPerSecond;
        public final long remainingBytes;
        public final int progressPercent;

        @Nullable
        public final File downloadedFile;

        private UpdateState(
            @NonNull Status status,
            @Nullable ReleaseInfo releaseInfo,
            @Nullable String message,
            long downloadedBytes,
            long totalBytes,
            long speedBytesPerSecond,
            long remainingBytes,
            int progressPercent,
            @Nullable File downloadedFile
        ) {
            this.status = status;
            this.releaseInfo = releaseInfo;
            this.message = message;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.speedBytesPerSecond = speedBytesPerSecond;
            this.remainingBytes = remainingBytes;
            this.progressPercent = progressPercent;
            this.downloadedFile = downloadedFile;
        }

        static UpdateState idle() {
            return new UpdateState(Status.IDLE, null, null, 0L, 0L, 0L, 0L, 0, null);
        }

        static UpdateState checking(@Nullable ReleaseInfo releaseInfo) {
            return new UpdateState(Status.CHECKING, releaseInfo, null, 0L, 0L, 0L, 0L, 0, null);
        }

        static UpdateState upToDate(@NonNull ReleaseInfo releaseInfo) {
            return new UpdateState(Status.UP_TO_DATE, releaseInfo, null, 0L, 0L, 0L, 0L, 0, null);
        }

        static UpdateState updateAvailable(@NonNull ReleaseInfo releaseInfo) {
            return updateAvailable(releaseInfo, null);
        }

        static UpdateState updateAvailable(@NonNull ReleaseInfo releaseInfo, @Nullable String message) {
            return new UpdateState(Status.UPDATE_AVAILABLE, releaseInfo, message, 0L, 0L, 0L, 0L, 0, null);
        }

        static UpdateState downloading(
            @NonNull ReleaseInfo releaseInfo,
            long downloadedBytes,
            long totalBytes,
            long speedBytesPerSecond,
            long remainingBytes,
            int progressPercent
        ) {
            return new UpdateState(
                Status.DOWNLOADING,
                releaseInfo,
                null,
                downloadedBytes,
                totalBytes,
                speedBytesPerSecond,
                remainingBytes,
                progressPercent,
                null
            );
        }

        static UpdateState downloaded(@NonNull ReleaseInfo releaseInfo, @NonNull File downloadedFile) {
            return new UpdateState(
                Status.DOWNLOADED,
                releaseInfo,
                null,
                downloadedFile.length(),
                downloadedFile.length(),
                0L,
                0L,
                100,
                downloadedFile
            );
        }

        static UpdateState error(@NonNull String message, @Nullable ReleaseInfo releaseInfo) {
            return new UpdateState(Status.ERROR, releaseInfo, message, 0L, 0L, 0L, 0L, 0, null);
        }
    }

    private static final class DownloadCancelledException extends Exception {

        private static final long serialVersionUID = 1L;
    }
}
