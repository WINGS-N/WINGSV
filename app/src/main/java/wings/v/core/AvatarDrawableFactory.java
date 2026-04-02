package wings.v.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

public final class AvatarDrawableFactory {
    private AvatarDrawableFactory() {
    }

    public static Drawable create(Context context, String initials, @ColorInt int backgroundColor) {
        float density = context.getResources().getDisplayMetrics().density;
        int sizePx = Math.max(1, Math.round(40f * density));
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(backgroundColor);
        float radius = sizePx / 2f;
        canvas.drawCircle(radius, radius, radius, circlePaint);

        String label = TextUtils.isEmpty(initials) ? "?" : initials.trim().toUpperCase();
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        textPaint.setTextSize(sizePx * (label.length() > 1 ? 0.38f : 0.48f));

        Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
        float textY = radius - ((fontMetrics.ascent + fontMetrics.descent) / 2f);
        canvas.drawText(label, radius, textY, textPaint);

        return new BitmapDrawable(context.getResources(), bitmap);
    }

    public static Drawable createCircularBanner(Context context,
                                                @NonNull Drawable bannerDrawable,
                                                @ColorInt int backgroundColor) {
        float density = context.getResources().getDisplayMetrics().density;
        int sizePx = Math.max(1, Math.round(40f * density));
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(backgroundColor);
        float radius = sizePx / 2f;
        canvas.drawCircle(radius, radius, radius, circlePaint);

        Bitmap source = drawableToBitmap(bannerDrawable);
        if (source != null) {
            Bitmap scaled = Bitmap.createScaledBitmap(source, sizePx, sizePx, true);
            Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            imagePaint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            canvas.drawCircle(radius, radius, radius, imagePaint);
        }

        return new BitmapDrawable(context.getResources(), bitmap);
    }

    private static Bitmap drawableToBitmap(@NonNull Drawable drawable) {
        int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 256;
        int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 256;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }
}
