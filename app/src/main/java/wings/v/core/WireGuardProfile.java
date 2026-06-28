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
public final class WireGuardProfile {

    public final String id;
    public final String title;
    public final String privateKey;
    public final String addresses;
    public final String dns;
    public final int mtu;
    public final String publicKey;
    public final String presharedKey;
    public final String allowedIps;
    public final String endpoint;
    // Source subscription tag. Empty for manually added / imported profiles; set
    // when this profile was dispatched from a 3x-ui subscription wingsv:// link
    // (either standalone WireGuard or the WG transport of a VK TURN profile). Not
    // part of stableDedupKey, so the same server can be reused across sources.
    public final String subscriptionId;
    public final String subscriptionTitle;

    public WireGuardProfile(
        final String id,
        final String title,
        final String privateKey,
        final String addresses,
        final String dns,
        final int mtu,
        final String publicKey,
        final String presharedKey,
        final String allowedIps,
        final String endpoint
    ) {
        this(id, title, privateKey, addresses, dns, mtu, publicKey, presharedKey, allowedIps, endpoint, "", "");
    }

    public WireGuardProfile(
        final String id,
        final String title,
        final String privateKey,
        final String addresses,
        final String dns,
        final int mtu,
        final String publicKey,
        final String presharedKey,
        final String allowedIps,
        final String endpoint,
        final String subscriptionId,
        final String subscriptionTitle
    ) {
        this.id = TextUtils.isEmpty(id) ? UUID.randomUUID().toString() : id;
        this.title = emptyIfNull(title);
        this.privateKey = emptyIfNull(privateKey);
        this.addresses = emptyIfNull(addresses);
        this.dns = emptyIfNull(dns);
        this.mtu = Math.max(mtu, 0);
        this.publicKey = emptyIfNull(publicKey);
        this.presharedKey = emptyIfNull(presharedKey);
        this.allowedIps = emptyIfNull(allowedIps);
        this.endpoint = emptyIfNull(endpoint);
        this.subscriptionId = emptyIfNull(subscriptionId);
        this.subscriptionTitle = emptyIfNull(subscriptionTitle);
    }

    /**
     * Returns a copy tagged with the given source subscription, preserving all
     * other fields (including id) so the profile identity is unchanged.
     */
    public WireGuardProfile withSubscription(final String subscriptionId, final String subscriptionTitle) {
        return new WireGuardProfile(
            id,
            title,
            privateKey,
            addresses,
            dns,
            mtu,
            publicKey,
            presharedKey,
            allowedIps,
            endpoint,
            subscriptionId,
            subscriptionTitle
        );
    }

    public boolean isFromSubscription() {
        return !TextUtils.isEmpty(subscriptionId);
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(privateKey) && TextUtils.isEmpty(publicKey) && TextUtils.isEmpty(endpoint);
    }

    public String stableDedupKey() {
        return (publicKey + "|" + endpoint).trim().toLowerCase(Locale.ROOT);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("title", title);
        object.put("private_key", privateKey);
        object.put("addresses", addresses);
        object.put("dns", dns);
        object.put("mtu", mtu);
        object.put("public_key", publicKey);
        object.put("preshared_key", presharedKey);
        object.put("allowed_ips", allowedIps);
        object.put("endpoint", endpoint);
        object.put("subscription_id", subscriptionId);
        object.put("subscription_title", subscriptionTitle);
        return object;
    }

    public static WireGuardProfile fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new WireGuardProfile(
            object.optString("id"),
            object.optString("title"),
            object.optString("private_key"),
            object.optString("addresses"),
            object.optString("dns"),
            object.optInt("mtu"),
            object.optString("public_key"),
            object.optString("preshared_key"),
            object.optString("allowed_ips"),
            object.optString("endpoint"),
            object.optString("subscription_id"),
            object.optString("subscription_title")
        );
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WireGuardProfile)) {
            return false;
        }
        final WireGuardProfile profile = (WireGuardProfile) other;
        return (
            mtu == profile.mtu &&
            Objects.equals(id, profile.id) &&
            Objects.equals(title, profile.title) &&
            Objects.equals(privateKey, profile.privateKey) &&
            Objects.equals(addresses, profile.addresses) &&
            Objects.equals(dns, profile.dns) &&
            Objects.equals(publicKey, profile.publicKey) &&
            Objects.equals(presharedKey, profile.presharedKey) &&
            Objects.equals(allowedIps, profile.allowedIps) &&
            Objects.equals(endpoint, profile.endpoint) &&
            Objects.equals(subscriptionId, profile.subscriptionId) &&
            Objects.equals(subscriptionTitle, profile.subscriptionTitle)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            id,
            title,
            privateKey,
            addresses,
            dns,
            mtu,
            publicKey,
            presharedKey,
            allowedIps,
            endpoint,
            subscriptionId
        );
    }

    private static String emptyIfNull(final String value) {
        return value == null ? "" : value;
    }
}
