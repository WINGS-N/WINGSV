package wings.v.core;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;
import java.util.UUID;

public final class XrayProfile {
    public final String id;
    public final String title;
    public final String rawLink;
    public final String subscriptionId;
    public final String subscriptionTitle;
    public final String address;
    public final int port;

    public XrayProfile(String id,
                       String title,
                       String rawLink,
                       String subscriptionId,
                       String subscriptionTitle,
                       String address,
                       int port) {
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
            return rawLink.trim().toLowerCase();
        }
        return (address + ":" + port + ":" + title).trim().toLowerCase();
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
        XrayProfile profile = (XrayProfile) other;
        return port == profile.port
                && Objects.equals(id, profile.id)
                && Objects.equals(title, profile.title)
                && Objects.equals(rawLink, profile.rawLink)
                && Objects.equals(subscriptionId, profile.subscriptionId)
                && Objects.equals(subscriptionTitle, profile.subscriptionTitle)
                && Objects.equals(address, profile.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, rawLink, subscriptionId, subscriptionTitle, address, port);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
