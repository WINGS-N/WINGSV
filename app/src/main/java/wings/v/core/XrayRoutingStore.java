package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import wings.v.xray.XrayBridge;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.LooseCoupling",
        "PMD.AvoidFileStream",
        "PMD.CouplingBetweenObjects",
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.GodClass",
        "PMD.TooManyMethods",
        "PMD.AtLeastOneConstructor",
        "PMD.UseConcurrentHashMap",
        "PMD.ShortVariable",
        "PMD.LongVariable",
        "PMD.UnnecessaryWarningSuppression",
    }
)
public final class XrayRoutingStore {

    public static final String DEFAULT_GEOSITE_URL =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat";
    public static final String DEFAULT_GEOIP_URL =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat";

    private static final String GEO_DIR = "xray/geo";
    private static final String GEOIP_NAME = "geoip";
    private static final String GEOSITE_NAME = "geosite";
    private static final int BUFFER_SIZE = 8192;
    private static final ExecutorService BOOTSTRAP_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BOOTSTRAP_SCHEDULED = new AtomicBoolean(false);

    private XrayRoutingStore() {}

    public static File getGeoDir(Context context) {
        File geoDir = new File(context.getFilesDir(), GEO_DIR);
        if (!geoDir.exists()) {
            geoDir.mkdirs();
        }
        return geoDir;
    }

    public static File getGeoDatFile(Context context, XrayRoutingRule.MatchType matchType) {
        return new File(getGeoDir(context), getBaseName(matchType) + ".dat");
    }

    public static File getGeoJsonFile(Context context, XrayRoutingRule.MatchType matchType) {
        return new File(getGeoDir(context), getBaseName(matchType) + ".json");
    }

    public static String getSourceUrl(Context context, XrayRoutingRule.MatchType matchType) {
        SharedPreferences preferences = AppPrefs.defaultSharedPreferences(context);
        String defaultValue = matchType == XrayRoutingRule.MatchType.GEOSITE ? DEFAULT_GEOSITE_URL : DEFAULT_GEOIP_URL;
        String key =
            matchType == XrayRoutingRule.MatchType.GEOSITE
                ? AppPrefs.KEY_XRAY_ROUTING_GEOSITE_URL
                : AppPrefs.KEY_XRAY_ROUTING_GEOIP_URL;
        return trim(preferences.getString(key, defaultValue));
    }

    public static void setSourceUrl(Context context, XrayRoutingRule.MatchType matchType, String value) {
        String key =
            matchType == XrayRoutingRule.MatchType.GEOSITE
                ? AppPrefs.KEY_XRAY_ROUTING_GEOSITE_URL
                : AppPrefs.KEY_XRAY_ROUTING_GEOIP_URL;
        String defaultValue = matchType == XrayRoutingRule.MatchType.GEOSITE ? DEFAULT_GEOSITE_URL : DEFAULT_GEOIP_URL;
        AppPrefs.defaultSharedPreferences(context)
            .edit()
            .putString(key, TextUtils.isEmpty(trim(value)) ? defaultValue : trim(value))
            .commit();
    }

    public static void ensureGeoFilesBootstrap(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = AppPrefs.defaultSharedPreferences(appContext);
        if (preferences.getBoolean(AppPrefs.KEY_XRAY_ROUTING_BOOTSTRAP_ATTEMPTED, false)) {
            return;
        }
        if (!BOOTSTRAP_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        preferences.edit().putBoolean(AppPrefs.KEY_XRAY_ROUTING_BOOTSTRAP_ATTEMPTED, true).apply();
        BOOTSTRAP_EXECUTOR.execute(() -> {
            tryDownloadGeoFileSilently(appContext, XrayRoutingRule.MatchType.GEOIP);
            tryDownloadGeoFileSilently(appContext, XrayRoutingRule.MatchType.GEOSITE);
        });
    }

    public static List<XrayRoutingRule> getRules(Context context) {
        ArrayList<XrayRoutingRule> rules = new ArrayList<>();
        JSONArray array = parseArray(
            AppPrefs.defaultSharedPreferences(context).getString(AppPrefs.KEY_XRAY_ROUTING_RULES_JSON, "[]")
        );
        if (array == null) {
            return rules;
        }
        for (int index = 0; index < array.length(); index++) {
            XrayRoutingRule rule = XrayRoutingRule.fromJson(array.optJSONObject(index));
            if (rule != null && !TextUtils.isEmpty(rule.code)) {
                rules.add(rule);
            }
        }
        return rules;
    }

    public static void setRules(Context context, List<XrayRoutingRule> rules) {
        JSONArray array = new JSONArray();
        if (rules != null) {
            for (XrayRoutingRule rule : rules) {
                if (rule == null || TextUtils.isEmpty(rule.code)) {
                    continue;
                }
                try {
                    array.put(rule.toJson());
                } catch (Exception ignored) {}
            }
        }
        AppPrefs.defaultSharedPreferences(context)
            .edit()
            .putString(AppPrefs.KEY_XRAY_ROUTING_RULES_JSON, array.toString())
            .commit();
    }

    public static List<XrayRoutingRule> getValidRules(Context context) {
        ArrayList<XrayRoutingRule> validRules = new ArrayList<>();
        for (XrayRoutingRule rule : getRules(context)) {
            if (validateRule(context, rule).valid) {
                validRules.add(rule);
            }
        }
        return validRules;
    }

    public static GeoFileInfo getGeoFileInfo(Context context, XrayRoutingRule.MatchType matchType) {
        File datFile = getGeoDatFile(context, matchType);
        GeoIndex index = loadGeoIndex(context, matchType);
        return new GeoFileInfo(
            datFile.exists(),
            datFile.length(),
            datFile.lastModified(),
            index.categoryCount,
            index.ruleCount,
            index.codes.size()
        );
    }

    public static void importGeoFile(Context context, XrayRoutingRule.MatchType matchType, InputStream inputStream)
        throws Exception {
        if (inputStream == null) {
            throw new IllegalArgumentException("Input stream is required");
        }
        replaceGeoFile(context, matchType, inputStream);
    }

    public static void downloadGeoFile(Context context, XrayRoutingRule.MatchType matchType, String url)
        throws Exception {
        URL requestUrl = new URL(trim(url));
        HttpURLConnection connection = DirectNetworkConnection.openHttpConnection(
            context.getApplicationContext(),
            requestUrl
        );
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", resolveUserAgent(context));
        connection.connect();
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            connection.disconnect();
            throw new IOException("HTTP " + responseCode);
        }
        try (InputStream inputStream = new BufferedInputStream(connection.getInputStream())) {
            replaceGeoFile(context, matchType, inputStream);
        } finally {
            connection.disconnect();
        }
    }

