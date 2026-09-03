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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.AvatarFetcher;
import wings.v.core.FederationAccount;
import wings.v.core.FederationSubscription;
import wings.v.core.UiFormatter;
import wings.v.core.XrayStore;
import wings.v.core.XraySubscription;

/** Экран аккаунта федерации: вход, выданный доступ и подписка */
public class FederationAccountActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private View signInCard;
    private View profileCard;
    private View counters;
    private View sections;
    private View panelSections;
    /** Код из панели, который ждёт входа в аккаунт */
    private String pendingInvite = "";
    private View signOutButton;
    private ProgressBar signInProgress;
    private ProgressBar signOutProgress;
    private ProgressBar accessProgress;
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
    private TextView trafficMeta;
    private TextView speedDown;
    private TextView speedUp;
    private ImageView avatar;

    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, FederationAccountActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wings_account);

        ToolbarLayout toolbar = findViewById(R.id.toolbar_layout);
        toolbar.setShowNavigationButtonAsBack(true);

        signInCard = findViewById(R.id.federation_sign_in_card);
        profileCard = findViewById(R.id.federation_profile_card);
        counters = findViewById(R.id.federation_counters);
        sections = findViewById(R.id.federation_sections);
        panelSections = findViewById(R.id.federation_panel_sections);
        signOutButton = findViewById(R.id.federation_sign_out);
        signInProgress = findViewById(R.id.federation_sign_in_progress);
        signOutProgress = findViewById(R.id.federation_sign_out_progress);
        accessProgress = findViewById(R.id.federation_access_progress);
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
        trafficMeta = findViewById(R.id.federation_traffic_caption);
        speedDown = findViewById(R.id.federation_speed_down);
        speedUp = findViewById(R.id.federation_speed_up);
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
        findViewById(R.id.federation_row_donor).setOnClickListener(v ->
            startActivity(FederationDonorActivity.createIntent(this))
        );
        findViewById(R.id.federation_row_invites).setOnClickListener(v ->
            startActivity(FederationInvitesActivity.createIntent(this))
        );
        avatar.setOnClickListener(v -> startActivity(FederationAvatarActivity.createIntent(this)));

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
        if (data != null) {
            String invite = wings.v.core.InviteCode.parse(data.toString());
            if (invite != null) {
                applyInvite(invite);
                return;
            }
        }
        String code = data == null ? null : data.getQueryParameter("code");
        if (TextUtils.isEmpty(code)) {
            return;
        }
        submit(() -> {
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
        submit(() -> {
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
                    if (!TextUtils.isEmpty(pendingInvite)) {
                        String waiting = pendingInvite;
                        pendingInvite = "";
                        applyInvite(waiting);
                    }
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
            busy ? R.string.wings_account_signing_in : R.string.wings_account_sign_in
        );
    }

    /** Раздел, который в приложении ещё не собран, открывается в панели */
    private void openPanelSection(@NonNull String path) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(FederationAccount.panelUrl(this) + path)));
    }

    /**
     * Применяет код приглашения, приехавший ссылкой из панели.
     *
     * <p>Без аккаунта применять его некуда, поэтому код запоминается и уходит в
     * дело сразу после входа - переспрашивать его у человека второй раз незачем
     */
    private void applyInvite(@NonNull String code) {
        if (!FederationAccount.isSignedIn(this)) {
            pendingInvite = code;
            signInError.setText(R.string.invite_scan_sign_in_first);
            signInError.setVisibility(View.VISIBLE);
            login.requestFocus();
            return;
        }
        submit(() -> {
            try {
                FederationAccount.redeemInvite(this, code);
                runOnUiThread(() -> Toast.makeText(this, R.string.invite_scan_done, Toast.LENGTH_LONG).show());
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
        setSigningOut(true);
        submit(() -> {
            FederationAccount.signOut(this);
            // Подписка выдана этому аккаунту: без него она мертва
            FederationSubscription.remove(this);
            AvatarFetcher.clearCache(this);
            runOnUiThread(() -> {
                setSigningOut(false);
                render();
            });
        });
    }

    /** Выход идёт по сети: сессию отзывает панель, и это не мгновенно */
    private void setSigningOut(boolean busy) {
        signOutProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        signOutButton.setEnabled(!busy);
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
        // Сначала прошлые цифры, потом свежие: пустые карточки на секунду выглядят
        // так, будто доступа нет
        applyAccess(FederationAccount.cachedAccess(this));
        loadAvatar();
        loadAccess();
    }

    /** Аватар грузится отдельно: без него экран уже полный, с ним - живой */
    private void loadAvatar() {
        String url = FederationAccount.avatarUrl(this);
        if (TextUtils.isEmpty(url)) {
            return;
        }
        submit(() -> {
            android.graphics.Bitmap bitmap = AvatarFetcher.cached(this, url);
            if (bitmap == null) {
                return;
            }
            runOnUiThread(() -> avatar.setImageDrawable(AvatarFetcher.circular(getResources(), bitmap)));
        });
    }

    private void loadAccess() {
        // Кэш уже показан, поэтому индикатор нужен только при первом заходе
        boolean blank = FederationAccount.cachedAccess(this) == null;
        accessProgress.setVisibility(blank ? View.VISIBLE : View.GONE);
        counters.setVisibility(blank ? View.GONE : View.VISIBLE);
        submit(() -> {
            try {
                FederationAccount.Access loaded = FederationAccount.access(this);
                runOnUiThread(() -> {
                    accessProgress.setVisibility(View.GONE);
                    counters.setVisibility(View.VISIBLE);
                    applyAccess(loaded);
                });
            } catch (Exception error) {
                runOnUiThread(() -> accessProgress.setVisibility(View.GONE));
                showError(error.getMessage());
            }
        });
    }

    private void applyAccess(@Nullable FederationAccount.Access access) {
        if (access == null || !access.enabled) {
            nodes.setText("-");
            nodesMeta.setText(R.string.wings_account_no_access);
            return;
        }
        nodes.setText(getString(R.string.wings_account_nodes, access.nodes));
        nodesMeta.setText(
            access.nodesEntitled > access.nodes
                ? getString(R.string.wings_account_nodes_short, access.nodesEntitled)
                : ""
        );
        applyTraffic(access);
        applySpeed(access);
        applyTrust(access);
        // Аватар мог смениться в панели, и тогда версия в адресе уже другая
        FederationAccount.rememberAvatarVersion(this, access.avatarVersion);
        loadAvatar();
        // Подписка заводится сама: вход в аккаунт - и есть согласие её получить
        if (!TextUtils.isEmpty(access.subscriptionUrl)) {
            submit(() -> FederationSubscription.ensure(this, access.subscriptionUrl));
        }
    }

    // Потолок приезжает в заголовке подписки, как у любого нормального сервиса.
    // Пока человек чист, потолка нет вовсе - он появляется только как наказание,
    // и тогда цифру надо видеть заранее, а не упираться в неё на полном ходу
    private void applyTraffic(@NonNull FederationAccount.Access access) {
        String used = UiFormatter.formatBytes(this, access.usedBytes);
        long cap = 0L;
        for (XraySubscription subscription : XrayStore.getSubscriptions(this)) {
            if (subscription != null && FederationSubscription.ID.equals(subscription.id)) {
                cap = subscription.advertisedTotalBytes;
                break;
            }
        }
        if (cap > 0L) {
            traffic.setText(getString(R.string.wings_account_traffic_of, used, UiFormatter.formatBytes(this, cap)));
            trafficMeta.setText(R.string.wings_account_traffic_caption);
            return;
        }
        traffic.setText(used);
        trafficMeta.setText(R.string.wings_account_traffic_unlimited);
    }

    /** Полоса доверия читается так же, как полоса трафика у подписки */
    private void applyTrust(@NonNull FederationAccount.Access access) {
        if (TextUtils.isEmpty(access.trustBand)) {
            trustBar.setVisibility(View.GONE);
            return;
        }
        trustBar.setVisibility(View.VISIBLE);
        trustProgress.setProgress(Math.max(0, Math.min(100, access.trustConfidence)));
        trust.setText(getString(R.string.wings_account_trust, access.trustConfidence));
    }

    /** Потолок вниз и вверх, как его выдал оракул. Цвета те же, что у трафика */
    private void applySpeed(@NonNull FederationAccount.Access access) {
        if (access.downlinkBps == 0 && access.uplinkBps == 0) {
            speedDown.setText(R.string.wings_account_speed_unlimited);
            speedUp.setText("");
            return;
        }
        speedDown.setText(UiFormatter.formatBytesPerSecond(this, access.downlinkBps));
        speedUp.setText(UiFormatter.formatBytesPerSecond(this, access.uplinkBps));
    }

    private void showError(@Nullable String message) {
        if (isFinishing() || isDestroyed() || Thread.currentThread().isInterrupted()) {
            // Экран закрыли, и запрос оборвали мы сами: жаловаться не на что
            return;
        }
        runOnUiThread(() -> {
            signInError.setText(TextUtils.isEmpty(message) ? getString(R.string.wings_account_network_error) : message);
            signInError.setVisibility(View.VISIBLE);
        });
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
}
