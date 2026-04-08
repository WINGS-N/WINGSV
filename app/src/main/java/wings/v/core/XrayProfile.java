package wings.v.core;

import android.text.TextUtils;
import java.util.Locale;
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
public final class XrayProfile {

    public final String id;
    public final String title;
    public final String rawLink;
    public final String subscriptionId;
    public final String subscriptionTitle;
    public final String address;
    public final int port;

    public XrayProfile(
        final String id,
        final String title,
        final String rawLink,
        final String subscriptionId,
        final String subscriptionTitle,
        final String address,
        final int port
    ) {
        this.id = TextUtils.isEmpty(id) ? UUID.randomUUID().toString() : id;
        this.title = emptyIfNull(title);
        this.rawLink = emptyIfNull(rawLink);
        this.subscriptionId = emptyIfNull(subscriptionId);
        this.subscriptionTitle = emptyIfNull(subscriptionTitle);
        this.address = emptyIfNull(address);
        this.port = Math.max(port, 0);
    }

    public String stableDedupKey() {
        if (!TextUtils.isEmpty(rawLink)) {
            return rawLink.trim().toLowerCase(Locale.ROOT);
        }
        return (address + ":" + port + ":" + title).trim().toLowerCase(Locale.ROOT);
    }

    public boolean usesXtlsVisionFlow() {
        if (TextUtils.isEmpty(rawLink)) {
            return false;
        }
        return rawLink.trim().toLowerCase(Locale.ROOT).contains("flow=xtls-rprx-vision");
    }

    public boolean usesRealitySecurity() {
        if (TextUtils.isEmpty(rawLink)) {
            return false;
        }
        return rawLink.trim().toLowerCase(Locale.ROOT).contains("security=reality");
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("title", title);
        object.put("raw_link", rawLink);
        object.put("subscription_id", subscriptionId);
        object.put("subscription_title", subscriptionTitle);
        object.put("address", address);
        object.put("port", port);
        return object;
    }

    public static XrayProfile fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new XrayProfile(
            object.optString("id"),
            object.optString("title"),
            object.optString("raw_link"),
            object.optString("subscription_id"),
            object.optString("subscription_title"),
            object.optString("address"),
            object.optInt("port")
        );
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof XrayProfile)) {
            return false;
        }
        final XrayProfile profile = (XrayProfile) other;
        return (
            port == profile.port &&
            Objects.equals(id, profile.id) &&
            Objects.equals(title, profile.title) &&
            Objects.equals(rawLink, profile.rawLink) &&
            Objects.equals(subscriptionId, profile.subscriptionId) &&
            Objects.equals(subscriptionTitle, profile.subscriptionTitle) &&
            Objects.equals(address, profile.address)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, rawLink, subscriptionId, subscriptionTitle, address, port);
    }

    private static String emptyIfNull(final String value) {
        return value == null ? "" : value;
    }
}
