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
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.AvatarFetcher;
import wings.v.core.FederationAccount;
import wings.v.core.UiFormatter;
import wings.v.core.XrayStore;
import wings.v.core.XraySubscription;
import wings.v.core.XraySubscriptionUpdater;

/** Экран аккаунта федерации: вход, выданный доступ и подписка */
public class FederationAccountActivity extends AppCompatActivity {

    /** Идентификатор подписки федерации: он же не даёт завести её дважды */
    private static final String FEDERATION_SUBSCRIPTION_ID = "wings-federation";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private View signInCard;
    private View profileCard;
    private View accessCard;
    private View signOutCard;
    private EditText login;
    private EditText password;
    private EditText code;
    private TextView signInError;
    private TextView username;
    private TextView trust;
    private TextView trustBand;
    private View trustBar;
    private ProgressBar trustProgress;
    private TextView nodes;
    private TextView nodesMeta;
    private TextView traffic;
    private TextView speedDown;
    private TextView speedUp;
    private ProgressBar avatarProgress;
    private TextView subscriptionState;
    private TextView subscription;
    private ImageView avatar;
    private Button applySubscription;

    private String subscriptionUrl = "";

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

        signInCard = findViewById(R.id.federation_sign_in_card);
        profileCard = findViewById(R.id.federation_profile_card);
        accessCard = findViewById(R.id.federation_access_card);
        signOutCard = findViewById(R.id.federation_sign_out_card);
        login = findViewById(R.id.federation_login);
        password = findViewById(R.id.federation_password);
        code = findViewById(R.id.federation_code);
        signInError = findViewById(R.id.federation_sign_in_error);
        username = findViewById(R.id.federation_username);
        trust = findViewById(R.id.federation_trust);
        trustBand = findViewById(R.id.federation_trust_band);
        trustBar = findViewById(R.id.federation_trust_bar);
        trustProgress = findViewById(R.id.federation_trust_progress);
        nodes = findViewById(R.id.federation_nodes);
        nodesMeta = findViewById(R.id.federation_nodes_meta);
        traffic = findViewById(R.id.federation_traffic);
        speedDown = findViewById(R.id.federation_speed_down);
        speedUp = findViewById(R.id.federation_speed_up);
        avatarProgress = findViewById(R.id.federation_avatar_progress);
        subscription = findViewById(R.id.federation_subscription);
        subscriptionState = findViewById(R.id.federation_subscription_state);
        avatar = findViewById(R.id.federation_avatar);
        applySubscription = findViewById(R.id.federation_apply_subscription);

        findViewById(R.id.federation_sign_in).setOnClickListener(v -> signIn());
        findViewById(R.id.federation_sign_in_matrix).setOnClickListener(v -> openBrowserLogin());
        findViewById(R.id.federation_sign_out).setOnClickListener(v -> signOut());
        applySubscription.setOnClickListener(v -> addSubscription());
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
        io.execute(() -> {
            try {
                FederationAccount.Session session = FederationAccount.signIn(this, user, pass, secondFactor);
                FederationAccount.store(this, session);
                runOnUiThread(() -> {
                    password.setText("");
                    code.setText("");
                    code.setVisibility(View.GONE);
                    signInError.setVisibility(View.GONE);
                    render();
                });
            } catch (FederationAccount.SecondFactorRequired needsCode) {
                runOnUiThread(() -> {
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
                showError(error.getMessage());
            }
        });
    }

    private void openBrowserLogin() {
        Uri target = Uri.parse(FederationAccount.panelUrl(this) + "/app/link");
        startActivity(new Intent(Intent.ACTION_VIEW, target));
    }

    private void signOut() {
        io.execute(() -> {
            FederationAccount.signOut(this);
            runOnUiThread(this::render);
        });
    }

    private void addSubscription() {
        if (TextUtils.isEmpty(subscriptionUrl)) {
            return;
        }
        XraySubscription entry = new XraySubscription(
            FEDERATION_SUBSCRIPTION_ID,
            getString(R.string.federation_account_title),
            subscriptionUrl,
            "auto",
            0,
            true,
            0L,
            0L,
            0L,
            0L,
            0L
        );
        List<XraySubscription> current = new ArrayList<>(XrayStore.getSubscriptions(this));
        // Подписка одна: повторное нажатие обновляет адрес, а не плодит копии
        current.removeIf(existing -> existing != null && FEDERATION_SUBSCRIPTION_ID.equals(existing.id));
        current.add(entry);
        XrayStore.setSubscriptions(this, current);
        Toast.makeText(this, R.string.federation_account_subscription_added, Toast.LENGTH_SHORT).show();
        subscriptionState.setText(R.string.federation_account_subscription_added_state);
        io.execute(() -> {
            try {
                XraySubscriptionUpdater.refreshAll(this);
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void render() {
        boolean signedIn = FederationAccount.isSignedIn(this);
        signInCard.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        profileCard.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        accessCard.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        signOutCard.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (!signedIn) {
            return;
        }
        username.setText(FederationAccount.username(this));
        trust.setText("");
        nodes.setText("");
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
            subscriptionState.setText("");
            subscription.setVisibility(View.GONE);
            applySubscription.setVisibility(View.GONE);
            return;
        }
        subscriptionUrl = access.subscriptionUrl;
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
        if (TextUtils.isEmpty(access.subscriptionUrl)) {
            subscriptionState.setText(R.string.federation_account_no_subscription);
            subscription.setVisibility(View.GONE);
            applySubscription.setVisibility(View.GONE);
            return;
        }
        subscription.setText(access.subscriptionUrl);
        subscription.setVisibility(View.VISIBLE);
        applySubscription.setVisibility(View.VISIBLE);
        subscriptionState.setText(
            hasFederationSubscription()
                ? R.string.federation_account_subscription_added_state
                : R.string.federation_account_subscription_missing
        );
    }

    /** Полоса доверия читается так же, как полоса трафика у подписки */
    private void applyTrust(@NonNull FederationAccount.Access access) {
        if (TextUtils.isEmpty(access.trustBand)) {
            trustBar.setVisibility(View.GONE);
            trustBand.setText("");
            return;
        }
        trustBar.setVisibility(View.VISIBLE);
        trustProgress.setProgress(Math.max(0, Math.min(100, access.trustConfidence)));
        String band = bandLabel(access.trustBand);
        trust.setText(getString(R.string.federation_account_trust, access.trustConfidence, band));
        trustBand.setText(band);
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

    /** Подписка уже заведена в приложении или ещё нет */
    private boolean hasFederationSubscription() {
        for (XraySubscription existing : XrayStore.getSubscriptions(this)) {
            if (existing != null && FEDERATION_SUBSCRIPTION_ID.equals(existing.id)) {
                return true;
            }
        }
        return false;
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