    public static ValidationResult validateRule(Context context, XrayRoutingRule rule) {
        if (rule == null) {
            return new ValidationResult(false, "Правило не задано");
        }
        if (!rule.enabled) {
            return new ValidationResult(true, "Правило выключено");
        }
        String normalizedCode = XrayRoutingRule.normalizeCode(rule.code, rule.matchType);
        if (TextUtils.isEmpty(normalizedCode)) {
            return new ValidationResult(false, "Значение правила не задано");
        }
        if (!rule.matchType.isGeo()) {
            return validateSimpleRule(rule.matchType, normalizedCode);
        }
        File datFile = getGeoDatFile(context, rule.matchType);
        if (!datFile.exists()) {
            return new ValidationResult(false, "Файл " + datFile.getName() + " не найден");
        }
        GeoIndex index = loadGeoIndex(context, rule.matchType);
        if (index.codes.isEmpty()) {
            return new ValidationResult(false, "Не удалось прочитать " + datFile.getName());
        }
        if (!index.codes.contains(normalizedCode)) {
            return new ValidationResult(false, "Код не найден в " + datFile.getName());
        }
        return new ValidationResult(true, datFile.getName());
    }

    private static ValidationResult validateSimpleRule(XrayRoutingRule.MatchType matchType, String value) {
        if (matchType == XrayRoutingRule.MatchType.PORT) {
            return validatePortRule(value);
        }
        if (!hasListValue(value)) {
            return new ValidationResult(false, "Значение правила не задано");
        }
        return new ValidationResult(true, matchType.value);
    }

    private static ValidationResult validatePortRule(String value) {
        boolean hasValue = false;
        for (String token : value.split("[,\\s]+")) {
            String normalized = trim(token);
            if (TextUtils.isEmpty(normalized)) {
                continue;
            }
            hasValue = true;
            if (!isValidPortToken(normalized)) {
                return new ValidationResult(false, "Порт должен быть 1-65535 или диапазоном");
            }
        }
        return hasValue
            ? new ValidationResult(true, XrayRoutingRule.MatchType.PORT.value)
            : new ValidationResult(false, "Порт не задан");
    }

    private static boolean isValidPortToken(String token) {
        int separator = token.indexOf('-');
        if (separator < 0) {
            return isValidPortNumber(token);
        }
        if (separator == 0 || separator == token.length() - 1 || token.indexOf('-', separator + 1) >= 0) {
            return false;
        }
        int start = parsePort(token.substring(0, separator));
        int end = parsePort(token.substring(separator + 1));
        return start > 0 && end > 0 && start <= end;
    }

