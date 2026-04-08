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
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.ShortVariable",
        "PMD.LongVariable",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.UselessParentheses",
    }
)
public final class XraySubscription {

    public final String id;
    public final String title;
    public final String url;
    public final String formatHint;
    public final int refreshIntervalHours;
    public final boolean autoUpdate;
    public final long lastUpdatedAt;

    public XraySubscription(
        String id,
        String title,
        String url,
        String formatHint,
        int refreshIntervalHours,
        boolean autoUpdate,
        long lastUpdatedAt
    ) {
        this.id = TextUtils.isEmpty(id) ? UUID.randomUUID().toString() : id;
        this.title = emptyIfNull(title);
        this.url = emptyIfNull(url);
        this.formatHint = emptyIfNull(formatHint);
        this.refreshIntervalHours = Math.max(refreshIntervalHours, 0);
        this.autoUpdate = autoUpdate;
        this.lastUpdatedAt = Math.max(lastUpdatedAt, 0L);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("title", title);
        object.put("url", url);
        object.put("format_hint", formatHint);
        object.put("refresh_interval_hours", refreshIntervalHours);
        object.put("auto_update", autoUpdate);
        object.put("last_updated_at", lastUpdatedAt);
        return object;
    }

    public static XraySubscription fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        return new XraySubscription(
            object.optString("id"),
            object.optString("title"),
            object.optString("url"),
            object.optString("format_hint"),
            object.optInt("refresh_interval_hours"),
            object.optBoolean("auto_update"),
            object.optLong("last_updated_at")
        );
    }

    public String stableDedupKey() {
        return url.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof XraySubscription)) {
            return false;
        }
        XraySubscription that = (XraySubscription) other;
        return (
            refreshIntervalHours == that.refreshIntervalHours &&
            autoUpdate == that.autoUpdate &&
            lastUpdatedAt == that.lastUpdatedAt &&
            Objects.equals(id, that.id) &&
            Objects.equals(title, that.title) &&
            Objects.equals(url, that.url) &&
            Objects.equals(formatHint, that.formatHint)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, url, formatHint, refreshIntervalHours, autoUpdate, lastUpdatedAt);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
