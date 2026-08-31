package wings.v.core;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Загрузка аватара и обрезка его в круг под тулбар */
public final class AvatarFetcher {

    private static final int TIMEOUT_MS = 8_000;

    /** Больше этого аватар в тулбаре всё равно не показать */
    private static final int MAX_SIZE_PX = 96;

    private AvatarFetcher() {}

    /** Возвращает картинку или null, когда панель недоступна */
    @Nullable
    public static Bitmap fetch(@NonNull Context context, @NonNull String url) {
        HttpURLConnection connection = null;
        try {
            connection = DirectNetworkConnection.openHttpConnection(context, new URL(url));
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream stream = connection.getInputStream()) {
                Bitmap decoded = BitmapFactory.decodeStream(stream);
                if (decoded == null) {
                    return null;
                }
                return Bitmap.createScaledBitmap(decoded, MAX_SIZE_PX, MAX_SIZE_PX, true);
            }
        } catch (Exception error) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Обрезает картинку в круг: квадратная аватарка в тулбаре смотрится чужеродно */
    @NonNull
    public static Drawable circular(@NonNull Resources resources, @NonNull Bitmap source) {
        int size = Math.min(source.getWidth(), source.getHeight());
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(output).drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return new BitmapDrawable(resources, output);
    }
}
