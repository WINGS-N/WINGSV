package wings.v.core;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.util.Log;
import androidx.annotation.NonNull;

/**
 * Utility class to handle display density and screen scaling issues
 * that can cause app crashes on launch or configuration changes.
 */
@SuppressWarnings({ "PMD.CommentRequired", "PMD.AtLeastOneConstructor" })
public final class DisplayDensityUtils {

    private static final String TAG = "DisplayDensityUtils";
    private static final float MIN_VALID_DENSITY = 0.1f;
    private static final float MAX_VALID_DENSITY = 10.0f;
    private static final float DEFAULT_DENSITY = 1.0f;

    private DisplayDensityUtils() {
        // Utility class
    }

    /**
     * Validates and fixes display density values to prevent crashes.
     *
     * @param context The context to check
     * @return true if density is valid, false if it was corrected
     */
    public static boolean validateDisplayDensity(@NonNull Context context) {
        try {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            float density = metrics.density;

            if (isValidDensity(density)) {
                return true;
            }

            Log.w(TAG, "Invalid display density detected: " + density + ", attempting to use default");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Error validating display density", e);
            return false;
        }
    }

    /**
     * Checks if a density value is valid.
     *
     * @param density The density value to check
     * @return true if valid, false otherwise
     */
    public static boolean isValidDensity(float density) {
        return (
            density >= MIN_VALID_DENSITY &&
            density <= MAX_VALID_DENSITY &&
            !Float.isNaN(density) &&
            !Float.isInfinite(density)
        );
    }

    /**
     * Safely handles configuration changes related to display density.
     *
     * @param activity The activity handling the configuration change
     * @param newConfig The new configuration
     */
    public static void handleConfigurationChange(@NonNull Activity activity, @NonNull Configuration newConfig) {
        try {
            // Validate the new display metrics
            validateDisplayDensity(activity);

            // Force layout refresh if needed
            if (activity.findViewById(android.R.id.content) != null) {
                activity.findViewById(android.R.id.content).invalidate();
                activity.findViewById(android.R.id.content).requestLayout();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error handling configuration change", e);
        }
    }

    /**
     * Safely converts dp to pixels, handling invalid density values.
     *
     * @param context The context
     * @param dp The dp value to convert
     * @return The pixel value
     */
    public static int dpToPx(@NonNull Context context, int dp) {
        try {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            float density = metrics.density;

            if (!isValidDensity(density)) {
                density = DEFAULT_DENSITY;
                Log.w(TAG, "Using default density for dp conversion");
            }

            return Math.round(dp * density);
        } catch (Exception e) {
            Log.w(TAG, "Error converting dp to px, using fallback", e);
            return dp; // Fallback to dp value
        }
    }

    /**
     * Gets the screen width in pixels, with safety checks.
     *
     * @param context The context
     * @return The screen width in pixels, or 0 if unable to determine
     */
    public static int getScreenWidth(@NonNull Context context) {
        try {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            return metrics.widthPixels;
        } catch (Exception e) {
            Log.w(TAG, "Error getting screen width", e);
            return 0;
        }
    }

    /**
     * Gets the screen height in pixels, with safety checks.
     *
     * @param context The context
     * @return The screen height in pixels, or 0 if unable to determine
     */
    public static int getScreenHeight(@NonNull Context context) {
        try {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            return metrics.heightPixels;
        } catch (Exception e) {
            Log.w(TAG, "Error getting screen height", e);
            return 0;
        }
    }
}
