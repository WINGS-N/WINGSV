package wings.v;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.FederationAccount;
import wings.v.core.XrayStore;
import wings.v.core.XraySubscription;
import wings.v.core.XraySubscriptionUpdater;

/** Экран аккаунта федерации: вход, выданный доступ и подписка */
public class FederationAccountActivity extends AppCompatActivity {

    /** Идентификатор подписки федерации: он же не даёт завести её дважды */
    private static final String FEDERATION_SUBSCRIPTION_ID = "wings-federation";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private View signInCard;
    private View accountCard;
    private EditText login;
    private EditText password;
    private EditText code;
    private TextView signInError;
    private TextView username;
    private TextView trust;
    private TextView nodes;
    private TextView subscription;
    private Button applySubscription;

    private String subscriptionUrl = "";

    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, FederationAccountActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_federation_account);

        signInCard = findViewById(R.id.federation_sign_in_card);
        accountCard = findViewById(R.id.federation_account_card);
        login = findViewById(R.id.federation_login);
        password = findViewById(R.id.federation_password);
        code = findViewById(R.id.federation_code);
        signInError = findViewById(R.id.federation_sign_in_error);
        username = findViewById(R.id.federation_username);
        trust = findViewById(R.id.federation_trust);
        nodes = findViewById(R.id.federation_nodes);
        subscription = findViewById(R.id.federation_subscription);
        applySubscription = findViewById(R.id.federation_apply_subscription);

        findViewById(R.id.federation_sign_in).setOnClickListener(v -> signIn());
        findViewById(R.id.federation_sign_in_matrix).setOnClickListener(v -> openBrowserLogin());
        findViewById(R.id.federation_sign_out).setOnClickListener(v -> signOut());
        applySubscription.setOnClickListener(v -> addSubscription());

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
        accountCard.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (!signedIn) {
            return;
        }
        username.setText(FederationAccount.username(this));
        loadAccess();
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
            nodes.setText(R.string.federation_account_no_access);
            subscription.setText("");
            applySubscription.setVisibility(View.GONE);
            return;
        }
        subscriptionUrl = access.subscriptionUrl;
        nodes.setText(getString(R.string.federation_account_nodes, access.nodes));
        subscription.setText(access.subscriptionUrl);
        applySubscription.setVisibility(TextUtils.isEmpty(access.subscriptionUrl) ? View.GONE : View.VISIBLE);
        if (TextUtils.isEmpty(access.trustBand)) {
            trust.setText("");
            return;
        }
        trust.setText(getString(R.string.federation_account_trust, access.trustConfidence, access.trustBand));
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
