package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import wings.v.R;

@SuppressWarnings(
    {
        "PMD.AvoidFileStream",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.LooseCoupling",
        "PMD.ShortVariable",
        "PMD.AvoidInstantiatingObjectsInLoops",
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.AvoidBranchingStatementAsLastInLoop",
        "PMD.UnnecessaryFullyQualifiedName",
        "PMD.GodClass",
        "PMD.TooManyMethods",
    }
)
public final class ByeDpiDomainListStore {

    private static final String FILE_NAME = "byedpi_domain_lists.json";

    private ByeDpiDomainListStore() {}

    @NonNull
    public static List<ByeDpiDomainList> getLists(@Nullable Context context) {
        ArrayList<ByeDpiDomainList> result = new ArrayList<>();
        if (context == null) {
            return result;
        }
        ensureDefaultLists(context);
        File file = listsFile(context);
        if (!file.exists()) {
            return result;
        }
        try {
            String rawJson = readUtf8(file);
            JSONArray array = new JSONArray(rawJson);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) {
                    continue;
                }
                String id = trim(object.optString("id"));
                String name = trim(object.optString("name"));
                JSONArray domainsArray = object.optJSONArray("domains");
                ArrayList<String> domains = new ArrayList<>();
                if (domainsArray != null) {
                    for (int domainIndex = 0; domainIndex < domainsArray.length(); domainIndex++) {
                        String domain = trim(domainsArray.optString(domainIndex));
                        if (!TextUtils.isEmpty(domain)) {
                            domains.add(domain);
                        }
                    }
                }
                if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name) || domains.isEmpty()) {
                    continue;
                }
                result.add(
                    new ByeDpiDomainList(
                        id,
                        name,
                        domains,
                        object.optBoolean("isActive", false),
                        object.optBoolean("isBuiltIn", false)
                    )
                );
            }
        } catch (IOException | JSONException ignored) {}
        Collections.sort(result, (left, right) ->
            left.name.toLowerCase(Locale.US).compareTo(right.name.toLowerCase(Locale.US))
        );
        return result;
    }

    public static void saveLists(@Nullable Context context, @Nullable List<ByeDpiDomainList> lists) {
        if (context == null) {
            return;
        }
        JSONArray array = new JSONArray();
        if (lists != null) {
            for (ByeDpiDomainList item : lists) {
                if (item == null || TextUtils.isEmpty(trim(item.id)) || TextUtils.isEmpty(trim(item.name))) {
                    continue;
                }
                JSONArray domains = new JSONArray();
                for (String domain : item.domains) {
                    String normalized = ByeDpiStore.normalizeTarget(domain);
                    if (!TextUtils.isEmpty(normalized)) {
                        domains.put(normalized);
                    }
                }
                if (domains.length() == 0) {
                    continue;
                }
                try {
                    JSONObject object = new JSONObject();
                    object.put("id", item.id);
                    object.put("name", item.name);
                    object.put("domains", domains);
                    object.put("isActive", item.isActive);
                    object.put("isBuiltIn", item.isBuiltIn);
                    array.put(object);
                } catch (JSONException ignored) {}
            }
        }
        try {
            writeUtf8(listsFile(context), array.toString());
        } catch (IOException ignored) {}
    }

    @NonNull
    public static List<String> getActiveDomains(@Nullable Context context) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (ByeDpiDomainList item : getLists(context)) {
            if (!item.isActive) {
                continue;
            }
            for (String domain : item.domains) {
                String normalized = ByeDpiStore.normalizeTarget(domain);
                if (!TextUtils.isEmpty(normalized)) {
                    unique.add(normalized);
                }
            }
        }
        return new ArrayList<>(unique);
    }

    @NonNull
    public static String buildActiveListsSummary(@Nullable Context context) {
        if (context == null) {
            return "";
        }
        ArrayList<String> names = new ArrayList<>();
        for (ByeDpiDomainList item : getLists(context)) {
            if (item.isActive) {
                names.add(item.name);
            }
        }
        if (names.isEmpty()) {
            return context.getString(R.string.domain_lists_summary);
        }
        return TextUtils.join(", ", names);
    }

    public static boolean addList(@Nullable Context context, @Nullable String name, @Nullable List<String> domains) {
        if (context == null) {
            return false;
        }
        String normalizedName = trim(name);
        ArrayList<String> normalizedDomains = normalizeDomains(domains);
        if (TextUtils.isEmpty(normalizedName) || normalizedDomains.isEmpty()) {
            return false;
        }
        ArrayList<ByeDpiDomainList> lists = new ArrayList<>(getLists(context));
        String id = normalizedName.toLowerCase(Locale.US).replace(' ', '_');
        for (ByeDpiDomainList item : lists) {
            if (TextUtils.equals(item.id, id)) {
                return false;
            }
        }
        lists.add(new ByeDpiDomainList(id, normalizedName, normalizedDomains, true, false));
        saveLists(context, lists);
        return true;
    }

    public static boolean updateList(
        @Nullable Context context,
        @Nullable String id,
        @Nullable String name,
        @Nullable List<String> domains
    ) {
        if (context == null) {
            return false;
        }
        String normalizedId = trim(id);
        String normalizedName = trim(name);
        ArrayList<String> normalizedDomains = normalizeDomains(domains);
        if (TextUtils.isEmpty(normalizedId) || TextUtils.isEmpty(normalizedName) || normalizedDomains.isEmpty()) {
            return false;
        }
        ArrayList<ByeDpiDomainList> lists = new ArrayList<>(getLists(context));
        for (int index = 0; index < lists.size(); index++) {
            ByeDpiDomainList item = lists.get(index);
            if (!TextUtils.equals(item.id, normalizedId)) {
                continue;
            }
            lists.set(
                index,
                new ByeDpiDomainList(item.id, normalizedName, normalizedDomains, item.isActive, item.isBuiltIn)
            );
            saveLists(context, lists);
            return true;
        }
        return false;
    }

    public static boolean toggleListActive(@Nullable Context context, @Nullable String id) {
        if (context == null) {
            return false;
        }
        String normalizedId = trim(id);
        ArrayList<ByeDpiDomainList> lists = new ArrayList<>(getLists(context));
        for (int index = 0; index < lists.size(); index++) {
            ByeDpiDomainList item = lists.get(index);
            if (!TextUtils.equals(item.id, normalizedId)) {
                continue;
            }
            lists.set(index, new ByeDpiDomainList(item.id, item.name, item.domains, !item.isActive, item.isBuiltIn));
            saveLists(context, lists);
            return true;
        }
        return false;
    }

    public static boolean deleteList(@Nullable Context context, @Nullable String id) {
        if (context == null) {
            return false;
        }
        String normalizedId = trim(id);
        ArrayList<ByeDpiDomainList> lists = new ArrayList<>(getLists(context));
        boolean removed = lists.removeIf(item -> TextUtils.equals(item.id, normalizedId));
        if (removed) {
            saveLists(context, lists);
        }
        return removed;
    }

    private static void ensureDefaultLists(@NonNull Context context) {
        File file = listsFile(context);
        if (file.exists()) {
            return;
        }
        ArrayList<ByeDpiDomainList> lists = new ArrayList<>();
        try {
            String[] assets = context.getAssets().list("");
            if (assets == null) {
                assets = new String[0];
            }
            for (String asset : assets) {
                if (!asset.startsWith("proxytest_") || !asset.endsWith(".sites")) {
                    continue;
                }
                String id = asset.substring("proxytest_".length(), asset.length() - ".sites".length());
                ArrayList<String> domains = new ArrayList<>();
                try (
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(context.getAssets().open(asset), StandardCharsets.UTF_8)
                    )
                ) {
                    String line;
                    line = reader.readLine();
                    while (line != null) {
                        String normalized = ByeDpiStore.normalizeTarget(line);
                        if (!TextUtils.isEmpty(normalized)) {
                            domains.add(normalized);
                        }
                        line = reader.readLine();
                    }
                }
                if (domains.isEmpty()) {
                    continue;
                }
                String name = defaultDisplayName(id);
                boolean isActive = TextUtils.equals(id, "youtube") || TextUtils.equals(id, "googlevideo");
                lists.add(new ByeDpiDomainList(id, name, domains, isActive, true));
            }
        } catch (IOException ignored) {}
        saveLists(context, lists);
    }

    @NonNull
    private static ArrayList<String> normalizeDomains(@Nullable List<String> domains) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (domains != null) {
            for (String domain : domains) {
                String normalized = ByeDpiStore.normalizeTarget(domain);
                if (!TextUtils.isEmpty(normalized)) {
                    unique.add(normalized);
                }
            }
        }
        return new ArrayList<>(unique);
    }

    @NonNull
    private static File listsFile(@NonNull Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    @NonNull
    private static String readUtf8(@NonNull File file) throws java.io.IOException {
        try (
            FileInputStream input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int read;
            read = input.read(buffer);
            while (read >= 0) {
                output.write(buffer, 0, read);
                read = input.read(buffer);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeUtf8(@NonNull File file, @NonNull String value) throws java.io.IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NonNull
    private static String defaultDisplayName(@NonNull String id) {
        switch (id) {
            case "cloudflare":
                return "Cloudflare";
            case "discord":
                return "Discord";
            case "general":
                return "General";
            case "googlevideo":
                return "GoogleVideo";
            case "social":
                return "Social";
            case "youtube":
                return "YouTube";
            default:
                return id.substring(0, 1).toUpperCase(Locale.US) + id.substring(1);
        }
    }

    @NonNull
    private static String trim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
