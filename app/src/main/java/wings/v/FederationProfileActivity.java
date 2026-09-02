package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.FederationAccount;

/** Информация профиля: что можно поменять в аккаунте, не открывая панель */
public final class FederationProfileActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView login;
    private EditText currentPassword;
    private EditText newPassword;
    private EditText repeatPassword;
    private TextView error;
    private ProgressBar progress;

    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, FederationProfileActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_federation_profile);

        ToolbarLayout toolbar = findViewById(R.id.toolbar_layout);
        toolbar.setShowNavigationButtonAsBack(true);

        login = findViewById(R.id.federation_profile_login);
        currentPassword = findViewById(R.id.federation_profile_password_current);
        newPassword = findViewById(R.id.federation_profile_password_new);
        repeatPassword = findViewById(R.id.federation_profile_password_repeat);
        error = findViewById(R.id.federation_profile_error);
        progress = findViewById(R.id.federation_profile_progress);

        login.setText(FederationAccount.username(this));
        findViewById(R.id.federation_profile_save_password).setOnClickListener(v -> changePassword());
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

    private void changePassword() {
        String current = text(currentPassword);
        String next = text(newPassword);
        String repeat = text(repeatPassword);
        if (TextUtils.isEmpty(current) || TextUtils.isEmpty(next)) {
            return;
        }
        if (!next.equals(repeat)) {
            showError(getString(R.string.federation_profile_password_mismatch));
            return;
        }
        setBusy(true);
        submit(() -> {
            try {
                FederationAccount.changePassword(this, current, next);
                runOnUiThread(() -> {
                    setBusy(false);
                    currentPassword.setText("");
                    newPassword.setText("");
                    repeatPassword.setText("");
                    error.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.federation_profile_password_changed, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> setBusy(false));
                showError(failure.getMessage());
            }
        });
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        findViewById(R.id.federation_profile_save_password).setEnabled(!busy);
    }

    private String text(EditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    private void showError(@Nullable String message) {
        if (isFinishing() || isDestroyed() || Thread.currentThread().isInterrupted()) {
            // Экран закрыли, и запрос оборвали мы сами: жаловаться не на что
            return;
        }
        runOnUiThread(() -> {
            error.setText(TextUtils.isEmpty(message) ? getString(R.string.federation_account_error) : message);
            error.setVisibility(View.VISIBLE);
        });
    }
}
