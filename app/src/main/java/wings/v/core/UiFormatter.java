package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.Formatter;

public final class UiFormatter {
    private UiFormatter() {
    }

    public static String formatBytes(Context context, long value) {
        return Formatter.formatFileSize(context, Math.max(0L, value));
    }

    public static String formatBytesPerSecond(Context context, long value) {
        return formatBytes(context, value) + "/s";
    }

    public static String truncate(String value, int maxLength) {
        if (TextUtils.isEmpty(value) || value.length() <= maxLength) {
            return value;
        }
        if (maxLength < 4) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 1) + "…";
    }
}
