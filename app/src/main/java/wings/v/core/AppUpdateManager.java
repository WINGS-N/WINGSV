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
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private static final String RELEASES_URL = "https://api.github.com/repos/WINGS-N/WINGSV/releases?per_page=4";
    private static final String RELEASES_URL_OVERRIDE_PROP = "debug.wingsv.releases_url";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final String PREFERRED_APK_ASSET_NAME = "app-release.apk";
    private static final String PATCH_ASSET_PREFIX = "wings-v_v";
    private static final String PATCH_ASSET_SUFFIX = ".patch";
    private static final String KEY_LAST_CHECK_AT = "last_check_at";
    private static final String KEY_LAST_RELEASE_ETAG = "last_release_etag";
    private static final String KEY_LAST_RELEASE_JSON = "last_release_json";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long AUTO_CHECK_MIN_INTERVAL_MS = 60L * 60L * 1000L;
    private static final long MIN_CONTENT_LENGTH_BYTES = 1L;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 250L;
    private static final int MAX_PATCH_CHAIN_DEPTH = 3;
    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("\\d+");

    private static volatile AppUpdateManager instance;

    private final Context appContext;
    private final SharedPreferences cachePreferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

    private volatile UpdateState state;
    private volatile UpdatePlan updatePlan = UpdatePlan.empty();
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

        UpdatePlan currentPlan = updatePlan.isUsable() ? updatePlan : UpdatePlan.full(releaseInfo);
        long initialTotalBytes = Math.max(
            MIN_CONTENT_LENGTH_BYTES,
            currentPlan.hasPatchChain() ? currentPlan.totalPatchBytes() : releaseInfo.apkAssetSize
        );
        downloadInFlight = true;
        cancelRequested = false;
        updateState(UpdateState.downloading(releaseInfo, 0L, initialTotalBytes, 0L, initialTotalBytes, 0));
        executor.execute(() -> {
            try {
                File targetFile = currentPlan.hasPatchChain()
                    ? downloadPatchedRelease(currentPlan)
                    : downloadFullRelease(currentPlan.latestRelease);
                updateState(UpdateState.downloaded(releaseInfo, targetFile));
            } catch (DownloadCancelledException ignored) {
                updateState(UpdateState.updateAvailable(releaseInfo, "Загрузка отменена"));
            } catch (Exception error) {
                if (currentPlan.hasPatchChain()) {
                    try {
                        updateState(
                            UpdateState.downloading(
                                releaseInfo,
                                0L,
                                Math.max(MIN_CONTENT_LENGTH_BYTES, releaseInfo.apkAssetSize),
                                0L,
                                Math.max(MIN_CONTENT_LENGTH_BYTES, releaseInfo.apkAssetSize),
                                0
                            )
                        );
                        File targetFile = downloadFullRelease(releaseInfo);
                        updateState(UpdateState.downloaded(releaseInfo, targetFile));
                    } catch (DownloadCancelledException ignored) {
                        updateState(UpdateState.updateAvailable(releaseInfo, "Загрузка отменена"));
                    } catch (Exception fallbackError) {
                        updateState(UpdateState.error(describeThrowable(fallbackError), releaseInfo));
                    }
                } else {
                    updateState(UpdateState.error(describeThrowable(error), releaseInfo));
                }
            } finally {
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

    @NonNull
    private static String resolveReleasesUrl() {
        if (!wings.v.BuildConfig.DEBUG) {
            return RELEASES_URL;
        }
        try {
            Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
            Object value = systemPropertiesClass
                .getMethod("get", String.class)
                .invoke(null, RELEASES_URL_OVERRIDE_PROP);
            if (value instanceof String) {
                String trimmed = ((String) value).trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        } catch (Throwable ignored) {}
        return RELEASES_URL;
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
        List<ReleaseInfo> releases = fetchRecentReleases(trackActiveConnection);
        if (releases.isEmpty()) {
            List<ReleaseInfo> cachedReleases = readCachedReleaseInfoList();
            if (!cachedReleases.isEmpty()) {
                return buildStateFromReleaseCatalog(cachedReleases);
            }
            return UpdateState.error("GitHub не вернул опубликованный релиз", null);
        }
        return buildStateFromReleaseCatalog(releases);
    }

    @NonNull
    private UpdateState buildStateFromReleaseCatalog(@NonNull List<ReleaseInfo> releases) {
        ReleaseInfo releaseInfo = releases.get(0);
        updatePlan = UpdatePlan.empty();
        if (!releaseInfo.hasInstallableAsset()) {
            return UpdateState.error("В релизе не найден APK-артефакт", releaseInfo);
        }
        String currentVersionName = resolveCurrentVersionName();
        long currentVersionCode = resolveCurrentVersionCode();
        boolean releaseIsNewer = isRemoteVersionNewer(
            releaseInfo.versionName,
            releaseInfo.versionCode,
            currentVersionName,
            currentVersionCode
        );
        if (!releaseIsNewer) {
            return UpdateState.upToDate(releaseInfo);
        }
        File cachedApk = resolveReadyDownloadedApk(releaseInfo);
        updatePlan = buildUpdatePlan(releases, currentVersionName, currentVersionCode);
        if (cachedApk != null) {
            return UpdateState.downloaded(releaseInfo, cachedApk);
        }
        return UpdateState.updateAvailable(releaseInfo);
    }

    @Nullable
    private List<ReleaseInfo> fetchRecentReleases(boolean trackActiveConnection) throws Exception {
        HttpURLConnection connection = openConnection(resolveReleasesUrl());
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
                return readCachedReleaseInfoList();
            }
            if (responseCode < 200 || responseCode >= 400) {
                if (isRateLimitResponse(connection, body)) {
                    persistLastCheckedAt();
                    List<ReleaseInfo> cachedReleases = readCachedReleaseInfoList();
                    if (!cachedReleases.isEmpty()) {
                        return cachedReleases;
                    }
                    throw new IllegalStateException("GitHub API rate limit exceeded");
                }
                String message = extractGithubMessage(body);
                if (TextUtils.isEmpty(message)) {
                    message = "GitHub releases HTTP " + responseCode;
                }
                throw new IllegalStateException(message);
            }

            List<ReleaseInfo> releaseInfo = parseReleaseInfoList(new JSONArray(body));
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
        List<ReleaseInfo> cachedReleases = readCachedReleaseInfoList();
        if (cachedReleases.isEmpty()) {
            return null;
        }
        return buildStateFromReleaseCatalog(cachedReleases);
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
    private List<ReleaseInfo> readCachedReleaseInfoList() {
        String cachedJson = cachePreferences.getString(KEY_LAST_RELEASE_JSON, "");
        if (TextUtils.isEmpty(cachedJson)) {
            return new ArrayList<>();
        }
        try {
            String normalized = cachedJson.trim();
            if (normalized.startsWith("[")) {
                return parseReleaseInfoList(new JSONArray(normalized));
            }
            if (normalized.startsWith("{")) {
                ArrayList<ReleaseInfo> result = new ArrayList<>();
                result.add(parseReleaseInfo(new JSONObject(normalized)));
                return result;
            }
        } catch (Exception ignored) {}
        return new ArrayList<>();
    }

    @NonNull
    private UpdateState loadPersistedState() {
        List<ReleaseInfo> cachedReleases = readCachedReleaseInfoList();
        if (cachedReleases.isEmpty()) {
            return UpdateState.idle();
        }
        return buildStateFromReleaseCatalog(cachedReleases);
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
        String tagName = root.optString("tag_name", "");
        String versionName = ReleaseInfo.normalizeVersionName(tagName);
        JSONArray assets = root.optJSONArray("assets");
        String selectedAssetName = "";
        String selectedAssetUrl = "";
        long selectedAssetSize = 0L;
        String selectedPatchName = "";
        String selectedPatchUrl = "";
        long selectedPatchSize = 0L;
        String apkSha256Url = "";
        String apkSha512Url = "";
        String patchSha256Url = "";
        String patchSha512Url = "";
        String expectedPatchName = buildPatchAssetName(versionName);
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
                if (assetName.toLowerCase(Locale.US).endsWith(".apk")) {
                    if (TextUtils.isEmpty(selectedAssetName) || PREFERRED_APK_ASSET_NAME.equals(assetName)) {
                        selectedAssetName = assetName;
                        selectedAssetUrl = assetUrl;
                        selectedAssetSize = asset.optLong("size", 0L);
                    }
                    if (PREFERRED_APK_ASSET_NAME.equals(assetName)) {
                        continue;
                    }
                }
                if (TextUtils.equals(assetName, expectedPatchName)) {
                    selectedPatchName = assetName;
                    selectedPatchUrl = assetUrl;
                    selectedPatchSize = asset.optLong("size", 0L);
                }
            }
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
                if (!TextUtils.isEmpty(selectedAssetName)) {
                    if (TextUtils.equals(assetName, selectedAssetName + ".sha512")) {
                        apkSha512Url = assetUrl;
                    } else if (TextUtils.equals(assetName, selectedAssetName + ".sha256")) {
                        apkSha256Url = assetUrl;
                    }
                }
                if (!TextUtils.isEmpty(selectedPatchName)) {
                    if (TextUtils.equals(assetName, selectedPatchName + ".sha512")) {
                        patchSha512Url = assetUrl;
                    } else if (TextUtils.equals(assetName, selectedPatchName + ".sha256")) {
                        patchSha256Url = assetUrl;
                    }
                }
            }
        }
        ChecksumInfo apkChecksum = preferredChecksum(apkSha512Url, apkSha256Url);
        ChecksumInfo patchChecksum = preferredChecksum(patchSha512Url, patchSha256Url);

        return new ReleaseInfo(
            tagName,
            root.optString("name", ""),
            root.optString("html_url", ""),
            root.optString("body", ""),
            root.optString("published_at", ""),
            selectedAssetName,
            selectedAssetUrl,
            selectedAssetSize,
            selectedPatchName,
            selectedPatchUrl,
            selectedPatchSize,
            apkChecksum.algorithm,
            apkChecksum.url,
            patchChecksum.algorithm,
            patchChecksum.url
        );
    }

    @NonNull
    private static List<ReleaseInfo> parseReleaseInfoList(@NonNull JSONArray root) {
        ArrayList<ReleaseInfo> result = new ArrayList<>();
        for (int index = 0; index < root.length(); index++) {
            JSONObject item = root.optJSONObject(index);
            if (item == null) {
                continue;
            }
            if (item.optBoolean("draft", false) || item.optBoolean("prerelease", false)) {
                continue;
            }
            ReleaseInfo releaseInfo = parseReleaseInfo(item);
            if (TextUtils.isEmpty(releaseInfo.tagName)) {
                continue;
            }
            result.add(releaseInfo);
        }
        return result;
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

    @NonNull
    private static ChecksumInfo preferredChecksum(@Nullable String sha512Url, @Nullable String sha256Url) {
        if (!TextUtils.isEmpty(sha512Url)) {
            return new ChecksumInfo("SHA-512", sha512Url.trim());
        }
        if (!TextUtils.isEmpty(sha256Url)) {
            return new ChecksumInfo("SHA-256", sha256Url.trim());
        }
        return ChecksumInfo.NONE;
    }

    @NonNull
    private UpdatePlan buildUpdatePlan(
        @NonNull List<ReleaseInfo> releases,
        @NonNull String currentVersionName,
        long currentVersionCode
    ) {
        if (releases.isEmpty()) {
            return UpdatePlan.empty();
        }
        ReleaseInfo latestRelease = releases.get(0);
        int currentIndex = -1;
        for (int index = 0; index < releases.size(); index++) {
            if (isSameVersion(releases.get(index), currentVersionName, currentVersionCode)) {
                currentIndex = index;
                break;
            }
        }
        if (currentIndex <= 0) {
            return UpdatePlan.full(latestRelease);
        }
        if (currentIndex > MAX_PATCH_CHAIN_DEPTH) {
            return UpdatePlan.full(latestRelease);
        }
        ArrayList<PatchStep> patchSteps = new ArrayList<>();
        for (int index = currentIndex - 1; index >= 0; index--) {
            ReleaseInfo targetRelease = releases.get(index);
            if (
                !targetRelease.hasPatchAsset() || !targetRelease.hasPatchChecksum() || !targetRelease.hasApkChecksum()
            ) {
                return UpdatePlan.full(latestRelease);
            }
            patchSteps.add(new PatchStep(targetRelease));
        }
        return patchSteps.isEmpty() ? UpdatePlan.full(latestRelease) : UpdatePlan.patchChain(latestRelease, patchSteps);
    }

    private static boolean isSameVersion(
        @NonNull ReleaseInfo releaseInfo,
        @NonNull String currentVersionName,
        long currentVersionCode
    ) {
        if (releaseInfo.versionCode > 0L && currentVersionCode > 0L) {
            return releaseInfo.versionCode == currentVersionCode;
        }
        return compareVersions(releaseInfo.versionName, currentVersionName) == 0;
    }

    @NonNull
    private File downloadFullRelease(@NonNull ReleaseInfo releaseInfo) throws Exception {
        File tempFile = buildTempApkFile(releaseInfo);
        File targetFile = buildTargetApkFile(releaseInfo);
        deleteQuietly(tempFile);
        if (targetFile.getParentFile() != null) {
            targetFile.getParentFile().mkdirs();
        }
        long totalBytes = Math.max(MIN_CONTENT_LENGTH_BYTES, releaseInfo.apkAssetSize);
        long startedAt = System.currentTimeMillis();
        downloadAssetToFile(
            releaseInfo,
            releaseInfo.apkAssetUrl,
            releaseInfo.apkAssetSize,
            tempFile,
            totalBytes,
            0L,
            startedAt
        );
        verifyDownloadedFile(tempFile, new ChecksumInfo(releaseInfo.apkChecksumAlgorithm, releaseInfo.apkChecksumUrl));
        if (cancelRequested) {
            throw new DownloadCancelledException();
        }
        replaceWithTarget(tempFile, targetFile);
        return targetFile;
    }

    @NonNull
    private File downloadPatchedRelease(@NonNull UpdatePlan updatePlan) throws Exception {
        ReleaseInfo latestRelease = updatePlan.latestRelease;
        File targetFile = buildTargetApkFile(latestRelease);
        File installedApk = resolveInstalledApkFile();
        File currentBase = installedApk;
        ArrayList<File> temporaryFiles = new ArrayList<>();
        long totalBytes = Math.max(MIN_CONTENT_LENGTH_BYTES, updatePlan.totalPatchBytes());
        long startedAt = System.currentTimeMillis();
        long downloadedBytes = 0L;
        try {
            if (targetFile.getParentFile() != null) {
                targetFile.getParentFile().mkdirs();
            }
            for (PatchStep patchStep : updatePlan.patchSteps) {
                ReleaseInfo targetRelease = patchStep.targetRelease;
                File patchFile = buildTempPatchFile(targetRelease);
                File outputFile = buildTempPatchedApkFile(targetRelease);
                deleteQuietly(patchFile);
                deleteQuietly(outputFile);
                temporaryFiles.add(patchFile);
                temporaryFiles.add(outputFile);
                downloadedBytes = downloadAssetToFile(
                    latestRelease,
                    targetRelease.patchAssetUrl,
                    targetRelease.patchAssetSize,
                    patchFile,
                    totalBytes,
                    downloadedBytes,
                    startedAt
                );
                verifyDownloadedFile(
                    patchFile,
                    new ChecksumInfo(targetRelease.patchChecksumAlgorithm, targetRelease.patchChecksumUrl)
                );
                applyPatchFile(latestRelease, currentBase, patchFile, outputFile, targetRelease.apkAssetSize);
                verifyDownloadedFile(
                    outputFile,
                    new ChecksumInfo(targetRelease.apkChecksumAlgorithm, targetRelease.apkChecksumUrl)
                );
                if (cancelRequested) {
                    throw new DownloadCancelledException();
                }
                if (targetRelease.apkAssetSize > 0L && outputFile.length() != targetRelease.apkAssetSize) {
                    throw new IllegalStateException("Патч собрал APK с неверным размером");
                }
                currentBase = outputFile;
            }
            replaceWithTarget(currentBase, targetFile);
            return targetFile;
        } finally {
            for (File file : temporaryFiles) {
                if (file != null && !TextUtils.equals(file.getAbsolutePath(), targetFile.getAbsolutePath())) {
                    deleteQuietly(file);
                }
            }
        }
    }

    private void verifyDownloadedFile(@NonNull File file, @NonNull ChecksumInfo checksumInfo) throws Exception {
        if (checksumInfo.isEmpty()) {
            return;
        }
        String expectedChecksum = fetchChecksumValue(checksumInfo);
        if (TextUtils.isEmpty(expectedChecksum)) {
            throw new IllegalStateException("Не удалось получить checksum");
        }
        String actualChecksum = computeChecksum(file, checksumInfo.algorithm);
        if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
            throw new IllegalStateException("Checksum не совпал");
        }
    }

    @NonNull
    private String fetchChecksumValue(@NonNull ChecksumInfo checksumInfo) throws Exception {
        HttpURLConnection connection = openConnection(checksumInfo.url);
        activeConnection.set(connection);
        try {
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 400) {
                throw new IllegalStateException("GitHub checksum asset HTTP " + responseCode);
            }
            String body = readResponseBody(connection).trim();
            if (TextUtils.isEmpty(body)) {
                return "";
            }
            int firstSpace = body.indexOf(' ');
            return firstSpace > 0 ? body.substring(0, firstSpace).trim() : body;
        } finally {
            connection.disconnect();
            activeConnection.compareAndSet(connection, null);
        }
    }

    @NonNull
    private static String computeChecksum(@NonNull File file, @NonNull String algorithm) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance(algorithm);
        try (InputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read = inputStream.read(buffer);
            while (read != -1) {
                digest.update(buffer, 0, read);
                read = inputStream.read(buffer);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private long downloadAssetToFile(
        @NonNull ReleaseInfo progressReleaseInfo,
        @NonNull String assetUrl,
        long expectedBytes,
        @NonNull File outputFile,
        long totalBytes,
        long downloadedOffset,
        long startedAt
    ) throws Exception {
        HttpURLConnection connection = openConnection(assetUrl);
        activeConnection.set(connection);
        try {
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 400) {
                throw new IllegalStateException("GitHub release asset HTTP " + responseCode);
            }
            long connectionLength = connection.getContentLengthLong();
            long assetBytes = connectionLength >= MIN_CONTENT_LENGTH_BYTES ? connectionLength : expectedBytes;
            try (
                InputStream inputStream = connection.getInputStream();
                FileOutputStream outputStream = new FileOutputStream(outputFile)
            ) {
                byte[] buffer = new byte[16 * 1024];
                long fileDownloadedBytes = 0L;
                long lastPublishedAt = 0L;
                int read = inputStream.read(buffer);
                while (read != -1) {
                    if (cancelRequested) {
                        throw new DownloadCancelledException();
                    }
                    outputStream.write(buffer, 0, read);
                    fileDownloadedBytes += read;
                    long totalDownloadedBytes = downloadedOffset + fileDownloadedBytes;
                    long now = System.currentTimeMillis();
                    if (now - lastPublishedAt >= PROGRESS_UPDATE_INTERVAL_MS) {
                        publishDownloadProgress(progressReleaseInfo, totalDownloadedBytes, totalBytes, startedAt);
                        lastPublishedAt = now;
                    }
                    read = inputStream.read(buffer);
                }
                outputStream.flush();
                long finalDownloadedBytes = downloadedOffset + fileDownloadedBytes;
                publishDownloadProgress(progressReleaseInfo, finalDownloadedBytes, totalBytes, startedAt);
                if (assetBytes > 0L && fileDownloadedBytes != assetBytes) {
                    return finalDownloadedBytes;
                }
                return finalDownloadedBytes;
            }
        } finally {
            connection.disconnect();
            activeConnection.compareAndSet(connection, null);
        }
    }

    private void publishDownloadProgress(
        @NonNull ReleaseInfo releaseInfo,
        long downloadedBytes,
        long totalBytes,
        long startedAt
    ) {
        long safeTotalBytes = Math.max(MIN_CONTENT_LENGTH_BYTES, totalBytes);
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - startedAt);
        long speedBytesPerSecond = (downloadedBytes * 1000L) / elapsedMs;
        long remainingBytes = Math.max(0L, safeTotalBytes - downloadedBytes);
        int progressPercent = (int) Math.min(100L, (downloadedBytes * 100L) / safeTotalBytes);
        updateState(
            UpdateState.downloading(
                releaseInfo,
                downloadedBytes,
                safeTotalBytes,
                speedBytesPerSecond,
                remainingBytes,
                progressPercent
            )
        );
    }

    @NonNull
    private File resolveInstalledApkFile() {
        File installedApk = new File(appContext.getPackageCodePath());
        if (!installedApk.isFile() || installedApk.length() <= 0L) {
            throw new IllegalStateException("Не удалось найти установленный APK");
        }
        return installedApk;
    }

    private void applyPatchFile(
        @NonNull ReleaseInfo releaseInfo,
        @NonNull File baseFile,
        @NonNull File patchFile,
        @NonNull File outputFile,
        long expectedOutputBytes
    ) throws Exception {
        byte[] dictionary = Files.readAllBytes(baseFile.toPath());
        long total = Math.max(MIN_CONTENT_LENGTH_BYTES, expectedOutputBytes);
        updateState(UpdateState.patching(releaseInfo, total, 0));
        long writtenBytes = 0L;
        long lastReportedAtMs = 0L;
        try (
            InputStream patchInput = new FileInputStream(patchFile);
            ZstdInputStream zstdInput = openZstdPatchStream(patchInput, dictionary);
            FileOutputStream outputStream = new FileOutputStream(outputFile)
        ) {
            byte[] buffer = new byte[16 * 1024];
            int read = zstdInput.read(buffer);
            while (read != -1) {
                if (cancelRequested) {
                    throw new DownloadCancelledException();
                }
                outputStream.write(buffer, 0, read);
                writtenBytes += read;
                long now = System.currentTimeMillis();
                if (now - lastReportedAtMs >= 200L) {
                    int percent = (int) Math.min(99L, (writtenBytes * 100L) / total);
                    updateState(UpdateState.patching(releaseInfo, total, percent));
                    lastReportedAtMs = now;
                }
                read = zstdInput.read(buffer);
            }
            outputStream.flush();
        }
        updateState(UpdateState.patching(releaseInfo, total, 100));
    }

    @NonNull
    private static ZstdInputStream openZstdPatchStream(@NonNull InputStream input, @NonNull byte[] dictionary)
        throws Exception {
        try {
            return new ZstdInputStream(input).setDict(dictionary);
        } catch (LinkageError nativeUnavailable) {
            throw new IllegalStateException("Zstd native library unavailable on this device", nativeUnavailable);
        }
    }

    private static void replaceWithTarget(@NonNull File sourceFile, @NonNull File targetFile) {
        if (targetFile.exists() && !targetFile.delete()) {
            throw new IllegalStateException("Не удалось заменить старый APK");
        }
        if (!sourceFile.renameTo(targetFile)) {
            throw new IllegalStateException("Не удалось сохранить APK");
        }
    }

    @NonNull
    private File buildTempPatchFile(@NonNull ReleaseInfo releaseInfo) {
        File directory = new File(appContext.getCacheDir(), "updates");
        return new File(directory, "WINGSV-" + sanitizeFileComponent(releaseInfo.tagName) + ".patch.part");
    }

    @NonNull
    private File buildTempPatchedApkFile(@NonNull ReleaseInfo releaseInfo) {
        File directory = new File(appContext.getCacheDir(), "updates");
        return new File(directory, "WINGSV-" + sanitizeFileComponent(releaseInfo.tagName) + ".patched.apk.part");
    }

    @NonNull
    private static String buildPatchAssetName(@NonNull String versionName) {
        return PATCH_ASSET_PREFIX + versionName + PATCH_ASSET_SUFFIX;
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
        PATCHING,
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
        public final String patchAssetName;

        @NonNull
        public final String patchAssetUrl;

        public final long patchAssetSize;

        @NonNull
        public final String apkChecksumAlgorithm;

        @NonNull
        public final String apkChecksumUrl;

        @NonNull
        public final String patchChecksumAlgorithm;

        @NonNull
        public final String patchChecksumUrl;

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
            long apkAssetSize,
            @Nullable String patchAssetName,
            @Nullable String patchAssetUrl,
            long patchAssetSize,
            @Nullable String apkChecksumAlgorithm,
            @Nullable String apkChecksumUrl,
            @Nullable String patchChecksumAlgorithm,
            @Nullable String patchChecksumUrl
        ) {
            this.tagName = safe(tagName);
            this.releaseName = safe(releaseName);
            this.releaseUrl = safe(releaseUrl);
            this.releaseBody = safe(releaseBody);
            this.publishedAt = safe(publishedAt);
            this.apkAssetName = safe(apkAssetName);
            this.apkAssetUrl = safe(apkAssetUrl);
            this.apkAssetSize = Math.max(0L, apkAssetSize);
            this.patchAssetName = safe(patchAssetName);
            this.patchAssetUrl = safe(patchAssetUrl);
            this.patchAssetSize = Math.max(0L, patchAssetSize);
            this.apkChecksumAlgorithm = safe(apkChecksumAlgorithm);
            this.apkChecksumUrl = safe(apkChecksumUrl);
            this.patchChecksumAlgorithm = safe(patchChecksumAlgorithm);
            this.patchChecksumUrl = safe(patchChecksumUrl);
            this.versionName = normalizeVersionName(this.tagName);
            this.versionCode = deriveVersionCode(this.versionName);
        }

        public boolean hasInstallableAsset() {
            return !TextUtils.isEmpty(apkAssetUrl) && !TextUtils.isEmpty(apkAssetName);
        }

        public boolean hasPatchAsset() {
            return !TextUtils.isEmpty(patchAssetUrl) && !TextUtils.isEmpty(patchAssetName);
        }

        public boolean hasApkChecksum() {
            return !TextUtils.isEmpty(apkChecksumAlgorithm) && !TextUtils.isEmpty(apkChecksumUrl);
        }

        public boolean hasPatchChecksum() {
            return !TextUtils.isEmpty(patchChecksumAlgorithm) && !TextUtils.isEmpty(patchChecksumUrl);
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

    private static final class ChecksumInfo {

        @NonNull
        private static final ChecksumInfo NONE = new ChecksumInfo("", "");

        @NonNull
        private final String algorithm;

        @NonNull
        private final String url;

        private ChecksumInfo(@Nullable String algorithm, @Nullable String url) {
            this.algorithm = algorithm == null ? "" : algorithm.trim();
            this.url = url == null ? "" : url.trim();
        }

        private boolean isEmpty() {
            return TextUtils.isEmpty(algorithm) || TextUtils.isEmpty(url);
        }
    }

    private static final class PatchStep {

        @NonNull
        private final ReleaseInfo targetRelease;

        private PatchStep(@NonNull ReleaseInfo targetRelease) {
            this.targetRelease = targetRelease;
        }
    }

    private static final class UpdatePlan {

        @NonNull
        private final ReleaseInfo latestRelease;

        @NonNull
        private final List<PatchStep> patchSteps;

        private UpdatePlan(@NonNull ReleaseInfo latestRelease, @NonNull List<PatchStep> patchSteps) {
            this.latestRelease = latestRelease;
            this.patchSteps = patchSteps;
        }

        @NonNull
        private static UpdatePlan empty() {
            return new UpdatePlan(
                new ReleaseInfo("", "", "", "", "", "", "", 0L, "", "", 0L, "", "", "", ""),
                new ArrayList<>()
            );
        }

        @NonNull
        private static UpdatePlan full(@NonNull ReleaseInfo latestRelease) {
            return new UpdatePlan(latestRelease, new ArrayList<>());
        }

        @NonNull
        private static UpdatePlan patchChain(@NonNull ReleaseInfo latestRelease, @NonNull List<PatchStep> patchSteps) {
            return new UpdatePlan(latestRelease, patchSteps);
        }

        private boolean hasPatchChain() {
            return !patchSteps.isEmpty();
        }

        private boolean isUsable() {
            return latestRelease.hasInstallableAsset();
        }

        private long totalPatchBytes() {
            long totalBytes = 0L;
            for (PatchStep patchStep : patchSteps) {
                totalBytes += Math.max(MIN_CONTENT_LENGTH_BYTES, patchStep.targetRelease.patchAssetSize);
            }
            return totalBytes;
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

        static UpdateState patching(@NonNull ReleaseInfo releaseInfo, long totalBytes, int progressPercent) {
            return new UpdateState(
                Status.PATCHING,
                releaseInfo,
                null,
                totalBytes,
                totalBytes,
                0L,
                0L,
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
