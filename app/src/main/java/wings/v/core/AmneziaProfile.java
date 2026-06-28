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
    // Source subscription tag. Empty for manually added / imported profiles; set
    // when this profile was dispatched from a 3x-ui subscription wingsv:// link
    // (either standalone AmneziaWG or the AWG transport of a VK TURN profile). Not
    // part of stableDedupKey, so the same config can be reused across sources.
    public final String subscriptionId;
    public final String subscriptionTitle;

    public AmneziaProfile(final String id, final String title, final String quickConfig) {
        this(id, title, quickConfig, "", "");
    }

    public AmneziaProfile(
        final String id,
        final String title,
        final String quickConfig,
        final String subscriptionId,
        final String subscriptionTitle
    ) {
        this.id = TextUtils.isEmpty(id) ? UUID.randomUUID().toString() : id;
        this.title = emptyIfNull(title);
        this.quickConfig = emptyIfNull(quickConfig);
        this.subscriptionId = emptyIfNull(subscriptionId);
        this.subscriptionTitle = emptyIfNull(subscriptionTitle);
    }

    /**
     * Returns a copy tagged with the given source subscription, preserving all
     * other fields (including id) so the profile identity is unchanged.
     */
    public AmneziaProfile withSubscription(final String subscriptionId, final String subscriptionTitle) {
        return new AmneziaProfile(id, title, quickConfig, subscriptionId, subscriptionTitle);
    }

    public boolean isFromSubscription() {
        return !TextUtils.isEmpty(subscriptionId);
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
        object.put("subscription_id", subscriptionId);
        object.put("subscription_title", subscriptionTitle);
        return object;
    }

    public static AmneziaProfile fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new AmneziaProfile(
            object.optString("id"),
            object.optString("title"),
            object.optString("quick_config"),
            object.optString("subscription_id"),
            object.optString("subscription_title")
        );
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
            Objects.equals(quickConfig, profile.quickConfig) &&
            Objects.equals(subscriptionId, profile.subscriptionId) &&
            Objects.equals(subscriptionTitle, profile.subscriptionTitle)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, quickConfig, subscriptionId);
    }

    private static String emptyIfNull(final String value) {
        return value == null ? "" : value;
    }
}
