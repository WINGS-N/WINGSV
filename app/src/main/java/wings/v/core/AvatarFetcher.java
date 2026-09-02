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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Загрузка аватара и обрезка его в круг под тулбар */
public final class AvatarFetcher {

    private static final int TIMEOUT_MS = 8_000;

    /** Больше этого аватар в тулбаре всё равно не показать */
    private static final int MAX_SIZE_PX = 96;

    private AvatarFetcher() {}

    /**
     * Отдаёт аватар из кэша, а когда его там нет - забирает у панели и кладёт.
     *
     * <p>Ключ кэша - сам адрес: версия картинки уже стоит в нём параметром, и
     * обновление аватара в панели меняет адрес. Прошлые версии удаляются, чтобы
     * кэш не рос с каждой сменой.
     */
    @Nullable
    public static Bitmap cached(@NonNull Context context, @NonNull String url) {
        File file = cacheFile(context, url);
        if (file.exists()) {
            Bitmap stored = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (stored != null) {
                return stored;
            }
            file.delete();
        }
        Bitmap fetched = fetch(context, url);
        if (fetched == null) {
            return null;
        }
        store(file, fetched);
        return fetched;
    }

    /** Файл под эту версию аватара, соседние версии при этом сносятся */
    private static File cacheFile(Context context, String url) {
        File dir = new File(context.getCacheDir(), "avatars");
        dir.mkdirs();
        String name = Integer.toHexString(url.hashCode()) + ".png";
        File[] existing = dir.listFiles();
        if (existing != null) {
            for (File other : existing) {
                if (!other.getName().equals(name)) {
                    other.delete();
                }
            }
        }
        return new File(dir, name);
    }

    private static void store(File file, Bitmap bitmap) {
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {
            // Кэш - удобство, а не обязанность: не записалось, значит скачаем снова
        }
    }

    /** Сбрасывает кэш целиком: например, когда из аккаунта вышли */
    public static void clearCache(@NonNull Context context) {
        File[] files = new File(context.getCacheDir(), "avatars").listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            file.delete();
        }
    }

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
