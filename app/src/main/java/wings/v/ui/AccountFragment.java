package wings.v.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.FederationAvatarActivity;
import wings.v.FederationDonorActivity;
import wings.v.FederationInvitesActivity;
import wings.v.FederationProfileActivity;
import wings.v.FederationSubscriptionActivity;
import wings.v.R;
import wings.v.core.AvatarFetcher;
import wings.v.core.FederationAccount;
import wings.v.core.FederationSubscription;
import wings.v.core.UiFormatter;
import wings.v.core.XrayStore;
import wings.v.core.XraySubscription;

/**
 * Аккаунт федерации: вход, выданный доступ и подписка.
 *
 * <p>Живёт фрагментом, потому что показывают его двое: свой таб в главном окне и
 * активити, в которую приводит ссылка из браузера после входа через Matrix
 */
public class AccountFragment extends Fragment {

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
    private View quotaBar;
    private ProgressBar quotaProgress;
    private TextView quotaText;
    private TextView speedDown;
    private TextView speedUp;
    private ImageView avatar;

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_wings_account, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);
        signInCard = root.findViewById(R.id.federation_sign_in_card);
        profileCard = root.findViewById(R.id.federation_profile_card);
        counters = root.findViewById(R.id.federation_counters);
        sections = root.findViewById(R.id.federation_sections);
        panelSections = root.findViewById(R.id.federation_panel_sections);
        signOutButton = root.findViewById(R.id.federation_sign_out);
        signInProgress = root.findViewById(R.id.federation_sign_in_progress);
        signOutProgress = root.findViewById(R.id.federation_sign_out_progress);
        accessProgress = root.findViewById(R.id.federation_access_progress);
        login = root.findViewById(R.id.federation_login);
        password = root.findViewById(R.id.federation_password);
        code = root.findViewById(R.id.federation_code);
        signInError = root.findViewById(R.id.federation_sign_in_error);
        username = root.findViewById(R.id.federation_username);
        trust = root.findViewById(R.id.federation_trust);
        trustBar = root.findViewById(R.id.federation_trust_bar);
        trustProgress = root.findViewById(R.id.federation_trust_progress);
        nodes = root.findViewById(R.id.federation_nodes);
        nodesMeta = root.findViewById(R.id.federation_nodes_meta);
        traffic = root.findViewById(R.id.federation_traffic);
        trafficMeta = root.findViewById(R.id.federation_traffic_caption);
        quotaBar = root.findViewById(R.id.federation_quota_bar);
        quotaProgress = root.findViewById(R.id.federation_quota_progress);
        quotaText = root.findViewById(R.id.federation_quota);
        speedDown = root.findViewById(R.id.federation_speed_down);
        speedUp = root.findViewById(R.id.federation_speed_up);
        avatar = root.findViewById(R.id.federation_avatar);

        root.findViewById(R.id.federation_sign_in).setOnClickListener(v -> signIn());
        root.findViewById(R.id.federation_sign_in_matrix).setOnClickListener(v -> openBrowserLogin());
        root.findViewById(R.id.federation_sign_out).setOnClickListener(v -> signOut());
        root
            .findViewById(R.id.federation_row_subscription)
            .setOnClickListener(v -> startActivity(FederationSubscriptionActivity.createIntent(requireContext())));
        root
            .findViewById(R.id.federation_row_profile)
            .setOnClickListener(v -> startActivity(FederationProfileActivity.createIntent(requireContext())));
        root.findViewById(R.id.federation_row_clients).setOnClickListener(v -> openPanelSection("/admin/clients"));
        root
            .findViewById(R.id.federation_row_donor)
            .setOnClickListener(v -> startActivity(FederationDonorActivity.createIntent(requireContext())));
        root
            .findViewById(R.id.federation_row_invites)
            .setOnClickListener(v -> startActivity(FederationInvitesActivity.createIntent(requireContext())));
        avatar.setOnClickListener(v -> startActivity(FederationAvatarActivity.createIntent(requireContext())));

        render();
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
                FederationAccount.Session session = FederationAccount.exchangeCode(requireContext(), code);
                FederationAccount.store(requireContext(), session);
                onUi(this::render);
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
                FederationAccount.Session session = FederationAccount.signIn(
                    requireContext(),
                    user,
                    pass,
                    secondFactor
                );
                FederationAccount.store(requireContext(), session);
                onUi(() -> {
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
                onUi(() -> {
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
                onUi(() -> setSigningIn(false));
                showError(error.getMessage());
            }
        });
    }

    /** Вход идёт по сети: без индикатора нажатие выглядит потерянным */
    private void setSigningIn(boolean busy) {
        signInProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        requireView().findViewById(R.id.federation_sign_in).setEnabled(!busy);
        ((Button) requireView().findViewById(R.id.federation_sign_in)).setText(
            busy ? R.string.wings_account_signing_in : R.string.wings_account_sign_in
        );
    }

    /** Раздел, который в приложении ещё не собран, открывается в панели */
    private void openPanelSection(@NonNull String path) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(FederationAccount.panelUrl(requireContext()) + path)));
    }

    /**
     * Применяет код приглашения, приехавший ссылкой из панели.
     *
     * <p>Без аккаунта применять его некуда, поэтому код запоминается и уходит в
     * дело сразу после входа - переспрашивать его у человека второй раз незачем
     */
    private void applyInvite(@NonNull String code) {
        if (!FederationAccount.isSignedIn(requireContext())) {
            pendingInvite = code;
            signInError.setText(R.string.invite_scan_sign_in_first);
            signInError.setVisibility(View.VISIBLE);
            login.requestFocus();
            return;
        }
        submit(() -> {
            try {
                FederationAccount.redeemInvite(requireContext(), code);
                onUi(() -> Toast.makeText(requireContext(), R.string.invite_scan_done, Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void openBrowserLogin() {
        Uri target = Uri.parse(FederationAccount.panelUrl(requireContext()) + "/app/link");
        startActivity(new Intent(Intent.ACTION_VIEW, target));
    }

    private void signOut() {
        setSigningOut(true);
        submit(() -> {
            FederationAccount.signOut(requireContext());
            // Подписка выдана этому аккаунту: без него она мертва
            FederationSubscription.remove(requireContext());
            AvatarFetcher.clearCache(requireContext());
            onUi(() -> {
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
        boolean signedIn = FederationAccount.isSignedIn(requireContext());
        signInCard.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        profileCard.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        counters.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        sections.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        signOutButton.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (!signedIn) {
            return;
        }
        username.setText(FederationAccount.username(requireContext()));
        panelSections.setVisibility(FederationAccount.hasPanel(requireContext()) ? View.VISIBLE : View.GONE);
        // Сначала прошлые цифры, потом свежие: пустые карточки на секунду выглядят
        // так, будто доступа нет
        applyAccess(FederationAccount.cachedAccess(requireContext()));
        loadAvatar();
        loadAccess();
    }

    /** Аватар грузится отдельно: без него экран уже полный, с ним - живой */
    private void loadAvatar() {
        String url = FederationAccount.avatarUrl(requireContext());
        if (TextUtils.isEmpty(url)) {
            return;
        }
        submit(() -> {
            android.graphics.Bitmap bitmap = AvatarFetcher.cached(requireContext(), url);
            if (bitmap == null) {
                return;
            }
            onUi(() -> avatar.setImageDrawable(AvatarFetcher.circular(getResources(), bitmap)));
        });
    }

    private void loadAccess() {
        // Кэш уже показан, поэтому индикатор нужен только при первом заходе
        boolean blank = FederationAccount.cachedAccess(requireContext()) == null;
        accessProgress.setVisibility(blank ? View.VISIBLE : View.GONE);
        counters.setVisibility(blank ? View.GONE : View.VISIBLE);
        submit(() -> {
            try {
                FederationAccount.Access loaded = FederationAccount.access(requireContext());
                onUi(() -> {
                    accessProgress.setVisibility(View.GONE);
                    counters.setVisibility(View.VISIBLE);
                    applyAccess(loaded);
                });
            } catch (Exception error) {
                onUi(() -> accessProgress.setVisibility(View.GONE));
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
        // Сколько выдано, и всё. Сколько положено по доверию - наша внутренняя
        // арифметика, человеку от неё ни тепло ни холодно
        nodes.setText(getString(R.string.wings_account_nodes, access.nodes));
        nodesMeta.setText("");
        applyTraffic(access);
        applySpeed(access);
        applyTrust(access);
        // Аватар мог смениться в панели, и тогда версия в адресе уже другая
        FederationAccount.rememberAvatarVersion(requireContext(), access.avatarVersion);
        loadAvatar();
        // Подписка заводится сама: вход в аккаунт - и есть согласие её получить
        if (!TextUtils.isEmpty(access.subscriptionUrl)) {
            submit(() -> FederationSubscription.ensure(requireContext(), access.subscriptionUrl));
        }
    }

    // Потолок приезжает в заголовке подписки, как у любого нормального сервиса.
    // Пока человек чист, потолка нет вовсе - он появляется только как наказание,
    // и тогда цифру надо видеть заранее, а не упираться в неё на полном ходу
    private void applyTraffic(@NonNull FederationAccount.Access access) {
        String used = UiFormatter.formatBytes(requireContext(), access.usedBytes);
        long cap = 0L;
        for (XraySubscription subscription : XrayStore.getSubscriptions(requireContext())) {
            if (subscription != null && FederationSubscription.ID.equals(subscription.id)) {
                cap = subscription.advertisedTotalBytes;
                break;
            }
        }
        if (cap > 0L) {
            traffic.setText(
                getString(R.string.wings_account_traffic_of, used, UiFormatter.formatBytes(requireContext(), cap))
            );
            trafficMeta.setText(R.string.wings_account_traffic_caption);
            applyQuotaBar(access.usedBytes, cap);
            return;
        }
        traffic.setText(used);
        trafficMeta.setText(R.string.wings_account_traffic_unlimited);
        quotaBar.setVisibility(View.GONE);
    }

    /** Полоса потолка: показывает ОСТАТОК и красится по нему, как у подписок */
    private void applyQuotaBar(long usedBytes, long cap) {
        quotaBar.setVisibility(View.VISIBLE);
        double remaining = Math.max(0d, (double) (cap - usedBytes) / (double) cap);
        quotaProgress.setProgress((int) Math.round(remaining * 1000));
        quotaProgress.setProgressTintList(
            android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(), quotaColor(remaining))
            )
        );
        // Формат тот же, что у подписок: сколько прошло из скольки. "Осталось X"
        // это своя выдумка, и человеку приходится считать в уме
        quotaText.setText(
            getString(
                R.string.xray_profiles_subscription_quota_used,
                UiFormatter.formatBytes(requireContext(), usedBytes),
                UiFormatter.formatBytes(requireContext(), cap)
            )
        );
    }

    /** Полоса доверия читается так же, как полоса трафика у подписки */
    private void applyTrust(@NonNull FederationAccount.Access access) {
        if (TextUtils.isEmpty(access.trustBand)) {
            trustBar.setVisibility(View.GONE);
            return;
        }
        trustBar.setVisibility(View.VISIBLE);
        int confidence = Math.max(0, Math.min(100, access.trustConfidence));
        // Цвет по ПОЛОСАМ доверия, а не по доле: 50 из 100 это ни хуя не "больше
        // половины, значит зелёный", а урезанный доступ, и человек обязан видеть
        // это без пояснений
        trustProgress.setProgress(confidence * 10);
        trustProgress.setProgressTintList(
            android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(requireContext(), trustColor(confidence))
            )
        );
        trust.setText(getString(R.string.wings_account_trust, access.trustConfidence));
    }

    /**
     * Цвет полосы доверия по полосам, которыми судит Oracle: от 60 полный
     * доступ, от 30 урезанный, ниже карантин
     */
    private static int trustColor(int confidence) {
        if (confidence < 30) {
            return R.color.wingsv_error;
        }
        if (confidence < 60) {
            return R.color.wingsv_warning;
        }
        return R.color.wingsv_success;
    }

    /**
     * Цвет полосы по остатку. Та же шкала, что у полосы трафика в подписках:
     * ниже десятой части красный, ниже двух пятых жёлтый, дальше зелёный
     */
    public static int quotaColor(double remainingRatio) {
        if (remainingRatio <= 0.1d) {
            return R.color.wingsv_error;
        }
        if (remainingRatio <= 0.4d) {
            return R.color.wingsv_warning;
        }
        return R.color.wingsv_success;
    }

    /** Потолок вниз и вверх, как его выдал оракул. Цвета те же, что у трафика */
    private void applySpeed(@NonNull FederationAccount.Access access) {
        if (access.downlinkBps == 0 && access.uplinkBps == 0) {
            speedDown.setText(R.string.wings_account_speed_unlimited);
            speedUp.setText("");
            return;
        }
        speedDown.setText(UiFormatter.formatBytesPerSecond(requireContext(), access.downlinkBps));
        speedUp.setText(UiFormatter.formatBytesPerSecond(requireContext(), access.uplinkBps));
    }

    private void showError(@Nullable String message) {
        if (!isAdded() || getView() == null || Thread.currentThread().isInterrupted()) {
            // Экран закрыли, и запрос оборвали мы сами: жаловаться не на что
            return;
        }
        onUi(() -> {
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
        if (io.isShutdown() || !isAdded()) {
            return;
        }
        try {
            io.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException dying) {
            // Экран закрыли ровно в этот момент - работать уже не для кого
        }
    }

    @Override
    public void onDestroyView() {
        io.shutdownNow();
        super.onDestroyView();
    }

    /** Код из браузера приносит активити: у фрагмента своего интента нет */
    public void acceptCode(@Nullable Intent intent) {
        handleCode(intent);
    }

    /**
     * Выполняет на UI-потоке, но только пока фрагмент жив.
     *
     * <p>Ответ из сети приходит и после того, как таб уехал с экрана, а поля
     * вьюх к этому моменту уже мертвы
     */
    private void onUi(Runnable work) {
        if (!isAdded() || getActivity() == null) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            if (isAdded() && getView() != null) {
                work.run();
            }
        });
    }
}
