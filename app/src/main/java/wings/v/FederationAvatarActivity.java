package wings.v;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.AvatarFetcher;
import wings.v.core.FederationAccount;

/**
 * Аватар: превью, источник картинки и подтверждение.
 *
 * <p>Выбранное не уходит на сервер сразу - сначала его видно, и только "Сохранить"
 * его отправляет.
 */
public final class FederationAvatarActivity extends AppCompatActivity {

    /** Максимум, который принимает панель */
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ImageView preview;
    private ProgressBar progress;
    private View save;

    private byte[] picked;
    private String pickedMime = "image/png";

    private final ActivityResultLauncher<String> pickFromGallery = registerForActivityResult(
        new ActivityResultContracts.GetContent(),
        uri -> {
            if (uri != null) {
                showPicked(uri);
            }
        }
    );

    /** Редактор аватара Galaxy живёт в AR Emoji и открывается своим экраном */
    private static final String AREMOJI_PACKAGE = "com.samsung.android.aremoji";
    private static final String AREMOJI_PROFILE = "com.samsung.android.aremoji.home.profile.ProfileActivity";

    /** Галерея Samsung: на её устройствах пикер должен быть её же */
    private static final String SAMSUNG_GALLERY = "com.sec.android.gallery3d";

    private final ActivityResultLauncher<Intent> pickFromSamsungGallery = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            Uri uri = result.getData() == null ? null : result.getData().getData();
            if (uri != null) {
                showPicked(uri);
            }
        }
    );

    private final ActivityResultLauncher<Intent> galaxyAvatar = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> takeGalaxyAvatar(result.getData())
    );

    private final ActivityResultLauncher<Void> takePhoto = registerForActivityResult(
        new ActivityResultContracts.TakePicturePreview(),
        bitmap -> {
            if (bitmap != null) {
                showTaken(bitmap);
            }
        }
    );

    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, FederationAvatarActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_federation_avatar);

        ToolbarLayout toolbar = findViewById(R.id.toolbar_layout);
        toolbar.setShowNavigationButtonAsBack(true);

        preview = findViewById(R.id.federation_avatar_preview);
        progress = findViewById(R.id.federation_avatar_progress);
        save = findViewById(R.id.federation_avatar_save);

        findViewById(R.id.federation_avatar_gallery).setOnClickListener(v -> openGallery());
        findViewById(R.id.federation_avatar_camera).setOnClickListener(v -> takePhoto.launch(null));
        findViewById(R.id.federation_avatar_cancel).setOnClickListener(v -> finish());
        View reset = findViewById(R.id.federation_avatar_reset);
        reset.setOnClickListener(v -> removeAvatar());
        // Убирать нечего, когда своей картинки и так нет
        reset.setVisibility(FederationAccount.hasOwnAvatar(this) ? View.VISIBLE : View.GONE);
        View galaxy = findViewById(R.id.federation_avatar_galaxy);
        galaxy.setVisibility(installed(AREMOJI_PACKAGE) ? View.VISIBLE : View.GONE);
        galaxy.setOnClickListener(v -> openGalaxyAvatar());
        save.setOnClickListener(v -> upload());

        loadCurrent();
    }

    /**
     * Кидает работу в фон, пока экран жив.
     *
     * <p>Ответ из сети приходит и после закрытия экрана, а пул к тому моменту уже
     * прибит: голый execute на нём кидает RejectedExecutionException и роняет
     * приложение нахуй
     */
    private void submit(@NonNull Runnable work) {
        if (io.isShutdown() || isFinishing() || isDestroyed()) {
            return;
        }
        try {
            io.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException dying) {
            // Экран закрыли ровно в этот момент - работать уже не для кого
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void loadCurrent() {
        String url = FederationAccount.avatarUrl(this);
        if (TextUtils.isEmpty(url)) {
            return;
        }
        submit(() -> {
            Bitmap bitmap = AvatarFetcher.cached(this, url);
            if (bitmap == null) {
                return;
            }
            runOnUiThread(() -> preview.setImageDrawable(AvatarFetcher.circular(getResources(), bitmap)));
        });
    }

    /** Пикер Samsung, когда он есть: системный остаётся запасным */
    private void openGallery() {
        if (installed(SAMSUNG_GALLERY)) {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            intent.setPackage(SAMSUNG_GALLERY);
            if (intent.resolveActivity(getPackageManager()) != null) {
                pickFromSamsungGallery.launch(intent);
                return;
            }
        }
        pickFromGallery.launch("image/*");
    }

    /** Аватар Galaxy рисуется в AR Emoji, а сюда возвращается картинкой */
    private void openGalaxyAvatar() {
        try {
            Intent intent = new Intent();
            intent.setClassName(AREMOJI_PACKAGE, AREMOJI_PROFILE);
            intent.putExtra("from", getPackageName());
            galaxyAvatar.launch(intent);
        } catch (Exception error) {
            complain(error.getMessage());
        }
    }

    private boolean installed(@NonNull String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception missing) {
            return false;
        }
    }

    private void showPicked(@NonNull Uri uri) {
        submit(() -> {
            try {
                byte[] data = readAll(uri);
                if (data.length > MAX_AVATAR_BYTES) {
                    throw new IllegalStateException(getString(R.string.federation_account_avatar_too_big));
                }
                String mime = getContentResolver().getType(uri);
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                runOnUiThread(() -> {
                    picked = data;
                    pickedMime = TextUtils.isEmpty(mime) ? "image/png" : mime;
                    if (bitmap != null) {
                        preview.setImageDrawable(AvatarFetcher.circular(getResources(), bitmap));
                    }
                    save.setEnabled(true);
                });
            } catch (Exception error) {
                complain(error.getMessage());
            }
        });
    }

    /**
     * Забирает картинку, нарисованную в AR Emoji.
     *
     * <p>Он отдаёт её то адресом, то самой картинкой в довесках, поэтому
     * проверяются оба варианта, а молчаливый возврат ни с чем - это отказ, и о
     * нём надо сказать.
     */
    private void takeGalaxyAvatar(@Nullable Intent data) {
        if (data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri != null) {
            showPicked(uri);
            return;
        }
        Bundle extras = data.getExtras();
        if (extras == null) {
            complain(getString(R.string.federation_avatar_galaxy_empty));
            return;
        }
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value instanceof Uri) {
                showPicked((Uri) value);
                return;
            }
            if (value instanceof Bitmap) {
                showTaken((Bitmap) value);
                return;
            }
            if (value instanceof String && ((String) value).startsWith("content://")) {
                showPicked(Uri.parse((String) value));
                return;
            }
        }
        complain(getString(R.string.federation_avatar_galaxy_empty));
    }

    private void showTaken(@NonNull Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        picked = out.toByteArray();
        pickedMime = "image/png";
        preview.setImageDrawable(AvatarFetcher.circular(getResources(), bitmap));
        save.setEnabled(true);
    }

    private void upload() {
        if (picked == null) {
            return;
        }
        byte[] data = picked;
        String mime = pickedMime;
        setBusy(true);
        submit(() -> {
            try {
                FederationAccount.uploadAvatar(this, data, mime);
                AvatarFetcher.clearCache(this);
                runOnUiThread(() -> {
                    setBusy(false);
                    Toast.makeText(this, R.string.federation_avatar_saved, Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (Exception error) {
                runOnUiThread(() -> setBusy(false));
                complain(error.getMessage());
            }
        });
    }

    private void removeAvatar() {
        setBusy(true);
        submit(() -> {
            try {
                FederationAccount.removeAvatar(this);
                AvatarFetcher.clearCache(this);
                runOnUiThread(() -> {
                    setBusy(false);
                    picked = null;
                    save.setEnabled(false);
                    findViewById(R.id.federation_avatar_reset).setVisibility(View.GONE);
                    preview.setImageResource(R.drawable.ic_account_avatar);
                    // На месте убранной сразу встаёт заглушка панели, а не пустота
                    loadCurrent();
                });
            } catch (Exception error) {
                runOnUiThread(() -> setBusy(false));
                complain(error.getMessage());
            }
        });
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        save.setEnabled(!busy && picked != null);
    }

    private byte[] readAll(@NonNull Uri uri) throws Exception {
        try (
            InputStream stream = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            if (stream == null) {
                throw new IllegalStateException("не удалось открыть файл");
            }
            byte[] buffer = new byte[8192];
            int read = stream.read(buffer);
            while (read != -1) {
                out.write(buffer, 0, read);
                read = stream.read(buffer);
            }
            return out.toByteArray();
        }
    }

    private void complain(@Nullable String message) {
        if (isFinishing() || isDestroyed() || Thread.currentThread().isInterrupted()) {
            // Экран закрыли, и запрос оборвали мы сами: жаловаться не на что
            return;
        }
        runOnUiThread(() -> Toast.makeText(this, message == null ? "" : message, Toast.LENGTH_LONG).show());
    }
}
