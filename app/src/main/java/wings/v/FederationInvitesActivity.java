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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import dev.oneuiproject.oneui.qr.app.QrScanActivity;
import dev.oneuiproject.oneui.widget.QRImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.FederationAccount;
import wings.v.core.InviteCode;

/**
 * Приглашения: свой код показывают с экрана, чужой вводят или снимают камерой.
 *
 * <p>В дерево встают один раз, поэтому у того, кого уже пригласили, второй код
 * ничего не поменяет - и говорить об этом надо до похода на сервер.
 */
public final class FederationInvitesActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private View qrFrame;
    private View qrSkeleton;
    private QRImageView qr;
    private TextView code;
    private TextView uses;
    private TextView hint;
    private TextView error;
    private TextView redeemState;
    private EditText redeemInput;
    private ProgressBar progress;
    private ProgressBar redeemProgress;
    private TextView redeemError;
    private View share;

    private String link = "";

    private final ActivityResultLauncher<Intent> scan = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getData() == null) {
                return;
            }
            String scanned = result.getData().getStringExtra(QrScanActivity.EXTRA_QR_SCANNER_RESULT);
            String parsed = InviteCode.parse(scanned);
            if (parsed == null) {
                complainRedeem(getString(R.string.invite_scan_failed));
                return;
            }
            redeemInput.setText(parsed);
            redeem(parsed);
        }
    );

    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, FederationInvitesActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_federation_invites);

        ToolbarLayout toolbar = findViewById(R.id.toolbar_layout);
        toolbar.setShowNavigationButtonAsBack(true);

        qrFrame = findViewById(R.id.invite_qr_frame);
        qrSkeleton = findViewById(R.id.invite_qr_skeleton);
        qr = findViewById(R.id.invite_qr);
        // Иконка приложения в середине кода: так видно, чей это QR
        try {
            qr.setIcon(getPackageManager().getApplicationIcon(getApplicationInfo()));
        } catch (Exception missing) {
            // Без иконки код всё равно читается
        }
        code = findViewById(R.id.invite_code);
        uses = findViewById(R.id.invite_uses);
        hint = findViewById(R.id.invite_hint);
        error = findViewById(R.id.invite_error);
        redeemState = findViewById(R.id.invite_redeem_state);
        redeemInput = findViewById(R.id.invite_redeem_input);
        progress = findViewById(R.id.invite_progress);
        redeemProgress = findViewById(R.id.invite_redeem_progress);
        redeemError = findViewById(R.id.invite_redeem_error);
        share = findViewById(R.id.invite_share);

        findViewById(R.id.invite_create).setOnClickListener(v -> createInvite());
        findViewById(R.id.invite_redeem).setOnClickListener(v -> redeem(text(redeemInput)));
        findViewById(R.id.invite_scan).setOnClickListener(v -> openScanner());
        share.setOnClickListener(v -> shareLink());

        load();
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

    private void load() {
        // Открываемся прошлым ответом: пустой список на секунду читается как
        // "приглашений нет"
        FederationAccount.Invites cached = FederationAccount.cachedInvites(this);
        if (cached != null) {
            apply(cached);
        }
        setBusy(cached == null);
        submit(() -> {
            try {
                FederationAccount.Invites loaded = FederationAccount.invites(this);
                runOnUiThread(() -> {
                    setBusy(false);
                    apply(loaded);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> setBusy(false));
                complain(failure.getMessage());
            }
        });
    }

    private void apply(@NonNull FederationAccount.Invites invites) {
        findViewById(R.id.invite_create).setEnabled(invites.mayInvite);
        if (!invites.mayInvite && !TextUtils.isEmpty(invites.reason)) {
            hint.setText(invites.reason);
        } else {
            hint.setText(R.string.federation_invites_hint);
        }

        FederationAccount.Invite live = null;
        for (FederationAccount.Invite invite : invites.list) {
            if (!invite.spent) {
                live = invite;
                break;
            }
        }
        showInvite(live);
    }

    /** Показывает код, который сейчас можно кому-то отдать */
    private void showInvite(@Nullable FederationAccount.Invite invite) {
        boolean present = invite != null && !TextUtils.isEmpty(invite.token);
        // Ответ пришёл: либо показываем код, либо убираем и скелетон - висеть ему
        // тут нечего, кода просто нет
        qrFrame.setVisibility(present ? View.VISIBLE : View.GONE);
        qrSkeleton.setVisibility(View.GONE);
        code.setVisibility(present ? View.VISIBLE : View.GONE);
        uses.setVisibility(present ? View.VISIBLE : View.GONE);
        share.setVisibility(present ? View.VISIBLE : View.GONE);
        if (!present) {
            link = "";
            return;
        }
        // В QR уходит ссылка: её понимает и системная камера, и наш сканер
        link = TextUtils.isEmpty(invite.link)
            ? InviteCode.link(FederationAccount.panelUrl(this), invite.token)
            : invite.link;
        qr.setContent(link);
        qr.invalidate();
        code.setText(invite.token);
        // Ноль в потолке - это код без предела, и писать "из 0" тут нечего
        uses.setText(
            invite.maxUses > 0
                ? getString(R.string.federation_invites_uses, invite.useCount, invite.maxUses)
                : getString(R.string.federation_invites_uses_unlimited, invite.useCount)
        );
    }

    private void createInvite() {
        setBusy(true);
        submit(() -> {
            try {
                FederationAccount.Invite created = FederationAccount.createInvite(this);
                runOnUiThread(() -> {
                    setBusy(false);
                    showInvite(created);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> setBusy(false));
                complain(failure.getMessage());
            }
        });
    }

    private void redeem(@Nullable String value) {
        String token = value == null ? "" : value.trim();
        if (TextUtils.isEmpty(token)) {
            return;
        }
        setRedeemBusy(true);
        submit(() -> {
            try {
                FederationAccount.redeemInvite(this, token);
                runOnUiThread(() -> {
                    setRedeemBusy(false);
                    redeemInput.setText("");
                    redeemState.setText(R.string.federation_invites_already);
                    redeemState.setVisibility(View.VISIBLE);
                    Toast.makeText(this, R.string.invite_scan_done, Toast.LENGTH_LONG).show();
                    load();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    setRedeemBusy(false);
                    complainRedeem(failure.getMessage());
                });
            }
        });
    }

    private void openScanner() {
        scan.launch(QrScanActivity.Companion.createIntent(this, getString(R.string.qr_scan_title)));
    }

    private void shareLink() {
        if (TextUtils.isEmpty(link)) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, link);
        startActivity(Intent.createChooser(intent, getString(R.string.federation_invites_share)));
    }

    /**
     * Занятость блока приглашения.
     *
     * <p>Скелетон поднимается только тогда, когда кода на экране ещё нет: если он
     * уже нарисован, подменять его крутилкой незачем, а после ошибки он так и
     * остался бы крутиться вечно.
     */
    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        boolean hasCode = qrFrame.getVisibility() == View.VISIBLE;
        if (busy && !hasCode) {
            qrSkeleton.setVisibility(View.VISIBLE);
        } else if (!busy) {
            qrSkeleton.setVisibility(View.GONE);
        }
        findViewById(R.id.invite_create).setEnabled(!busy);
    }

    /** Занятость применения кода: своя кнопка, своя крутилка */
    private void setRedeemBusy(boolean busy) {
        redeemProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        findViewById(R.id.invite_redeem).setEnabled(!busy);
        findViewById(R.id.invite_scan).setEnabled(!busy);
        if (busy) {
            redeemError.setVisibility(View.GONE);
        }
    }

    private String text(EditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    /** Ошибка набранного кода живёт под своей кнопкой, а не в блоке приглашения */
    private void complainRedeem(@Nullable String message) {
        redeemError.setText(TextUtils.isEmpty(message) ? getString(R.string.invite_scan_failed) : message);
        redeemError.setVisibility(View.VISIBLE);
    }

    private void complain(@Nullable String message) {
        if (isFinishing() || isDestroyed() || Thread.currentThread().isInterrupted()) {
            return;
        }
        runOnUiThread(() -> {
            error.setText(TextUtils.isEmpty(message) ? getString(R.string.invite_scan_failed) : message);
            error.setVisibility(View.VISIBLE);
        });
    }
}
