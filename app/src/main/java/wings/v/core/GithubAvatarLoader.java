package wings.v.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GithubAvatarLoader {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    public interface Callback {
        void onLoaded(@NonNull Drawable drawable);
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    private final Context appContext;
    private final File cacheDirectory;

    public GithubAvatarLoader(Context context) {
        appContext = context.getApplicationContext();
        cacheDirectory = new File(appContext.getCacheDir(), "github_avatars");
        if (!cacheDirectory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDirectory.mkdirs();
        }
    }

    @Nullable
    public Drawable loadCached(String username) {
        Bitmap bitmap = decodeAvatar(cacheFile(username));
        return bitmap == null ? null : toCircularDrawable(bitmap);
    }

    public void fetch(String username, @NonNull Callback callback) {
        EXECUTOR.execute(() -> {
            Bitmap bitmap = downloadAvatar(username);
            if (bitmap == null) {
                return;
            }
            Drawable drawable = toCircularDrawable(bitmap);
            MAIN_HANDLER.post(() -> callback.onLoaded(drawable));
        });
    }

    @Nullable
    private Bitmap downloadAvatar(String username) {
        HttpURLConnection connection = null;
        File targetFile = cacheFile(username);
        File tempFile = new File(cacheDirectory, username + ".tmp");
        try {
            URL url = new URL("https://github.com/" + username + ".png?size=128");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(4500);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(true);
            connection.setRequestProperty("User-Agent", "WINGSV/" + appContext.getPackageName());
            connection.connect();
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                return null;
            }

            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
                outputStream.flush();
            }

            Bitmap bitmap = decodeAvatar(tempFile);
            if (bitmap == null) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
                return null;
            }

            if (targetFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                targetFile.delete();
            }
            if (!tempFile.renameTo(targetFile)) {
                try (InputStream inputStream = new FileInputStream(tempFile);
                     FileOutputStream outputStream = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, read);
                    }
                    outputStream.flush();
                }
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
            return bitmap;
        } catch (Exception ignored) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private Bitmap decodeAvatar(File file) {
        try {
            if (!file.exists()) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Drawable toCircularDrawable(Bitmap bitmap) {
        RoundedBitmapDrawable drawable =
                RoundedBitmapDrawableFactory.create(appContext.getResources(), bitmap);
        drawable.setCircular(true);
        drawable.setAntiAlias(true);
        return drawable;
    }

    private File cacheFile(String username) {
        return new File(cacheDirectory, username + ".png");
    }
}
