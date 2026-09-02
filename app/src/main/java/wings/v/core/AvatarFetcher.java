package wings.v.core;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
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

    private static final String TAG = "AvatarFetcher";

    private static final int TIMEOUT_MS = 8_000;

    /** Потолок стороны: кружок в профиле крупный, и мелкая картинка на нём мылит */
    private static final int MAX_SIZE_PX = 512;

    /**
     * Ревизия кэша стоит в имени файла и растёт вместе с MAX_SIZE_PX: иначе на
     * диске лежит картинка, ужатая под старый потолок, и её подсовывают вместо
     * нормальной
     */
    private static final int CACHE_REVISION = 2;

    /**
     * Последняя картинка держится в памяти: иначе каждый экран открывается с
     * человечком и подменяет его через сеть или диск.
     */
    private static volatile String memoryKey = "";

    @Nullable
    private static volatile Bitmap memoryBitmap;

    private AvatarFetcher() {}

    /** Готовая картинка для этого адреса, если она уже загружена */
    @Nullable
    public static Bitmap fromMemory(@NonNull String url) {
        return url.equals(memoryKey) ? memoryBitmap : null;
    }

    /**
     * Отдаёт аватар из кэша, а когда его там нет - забирает у панели и кладёт.
     *
     * <p>Ключ кэша - сам адрес: версия картинки уже стоит в нём параметром, и
     * обновление аватара в панели меняет адрес. Прошлые версии удаляются, чтобы
     * кэш не рос с каждой сменой.
     */
    @Nullable
    public static Bitmap cached(@NonNull Context context, @NonNull String url) {
        Bitmap inMemory = fromMemory(url);
        if (inMemory != null) {
            return inMemory;
        }
        File file = cacheFile(context, url);
        if (file.exists()) {
            Bitmap stored = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (stored != null) {
                remember(url, stored);
                return stored;
            }
            file.delete();
        }
        Bitmap fetched = fetch(context, url);
        if (fetched == null) {
            return null;
        }
        store(file, fetched);
        remember(url, fetched);
        return fetched;
    }

    /** Файл под эту версию аватара, соседние версии при этом сносятся */
    private static File cacheFile(Context context, String url) {
        File dir = new File(context.getCacheDir(), "avatars");
        dir.mkdirs();
        String name = Integer.toHexString(url.hashCode()) + "-" + CACHE_REVISION + ".png";
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

    private static void remember(String url, Bitmap bitmap) {
        memoryKey = url;
        memoryBitmap = bitmap;
    }

    /**
     * Греет кэш заранее, в фоне.
     *
     * <p>Без прогрева первый показ после запуска всегда лезет в сеть, и человек
     * успевает увидеть пустой кружок на каждом экране, где аватар есть.
     */
    public static void warmUp(@NonNull Context context, @NonNull String url) {
        if (url.isEmpty() || fromMemory(url) != null) {
            return;
        }
        Context app = context.getApplicationContext();
        new Thread(() -> cached(app, url), "avatar-warmup").start();
    }

    /** Сбрасывает кэш целиком: например, когда из аккаунта вышли */
    public static void clearCache(@NonNull Context context) {
        memoryKey = "";
        memoryBitmap = null;
        File[] files = new File(context.getCacheDir(), "avatars").listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            file.delete();
        }
    }

    /**
     * Возвращает картинку или null, когда панель недоступна.
     *
     * <p>Причина неудачи пишется в лог: молчаливый null неотличим от "аватара
     * нет", и разбираться, почему у человека пустой кружок, потом не по чему.
     */
    @Nullable
    public static Bitmap fetch(@NonNull Context context, @NonNull String url) {
        HttpURLConnection connection = null;
        try {
            connection = DirectNetworkConnection.openHttpConnection(context, new URL(url));
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "avatar fetch failed: HTTP " + code + " for " + url);
                return null;
            }
            try (InputStream stream = connection.getInputStream()) {
                Bitmap decoded = BitmapFactory.decodeStream(stream);
                if (decoded == null) {
                    Log.w(TAG, "avatar fetch failed: body is not an image, " + url);
                    return null;
                }
                // Растягивать мелкий исходник незачем: апскейл только мылит.
                // Уменьшается лишь то, что крупнее нужного
                int side = Math.max(decoded.getWidth(), decoded.getHeight());
                if (side <= MAX_SIZE_PX) {
                    return decoded;
                }
                float scale = (float) MAX_SIZE_PX / side;
                int width = Math.max(1, Math.round(decoded.getWidth() * scale));
                int height = Math.max(1, Math.round(decoded.getHeight() * scale));
                return Bitmap.createScaledBitmap(decoded, width, height, true);
            }
        } catch (Exception error) {
            Log.w(TAG, "avatar fetch failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
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
        paint.setFilterBitmap(true);
        paint.setDither(true);
        BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        // Кадр берётся из середины: иначе неквадратная картинка режется по
        // левому верхнему углу и лицо уезжает за край круга
        Matrix crop = new Matrix();
        crop.setTranslate(-(source.getWidth() - size) / 2f, -(source.getHeight() - size) / 2f);
        shader.setLocalMatrix(crop);
        paint.setShader(shader);
        new Canvas(output).drawCircle(size / 2f, size / 2f, size / 2f, paint);
        BitmapDrawable drawable = new BitmapDrawable(resources, output);
        // Картинка обычно крупнее вида, и без фильтра уменьшение выглядит рвано
        drawable.setFilterBitmap(true);
        return drawable;
    }
}
