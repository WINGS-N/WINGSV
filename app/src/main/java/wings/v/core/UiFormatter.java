package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import java.util.Locale;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
        "PMD.LongVariable",
    }
)
public final class UiFormatter {

    private static final long SECONDS_PER_MINUTE = 60L;
    private static final int MIN_ELLIPSIS_LENGTH = 4;
    private static final String[] SIZE_UNITS = { "B", "KB", "MB", "GB", "TB" };

    private UiFormatter() {}

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

    public static String formatDurationShort(long valueMs) {
        long totalSeconds = Math.max(0L, valueMs / 1000L);
        if (totalSeconds < SECONDS_PER_MINUTE) {
            return totalSeconds + "s";
        }
        long minutes = totalSeconds / SECONDS_PER_MINUTE;
        long seconds = totalSeconds % SECONDS_PER_MINUTE;
        if (minutes < SECONDS_PER_MINUTE) {
            return seconds == 0L ? minutes + "m" : minutes + "m " + seconds + "s";
        }
        long hours = minutes / SECONDS_PER_MINUTE;
        minutes %= SECONDS_PER_MINUTE;
        return minutes == 0L ? hours + "h" : hours + "h " + minutes + "m";
    }

    public static String truncate(String value, int maxLength) {
        if (TextUtils.isEmpty(value) || value.length() <= maxLength) {
            return value;
        }
        if (maxLength < MIN_ELLIPSIS_LENGTH) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 1) + "…";
    }
}
