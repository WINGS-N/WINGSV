package wings.v.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import wings.v.R;

public final class GuardianImportPrompt {

    private static final ExecutorService AVATAR_EXECUTOR = Executors.newSingleThreadExecutor();

    private GuardianImportPrompt() {}

    @FunctionalInterface
    public interface OnConfirm {
        void onConfirm();
    }

    public static void show(
        @NonNull Activity activity,
        @NonNull WingsImportParser.ImportedConfig parsed,
        @NonNull OnConfirm onConfirm
    ) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_guardian_import_confirm, null);
        TextView usernameView = view.findViewById(R.id.guardian_import_username);
        TextView descriptionView = view.findViewById(R.id.guardian_import_description);
        ImageView avatarView = view.findViewById(R.id.guardian_import_avatar_image);
        ProgressBar loaderView = view.findViewById(R.id.guardian_import_avatar_loader);
        avatarView.setClipToOutline(true);
        avatarView.setOutlineProvider(
            new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            }
        );
        usernameView.setText(parsed.guardianAdminUsername);
        descriptionView.setText(activity.getString(R.string.guardian_import_description, parsed.guardianAdminUsername));
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.import_confirm_title)
            .setView(view)
            .setPositiveButton(R.string.import_confirm_apply, (d, which) -> onConfirm.onConfirm())
            .setNegativeButton(android.R.string.cancel, (d, which) ->
                Toast.makeText(activity, R.string.import_confirm_cancelled, Toast.LENGTH_SHORT).show()
            )
            .create();
        prefetchAvatar(parsed, dialog, loaderView, avatarView);
        dialog.show();
    }

    private static void prefetchAvatar(
        @NonNull WingsImportParser.ImportedConfig parsed,
        @NonNull AlertDialog hostDialog,
        @NonNull ProgressBar loaderView,
        @NonNull ImageView avatarView
    ) {
        // Always try a fetch when an admin id is present: a missing /
        // outdated avatar_version (panel build that did not yet wire that
        // field, or a default placeholder admin) is no reason to hide the
        // spinner before we even hit the network. The server responds 404
        // for admins without a custom upload and we then fall back to the
        // placeholder background.
        if (parsed.guardianAdminId <= 0) {
            loaderView.setVisibility(View.GONE);
            return;
        }
        String url = buildAvatarUrl(parsed);
        if (TextUtils.isEmpty(url)) {
            loaderView.setVisibility(View.GONE);
            return;
        }
        loaderView.setVisibility(View.VISIBLE);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        hostDialog.setOnDismissListener(d -> cancelled.set(true));
        Handler mainHandler = new Handler(Looper.getMainLooper());
        AVATAR_EXECUTOR.execute(() -> {
            Bitmap bitmap = downloadAvatar(url);
            if (cancelled.get()) return;
            mainHandler.post(() -> {
                if (cancelled.get()) return;
                loaderView.setVisibility(View.GONE);
                if (bitmap != null) {
                    avatarView.setImageBitmap(bitmap);
                }
                // On miss (server returned 404 or network failed) keep the
                // shipped default avatar that the layout already loaded.
            });
        });
    }

    @Nullable
    private static String buildAvatarUrl(@NonNull WingsImportParser.ImportedConfig parsed) {
        String wsUrl = parsed.guardianWsUrl;
        if (TextUtils.isEmpty(wsUrl)) return null;
        String httpBase;
        if (wsUrl.startsWith("wss://")) {
            httpBase = "https://" + wsUrl.substring("wss://".length());
        } else if (wsUrl.startsWith("ws://")) {
            httpBase = "http://" + wsUrl.substring("ws://".length());
        } else {
            httpBase = wsUrl;
        }
        int pathStart = httpBase.indexOf('/', httpBase.indexOf("://") + 3);
        String origin = pathStart < 0 ? httpBase : httpBase.substring(0, pathStart);
        return origin + "/api/admin/avatars/" + parsed.guardianAdminId + ".png?v=" + parsed.guardianAdminAvatarVersion;
    }

    @Nullable
    private static Bitmap downloadAvatar(@NonNull String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