    private static boolean isValidPortNumber(String value) {
        return parsePort(value) > 0;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(trim(value));
            return port >= 1 && port <= 65535 ? port : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static boolean hasListValue(String value) {
        for (String token : value.split("[,\\s]+")) {
            if (!TextUtils.isEmpty(trim(token))) {
                return true;
            }
        }
        return false;
    }

    private static void tryDownloadGeoFileSilently(Context context, XrayRoutingRule.MatchType matchType) {
        if (getGeoDatFile(context, matchType).exists()) {
            return;
        }
        try {
            downloadGeoFile(context, matchType, getSourceUrl(context, matchType));
        } catch (Exception ignored) {}
    }

    private static GeoIndex loadGeoIndex(Context context, XrayRoutingRule.MatchType matchType) {
        File datFile = getGeoDatFile(context, matchType);
        File jsonFile = getGeoJsonFile(context, matchType);
        if (!datFile.exists()) {
            return GeoIndex.empty();
        }
        if (!jsonFile.exists() || jsonFile.lastModified() < datFile.lastModified()) {
            try {
                XrayBridge.countGeoData(context.getApplicationContext(), getBaseName(matchType), getGeoType(matchType));
            } catch (Exception ignored) {
                return GeoIndex.empty();
            }
        }
        try {
            return parseGeoIndex(readFile(jsonFile));
        } catch (Exception ignored) {
            return GeoIndex.empty();
        }
    }

    private static GeoIndex parseGeoIndex(String json) throws Exception {
        JSONObject object = new JSONObject(json);
        JSONArray codesArray = object.optJSONArray("codes");
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (codesArray != null) {
            for (int index = 0; index < codesArray.length(); index++) {
                JSONObject codeObject = codesArray.optJSONObject(index);
                String code = trim(codeObject != null ? codeObject.optString("code") : "").toLowerCase(Locale.ROOT);
                if (!TextUtils.isEmpty(code)) {
                    codes.add(code);
                }
            }
        }
        return new GeoIndex(codes, object.optInt("categoryCount"), object.optInt("ruleCount"));
    }

    private static void replaceGeoFile(Context context, XrayRoutingRule.MatchType matchType, InputStream source)
        throws Exception {
        File geoDir = getGeoDir(context);
        File workDir = new File(geoDir, ".tmp-" + getBaseName(matchType) + "-" + System.currentTimeMillis());
        if (!workDir.exists()) {
            workDir.mkdirs();
        }
        File tempDat = new File(workDir, getBaseName(matchType) + ".dat");
        File tempJson = new File(workDir, getBaseName(matchType) + ".json");
        try {
            writeStreamToFile(source, tempDat);
            XrayBridge.countGeoData(workDir, getBaseName(matchType), getGeoType(matchType));
            replaceFile(tempDat, getGeoDatFile(context, matchType));
            replaceFile(tempJson, getGeoJsonFile(context, matchType));
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static void replaceFile(File source, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (target.exists()) {
            target.delete();
        }
        try (
            FileInputStream inputStream = new FileInputStream(source);
            FileOutputStream outputStream = new FileOutputStream(target, false)
        ) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read = inputStream.read(buffer);
            while (read >= 0) {
                if (read == 0) {
                    read = inputStream.read(buffer);
                    continue;
                }
                outputStream.write(buffer, 0, read);
                read = inputStream.read(buffer);
            }
        }
    }

    private static void writeStreamToFile(InputStream source, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (
            BufferedInputStream inputStream = new BufferedInputStream(source);
            BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(target, false))
        ) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read = inputStream.read(buffer);
            while (read >= 0) {
                if (read == 0) {
                    read = inputStream.read(buffer);
                    continue;
                }
                outputStream.write(buffer, 0, read);
                read = inputStream.read(buffer);
            }
        }
    }

    private static String readFile(File file) throws Exception {
        try (
            FileInputStream inputStream = new FileInputStream(file);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read = inputStream.read(buffer);
            while (read >= 0) {
                if (read == 0) {
                    read = inputStream.read(buffer);
                    continue;
                }
                outputStream.write(buffer, 0, read);
                read = inputStream.read(buffer);
            }
            return outputStream.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }

    private static JSONArray parseArray(String raw) {
        try {
            return new JSONArray(TextUtils.isEmpty(raw) ? "[]" : raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String getBaseName(XrayRoutingRule.MatchType matchType) {
        return matchType == XrayRoutingRule.MatchType.GEOSITE ? GEOSITE_NAME : GEOIP_NAME;
    }

    private static String getGeoType(XrayRoutingRule.MatchType matchType) {
        return matchType == XrayRoutingRule.MatchType.GEOSITE ? "domain" : "ip";
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static String resolveUserAgent(Context context) {
        try {
            String versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            if (!TextUtils.isEmpty(versionName)) {
                return "WINGSV/" + versionName;
            }
        } catch (Exception ignored) {}
        return "WINGSV";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class ValidationResult {

        public final boolean valid;
        public final String message;

        public ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = TextUtils.isEmpty(message) ? "" : message;
        }
    }

    public static final class GeoFileInfo {

        public final boolean exists;
        public final long sizeBytes;
        public final long updatedAt;
        public final int categoryCount;
        public final int ruleCount;
        public final int codeCount;

        public GeoFileInfo(
            boolean exists,
            long sizeBytes,
            long updatedAt,
            int categoryCount,
            int ruleCount,
            int codeCount
        ) {
            this.exists = exists;
            this.sizeBytes = sizeBytes;
            this.updatedAt = updatedAt;
            this.categoryCount = categoryCount;
            this.ruleCount = ruleCount;
            this.codeCount = codeCount;
        }
    }

    private static final class GeoIndex {

        final Set<String> codes;
        final int categoryCount;
        final int ruleCount;

        GeoIndex(Set<String> codes, int categoryCount, int ruleCount) {
            this.codes = codes;
            this.categoryCount = categoryCount;
            this.ruleCount = ruleCount;
        }

        static GeoIndex empty() {
            return new GeoIndex(new LinkedHashSet<>(), 0, 0);
        }
    }
}
