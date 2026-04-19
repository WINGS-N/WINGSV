package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings(
    {
        "PMD.AvoidCatchingGenericException",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public final class RuStoreRecommendedAppsAsset {

    private static final String ASSET_FILE_NAME = "rustore_recommended_apps.json";
    private static final Object LOCK = new Object();

    @Nullable
    private static volatile Map<String, RecommendedAppInfo> cachedApps;

    private RuStoreRecommendedAppsAsset() {}

    public static Map<String, RecommendedAppInfo> getApps(Context context) {
        Map<String, RecommendedAppInfo> localCache = cachedApps;
        if (localCache != null) {
            return localCache;
        }
        synchronized (LOCK) {
            localCache = cachedApps;
            if (localCache == null) {
                localCache = Collections.unmodifiableMap(loadApps(context.getApplicationContext()));
                cachedApps = localCache;
            }
            return localCache;
        }
    }

    public static Set<String> getPackageNames(Context context) {
        return new LinkedHashSet<>(getApps(context).keySet());
    }

    private static Map<String, RecommendedAppInfo> loadApps(Context context) {
        LinkedHashMap<String, RecommendedAppInfo> result = new LinkedHashMap<>();
        try (InputStream inputStream = context.getAssets().open(ASSET_FILE_NAME)) {
            JSONObject root = new JSONObject(readText(inputStream));
            JSONArray packagesArray = root.optJSONArray("packages");
            if (packagesArray == null) {
                return result;
            }
            for (int index = 0; index < packagesArray.length(); index++) {
                JSONObject object = packagesArray.optJSONObject(index);
                if (object == null) {
                    continue;
                }
                String packageName = trim(object.optString("package_name", ""));
                if (TextUtils.isEmpty(packageName)) {
                    continue;
                }
                result.put(
                    packageName,
                    new RecommendedAppInfo(
                        packageName,
                        nullableText(object, "app_name"),
                        nullableText(object, "developer_name"),
                        nullableText(object, "developer_path")
                    )
                );
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String readText(InputStream inputStream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read = inputStream.read(buffer);
        while (read != -1) {
            output.write(buffer, 0, read);
            read = inputStream.read(buffer);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    @Nullable
    private static String nullableText(JSONObject object, String key) {
        Object rawValue = object.opt(key);
        if (rawValue == null || rawValue == JSONObject.NULL) {
            return null;
        }
        String normalized = trim(String.valueOf(rawValue));
        return TextUtils.isEmpty(normalized) ? null : normalized;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class RecommendedAppInfo {

        public final String packageName;

        @Nullable
        public final String appName;

        @Nullable
        public final String developerName;

        @Nullable
        public final String developerPath;

        public RecommendedAppInfo(
            String packageName,
            @Nullable String appName,
            @Nullable String developerName,
            @Nullable String developerPath
        ) {
            this.packageName = packageName;
            this.appName = appName;
            this.developerName = developerName;
            this.developerPath = developerPath;
        }
    }
}
