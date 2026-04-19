package wings.v.core;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;

public final class UpdateBadgeUtils {

    private UpdateBadgeUtils() {}

    public static boolean shouldShowUpdateBadge(@Nullable AppUpdateManager.UpdateState state) {
        if (state == null) {
            return false;
        }
        switch (state.status) {
            case UPDATE_AVAILABLE:
            case DOWNLOADING:
            case DOWNLOADED:
                return true;
            case ERROR:
                return state.releaseInfo != null;
            default:
                return false;
        }
    }

    @Nullable
    public static Drawable createBadgedIcon(@NonNull Context context, @DrawableRes int baseIconResId) {
        Drawable base = AppCompatResources.getDrawable(context, baseIconResId);
        Drawable badge = AppCompatResources.getDrawable(context, androidx.appcompat.R.drawable.sesl_dot_badge);
        if (base == null) {
            return null;
        }
        if (badge == null) {
            return base;
        }

        Drawable baseDrawable = DrawableCompat.wrap(base.mutate());
        Drawable badgeDrawable = DrawableCompat.wrap(badge.mutate());
        LayerDrawable layered = new LayerDrawable(new Drawable[] { baseDrawable, badgeDrawable });

        int baseWidth = Math.max(1, baseDrawable.getIntrinsicWidth());
        int baseHeight = Math.max(1, baseDrawable.getIntrinsicHeight());
        int badgeWidth = Math.max(1, badgeDrawable.getIntrinsicWidth());
        int badgeHeight = Math.max(1, badgeDrawable.getIntrinsicHeight());

        layered.setLayerGravity(1, Gravity.END | Gravity.TOP);
        layered.setLayerSize(0, baseWidth, baseHeight);
        layered.setLayerSize(1, badgeWidth, badgeHeight);
        return layered;
    }
}
