package wings.v.core;

import android.text.TextUtils;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.ShortVariable",
        "PMD.OnlyOneReturn",
        "PMD.LawOfDemeter",
        "PMD.SimplifyBooleanReturns",
    }
)
public final class AmneziaProfile {

    public final String id;
    public final String title;
    public final String quickConfig;

    public AmneziaProfile(final String id, final String title, final String quickConfig) {
        this.id = TextUtils.isEmpty(id) ? UUID.randomUUID().toString() : id;
        this.title = emptyIfNull(title);
        this.quickConfig = emptyIfNull(quickConfig);
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(quickConfig.trim());
    }

    public String stableDedupKey() {
        return AmneziaStore.dedupKeyFromRawConfig(quickConfig);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("title", title);
        object.put("quick_config", quickConfig);
        return object;
    }

    public static AmneziaProfile fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new AmneziaProfile(object.optString("id"), object.optString("title"), object.optString("quick_config"));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AmneziaProfile)) {
            return false;
        }
        final AmneziaProfile profile = (AmneziaProfile) other;
        return (
            Objects.equals(id, profile.id) &&
            Objects.equals(title, profile.title) &&
            Objects.equals(quickConfig, profile.quickConfig)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, quickConfig);
    }

    private static String emptyIfNull(final String value) {
        return value == null ? "" : value;
    }
}
