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
        "PMD.OnlyOneReturn",
        "PMD.LawOfDemeter",
        "PMD.AtLeastOneConstructor",
        "PMD.ShortVariable",
        "PMD.LongVariable",
        "PMD.CommentDefaultAccessModifier",
        "PMD.UnnecessaryWarningSuppression",
    }
)
public final class XrayRoutingRule {

    public enum MatchType {
        GEOIP("geoip"),
        GEOSITE("geosite"),
        DOMAIN("domain"),
        IP("ip"),
        PORT("port");

        public final String value;

        MatchType(String value) {
            this.value = value;
        }

        public static MatchType fromValue(String value) {
            if ("geosite".equalsIgnoreCase(value)) {
                return GEOSITE;
            }
            if ("domain".equalsIgnoreCase(value)) {
                return DOMAIN;
            }
            if ("ip".equalsIgnoreCase(value)) {
                return IP;
            }
            if ("port".equalsIgnoreCase(value)) {
                return PORT;
            }
            return GEOIP;
        }

        public boolean isGeo() {
            return this == GEOIP || this == GEOSITE;
        }
    }

    public enum Action {
        DIRECT("direct"),
        PROXY("proxy"),
        BLOCK("block");

        public final String value;

        Action(String value) {
            this.value = value;
        }

        public static Action fromValue(String value) {
            if ("direct".equalsIgnoreCase(value)) {
                return DIRECT;
            }
            if ("block".equalsIgnoreCase(value)) {
                return BLOCK;
            }
            return PROXY;
        }
    }

    public final String id;
    public final MatchType matchType;
    public final String code;
    public final Action action;
    public final boolean enabled;

    public XrayRoutingRule(String id, MatchType matchType, String code, Action action, boolean enabled) {
        this.id = TextUtils.isEmpty(id) ? UUID.randomUUID().toString() : id;
        this.matchType = matchType == null ? MatchType.GEOIP : matchType;
        this.code = normalizeCode(code, this.matchType);
        this.action = action == null ? Action.PROXY : action;
        this.enabled = enabled;
    }

    public String prefixedCode() {
        return matchType.value + ":" + code;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("match_type", matchType.value);
        object.put("code", code);
        object.put("action", action.value);
        object.put("enabled", enabled);
        return object;
    }

    public static XrayRoutingRule fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new XrayRoutingRule(
            object.optString("id"),
            MatchType.fromValue(object.optString("match_type")),
            object.optString("code"),
            Action.fromValue(object.optString("action")),
            object.optBoolean("enabled", true)
        );
    }

    public static String normalizeCode(String code, MatchType matchType) {
        String normalized = code == null ? "" : code.trim();
        MatchType normalizedMatchType = matchType == null ? MatchType.GEOIP : matchType;
        normalized = stripOwnPrefix(normalized, normalizedMatchType).trim();
        if (normalizedMatchType == MatchType.PORT) {
            return normalized.replaceAll("\\s*-\\s*", "-");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String stripOwnPrefix(String code, MatchType matchType) {
        String prefix = matchType.value + ":";
        if (code.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return code.substring(prefix.length());
        }
        return code;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof XrayRoutingRule)) {
            return false;
        }
        XrayRoutingRule that = (XrayRoutingRule) other;
        return (
            enabled == that.enabled &&
            Objects.equals(id, that.id) &&
            matchType == that.matchType &&
            Objects.equals(code, that.code) &&
            action == that.action
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, matchType, code, action, enabled);
    }
}
