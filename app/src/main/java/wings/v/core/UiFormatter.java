package wings.v.core;

import android.content.Context;
import android.text.TextUtils;

import java.util.Locale;

public final class UiFormatter {
    private static final String[] SIZE_UNITS = {"B", "KB", "MB", "GB", "TB"};

    private UiFormatter() {
    }

    public static String formatBytes(Context context, long value) {
        double normalized = Math.max(0L, value);
        int unitIndex = 0;
        while (normalized >= 1024.0 && unitIndex < SIZE_UNITS.length - 1) {
            normalized /= 1024.0;
            unitIndex++;
        }
        String format = normalized >= 100.0 || unitIndex == 0 ? "%.0f %s" : "%.1f %s";
        return String.format(Locale.US, format, normalized, SIZE_UNITS[unitIndex]);
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
