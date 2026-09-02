package wings.v;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
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
import wings.v.core.FederationSubscription;
import wings.v.core.UiFormatter;

/** Экран аккаунта федерации: вход, выданный доступ и подписка */
public class FederationAccountActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private View signInCard;
    private View profileCard;
    private View counters;
    private View sections;
    private View panelSections;
    private View signOutButton;
    private ProgressBar signInProgress;
    private EditText login;
    private EditText password;
    private EditText code;
    private TextView signInError;
    private TextView username;
    private TextView trust;
    private View trustBar;
    private ProgressBar trustProgress;
    private TextView nodes;
    private TextView nodesMeta;
    private TextView traffic;
    private TextView speedDown;
    private TextView speedUp;
    private ProgressBar avatarProgress;
    private ImageView avatar;

    /** Максимум, который принимает панель */
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;

    private final ActivityResultLauncher<String> pickAvatar = registerForActivityResult(
        new ActivityResultContracts.GetContent(),
        uri -> {
            if (uri != null) {
                uploadAvatar(uri);
            }
        }
    );

    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, FederationAccountActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_federation_account);

        ToolbarLayout toolbar = findViewById(R.id.toolbar_layout);
        toolbar.setShowNavigationButtonAsBack(true);

        signInCard = findViewById(R.id.federation_sign_in_card);
        profileCard = findViewById(R.id.federation_profile_card);
        counters = findViewById(R.id.federation_counters);
        sections = findViewById(R.id.federation_sections);
        panelSections = findViewById(R.id.federation_panel_sections);
        signOutButton = findViewById(R.id.federation_sign_out);
        signInProgress = findViewById(R.id.federation_sign_in_progress);
        login = findViewById(R.id.federation_login);
        password = findViewById(R.id.federation_password);
        code = findViewById(R.id.federation_code);
        signInError = findViewById(R.id.federation_sign_in_error);
        username = findViewById(R.id.federation_username);
        trust = findViewById(R.id.federation_trust);
        trustBar = findViewById(R.id.federation_trust_bar);
        trustProgress = findViewById(R.id.federation_trust_progress);
        nodes = findViewById(R.id.federation_nodes);
        nodesMeta = findViewById(R.id.federation_nodes_meta);
        traffic = findViewById(R.id.federation_traffic);
        speedDown = findViewById(R.id.federation_speed_down);
        speedUp = findViewById(R.id.federation_speed_up);
        avatarProgress = findViewById(R.id.federation_avatar_progress);
        avatar = findViewById(R.id.federation_avatar);

        findViewById(R.id.federation_sign_in).setOnClickListener(v -> signIn());
        findViewById(R.id.federation_sign_in_matrix).setOnClickListener(v -> openBrowserLogin());
        findViewById(R.id.federation_sign_out).setOnClickListener(v -> signOut());
        findViewById(R.id.federation_row_subscription).setOnClickListener(v ->
            startActivity(FederationSubscriptionActivity.createIntent(this))
        );
        findViewById(R.id.federation_row_profile).setOnClickListener(v ->
            startActivity(FederationProfileActivity.createIntent(this))
        );
        findViewById(R.id.federation_row_clients).setOnClickListener(v -> openPanelSection("/admin/clients"));
        avatar.setOnClickListener(v -> pickAvatar.launch("image/*"));

        handleCode(getIntent());
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCode(intent);
    }

    /** Возврат из браузера после входа через Matrix приносит одноразовый код */
    private void handleCode(@Nullable Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        String code = data == null ? null : data.getQueryParameter("code");
        if (TextUtils.isEmpty(code)) {
            return;
        }
        io.execute(() -> {
            try {
                FederationAccount.Session session = FederationAccount.exchangeCode(this, code);
                FederationAccount.store(this, session);
                runOnUiThread(this::render);
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void signIn() {
        String user = login.getText() == null ? "" : login.getText().toString().trim();
        String pass = password.getText() == null ? "" : password.getText().toString();
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            return;
        }
        String secondFactor = code.getText() == null ? "" : code.getText().toString().trim();
        setSigningIn(true);
        io.execute(() -> {
            try {
                FederationAccount.Session session = FederationAccount.signIn(this, user, pass, secondFactor);
                FederationAccount.store(this, session);
                runOnUiThread(() -> {
                    setSigningIn(false);
                    password.setText("");
                    code.setText("");
                    code.setVisibility(View.GONE);
                    signInError.setVisibility(View.GONE);
                    render();
                });
            } catch (FederationAccount.SecondFactorRequired needsCode) {
                runOnUiThread(() -> {
                    setSigningIn(false);
                    code.setVisibility(View.VISIBLE);
                    code.requestFocus();
                    if (TextUtils.isEmpty(secondFactor)) {
                        signInError.setVisibility(View.GONE);
                        return;
                    }
                    signInError.setText(needsCode.getMessage());
                    signInError.setVisibility(View.VISIBLE);
                });
            } catch (Exception error) {
                runOnUiThread(() -> setSigningIn(false));
                showError(error.getMessage());
            }
        });
    }

    /** Вход идёт по сети: без индикатора нажатие выглядит потерянным */
    private void setSigningIn(boolean busy) {
        signInProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        findViewById(R.id.federation_sign_in).setEnabled(!busy);
        ((Button) findViewById(R.id.federation_sign_in)).setText(
            busy ? R.string.federation_account_signing_in : R.string.federation_account_sign_in
        );
    }

    /** Раздел, который в приложении ещё не собран, открывается в панели */
    private void openPanelSection(@NonNull String path) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(FederationAccount.panelUrl(this) + path)));
    }

    private void openBrowserLogin() {
        Uri target = Uri.parse(FederationAccount.panelUrl(this) + "/app/link");
        startActivity(new Intent(Intent.ACTION_VIEW, target));
    }

    private void signOut() {
        io.execute(() -> {
            FederationAccount.signOut(this);
            // Подписка выдана этому аккаунту: без него она мертва
            FederationSubscription.remove(this);
            AvatarFetcher.clearCache(this);
            runOnUiThread(this::render);
        });
    }

    private void render() {
        boolean signedIn = FederationAccount.isSignedIn(this);
        signInCard.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        profileCard.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        counters.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        sections.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        signOutButton.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (!signedIn) {
            return;
        }
        username.setText(FederationAccount.username(this));
        panelSections.setVisibility(FederationAccount.hasPanel(this) ? View.VISIBLE : View.GONE);
        loadAvatar();
        loadAccess();
    }

    /** Аватар грузится отдельно: без него экран уже полный, с ним - живой */
    private void loadAvatar() {
        String url = FederationAccount.avatarUrl(this);
        if (TextUtils.isEmpty(url)) {
            return;
        }
        io.execute(() -> {
            android.graphics.Bitmap bitmap = AvatarFetcher.cached(this, url);
            if (bitmap == null) {
                return;
            }
            runOnUiThread(() -> avatar.setImageDrawable(AvatarFetcher.circular(getResources(), bitmap)));
        });
    }

    /** Меняет аватар: файл уходит в панель, а кэш картинки сбрасывается */
    private void uploadAvatar(@NonNull Uri uri) {
        avatarProgress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                byte[] data = readAll(uri);
                if (data.length > MAX_AVATAR_BYTES) {
                    throw new IllegalStateException(getString(R.string.federation_account_avatar_too_big));
                }
                String mime = getContentResolver().getType(uri);
                FederationAccount.uploadAvatar(this, data, TextUtils.isEmpty(mime) ? "image/png" : mime);
                AvatarFetcher.clearCache(this);
                runOnUiThread(() -> {
                    avatarProgress.setVisibility(View.GONE);
                    loadAvatar();
                });
            } catch (Exception error) {
                runOnUiThread(() -> avatarProgress.setVisibility(View.GONE));
                showError(error.getMessage());
            }
        });
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

    private void loadAccess() {
        io.execute(() -> {
            try {
                FederationAccount.Access loaded = FederationAccount.access(this);
                runOnUiThread(() -> applyAccess(loaded));
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void applyAccess(@Nullable FederationAccount.Access access) {
        if (access == null || !access.enabled) {
            nodes.setText("-");
            nodesMeta.setText(R.string.federation_account_no_access);
            return;
        }
        nodes.setText(getString(R.string.federation_account_nodes, access.nodes));
        nodesMeta.setText(
            access.nodesEntitled > access.nodes
                ? getString(R.string.federation_account_nodes_short, access.nodesEntitled)
                : ""
        );
        traffic.setText(UiFormatter.formatBytes(this, access.usedBytes));
        applySpeed(access);
        applyTrust(access);
        // Аватар мог смениться в панели, и тогда версия в адресе уже другая
        FederationAccount.rememberAvatarVersion(this, access.avatarVersion);
        loadAvatar();
        // Подписка заводится сама: вход в аккаунт - и есть согласие её получить
        if (!TextUtils.isEmpty(access.subscriptionUrl)) {
            io.execute(() -> FederationSubscription.ensure(this, access.subscriptionUrl));
        }
    }

    /** Полоса доверия читается так же, как полоса трафика у подписки */
    private void applyTrust(@NonNull FederationAccount.Access access) {
        if (TextUtils.isEmpty(access.trustBand)) {
            trustBar.setVisibility(View.GONE);
            return;
        }
        trustBar.setVisibility(View.VISIBLE);
        trustProgress.setProgress(Math.max(0, Math.min(100, access.trustConfidence)));
        trust.setText(
            getString(R.string.federation_account_trust, access.trustConfidence) + " - " + bandLabel(access.trustBand)
        );
    }

    private String bandLabel(@NonNull String band) {
        if ("full".equals(band)) {
            return getString(R.string.federation_account_trust_full);
        }
        if ("reduced".equals(band)) {
            return getString(R.string.federation_account_trust_reduced);
        }
        if ("quarantine".equals(band)) {
            return getString(R.string.federation_account_trust_quarantine);
        }
        return band;
    }

    /** Потолок вниз и вверх, как его выдал оракул. Цвета те же, что у трафика */
    private void applySpeed(@NonNull FederationAccount.Access access) {
        if (access.downlinkBps == 0 && access.uplinkBps == 0) {
            speedDown.setText(R.string.federation_account_speed_unlimited);
            speedUp.setText("");
            return;
        }
        speedDown.setText(UiFormatter.formatBytesPerSecond(this, access.downlinkBps));
        speedUp.setText(UiFormatter.formatBytesPerSecond(this, access.uplinkBps));
    }

    private void showError(@Nullable String message) {
        runOnUiThread(() -> {
            signInError.setText(
                TextUtils.isEmpty(message) ? getString(R.string.federation_account_network_error) : message
            );
            signInError.setVisibility(View.VISIBLE);
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
