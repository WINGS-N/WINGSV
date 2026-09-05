package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.FederationAccount;
import wings.v.core.UiFormatter;

/**
 * Свои серверы, отданные в федерацию: сколько с них прошло и какой потолок стоит.
 *
 * <p>Это донорская сторона админа, а не общий флот: чужих машин тут не видно и
 * видно быть не должно.
 */
public final class FederationDonorActivity extends AppCompatActivity {

    /** Гигабайт в байтах: лимит человек называет в них, а сервер считает в байтах */
    private static final long GB = 1024L * 1024L * 1024L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ProgressBar progress;
    private View counters;
    private View separator;
    private View connect;
    private View connectFrame;
    private ProgressBar connectProgress;
    private ViewGroup nodeList;
    private TextView error;
    private TextView nodes;
    private TextView nodesMeta;
    private TextView given;
    private TextView givenMeta;
    private ProgressBar budgetBar;
    private TextView budgetText;

    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, FederationDonorActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_federation_donor);

        ToolbarLayout toolbar = findViewById(R.id.toolbar_layout);
        toolbar.setShowNavigationButtonAsBack(true);

        progress = findViewById(R.id.donor_progress);
        counters = findViewById(R.id.donor_counters);
        separator = findViewById(R.id.donor_nodes_separator);
        connect = findViewById(R.id.donor_connect);
        connectFrame = findViewById(R.id.donor_connect_frame);
        connectProgress = findViewById(R.id.donor_connect_progress);
        nodeList = findViewById(R.id.donor_node_list);
        error = findViewById(R.id.donor_error);
        nodes = findViewById(R.id.donor_nodes);
        nodesMeta = findViewById(R.id.donor_nodes_meta);
        given = findViewById(R.id.donor_given);
        givenMeta = findViewById(R.id.donor_given_meta);
        budgetBar = findViewById(R.id.donor_budget_bar);
        budgetText = findViewById(R.id.donor_budget_text);

        connect.setOnClickListener(v -> askBudgetForNewNode());
        load();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    /**
     * Кидает работу в фон, пока экран жив: ответ приходит и после закрытия, а пул
     * к тому моменту прибит, и голый execute роняет приложение нахуй
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

    private void load() {
        // Открываемся тем, что показывали в прошлый раз: пустой экран на секунду
        // выглядит так, будто серверов нет вовсе
        FederationAccount.Donor cached = FederationAccount.cachedDonor(this);
        if (cached != null) {
            apply(cached);
        }
        progress.setVisibility(cached == null ? View.VISIBLE : View.GONE);
        submit(() -> {
            try {
                FederationAccount.Donor donor = FederationAccount.donor(this);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    apply(donor);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> progress.setVisibility(View.GONE));
                complain(failure.getMessage());
            }
        });
    }

    private void apply(@NonNull FederationAccount.Donor donor) {
        if (!TextUtils.isEmpty(donor.error)) {
            complain(donor.error);
        }
        counters.setVisibility(View.VISIBLE);
        connectFrame.setVisibility(View.VISIBLE);
        separator.setVisibility(donor.list.isEmpty() ? View.GONE : View.VISIBLE);

        nodes.setText(String.valueOf(donor.nodes));
        nodesMeta.setText(donor.nodesOnline + " " + getString(R.string.federation_donor_online));
        given.setText(UiFormatter.formatBytes(this, donor.usedBytes));
        givenMeta.setText(
            donor.probeBytes > 0
                ? getString(R.string.wings_account_probe_meta, UiFormatter.formatBytes(this, donor.probeBytes))
                : ""
        );
        applyBudget(budgetBar, budgetText, donor.usedBytes, donor.declaredBudgetBytes);

        nodeList.removeAllViews();
        for (FederationAccount.DonorNode node : donor.list) {
            nodeList.addView(nodeCard(node));
        }
        if (donor.list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.federation_donor_empty);
            empty.setPadding(24, 12, 24, 12);
            nodeList.addView(empty);
        }
    }

    private View nodeCard(@NonNull FederationAccount.DonorNode node) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_donor_node, nodeList, false);
        TextView name = card.findViewById(R.id.donor_node_name);
        TextView state = card.findViewById(R.id.donor_node_state);
        TextView versions = card.findViewById(R.id.donor_node_versions);

        name.setText(TextUtils.isEmpty(node.hostname) ? node.nodeId : node.hostname);
        state.setText(node.online ? R.string.xray_routing_badge_ready : R.string.federation_donor_offline);
        state.setBackgroundResource(node.online ? R.drawable.bg_profile_ping_good : R.drawable.bg_profile_ping_bad);
        state.setTextColor(getColor(node.online ? R.color.traffic_rx : R.color.traffic_tx));
        versions.setText(
            (TextUtils.isEmpty(node.xrayVersion) ? "-" : node.xrayVersion) +
                "  |  " +
                (TextUtils.isEmpty(node.vktpVersion) ? "-" : node.vktpVersion)
        );
        applyBudget(
            card.findViewById(R.id.donor_node_bar),
            card.findViewById(R.id.donor_node_usage),
            node.usedBytes,
            node.declaredBudgetBytes
        );
        View budgetButton = card.findViewById(R.id.donor_node_budget);
        View budgetSpinner = card.findViewById(R.id.donor_node_budget_progress);
        budgetButton.setOnClickListener(v -> askBudget(node, budgetButton, budgetSpinner));
        return card;
    }

    /**
     * Полоса лимита. Показывает ОСТАТОК, а не сожранное: полная полоса значит,
     * что лимит цел, и тает по мере того, как его съедают. Ровно как полоса
     * трафика у подписок, чтобы человеку не приходилось читать две разные
     */
    private void applyBudget(ProgressBar bar, TextView label, long used, long declared) {
        double remainingRatio = declared > 0 ? Math.max(0d, (double) (declared - used) / (double) declared) : 1d;
        bar.setProgress((int) Math.round(remainingRatio * 1000));
        bar.setProgressTintList(
            android.content.res.ColorStateList.valueOf(
                getColor(FederationAccountActivity.quotaColor(declared > 0 ? remainingRatio : 1d))
            )
        );
        label.setText(
            getString(
                R.string.federation_donor_budget_used,
                UiFormatter.formatBytes(this, used),
                declared > 0 ? UiFormatter.formatBytes(this, declared) : "-"
            )
        );
    }

    private void askBudget(@NonNull FederationAccount.DonorNode node, View button, View spinner) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setHint(R.string.federation_donor_budget_title);
        if (node.declaredBudgetBytes > 0) {
            field.setText(String.valueOf(node.declaredBudgetBytes / GB));
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.federation_donor_budget_title)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = field.getText() == null ? "" : field.getText().toString().trim();
                if (TextUtils.isEmpty(value)) {
                    return;
                }
                saveBudget(node.nodeId, parseGb(value), button, spinner);
            })
            .show();
    }

    /** Мусор в поле не должен ронять приложение через NumberFormatException */
    private long parseGb(@NonNull String value) {
        try {
            return Math.max(0L, Long.parseLong(value.trim())) * GB;
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    private void saveBudget(@NonNull String nodeId, long bytes, View button, View spinner) {
        if (bytes <= 0L) {
            complain(getString(R.string.federation_donor_budget_zero));
            return;
        }
        button.setEnabled(false);
        spinner.setVisibility(View.VISIBLE);
        submit(() -> {
            try {
                FederationAccount.setNodeBudget(this, nodeId, bytes);
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    spinner.setVisibility(View.GONE);
                    load();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    spinner.setVisibility(View.GONE);
                });
                complain(failure.getMessage());
            }
        });
    }

    /** Команда подключения: её выполняют на самой машине, поэтому её отдают текстом */
    private void showEnrollCommand(long budgetGb) {
        setConnecting(true);
        submit(() -> {
            try {
                String command = FederationAccount.enrollCommand(this, budgetGb);
                runOnUiThread(() -> {
                    setConnecting(false);
                    showCommandDialog(command);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> setConnecting(false));
                complain(failure.getMessage());
            }
        });
    }

    /** Потолок спрашивается до выписки: после захода сервера его уже поздно менять */
    private void askBudgetForNewNode() {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setHint(R.string.federation_donor_budget_title);
        new AlertDialog.Builder(this)
            .setTitle(R.string.federation_donor_budget_title)
            .setMessage(R.string.federation_donor_budget_hint)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.federation_donor_connect, (dialog, which) -> {
                String value = field.getText() == null ? "" : field.getText().toString().trim();
                showEnrollCommand(TextUtils.isEmpty(value) ? 0L : Long.parseLong(value));
            })
            .show();
    }

    /** Токен выписывает голова, и это не мгновенно: ждём прямо на кнопке */
    private void setConnecting(boolean busy) {
        connectProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        connect.setEnabled(!busy);
    }

    private void showCommandDialog(@NonNull String command) {
        TextView view = new TextView(this);
        view.setText(command);
        view.setTextIsSelectable(true);
        view.setPadding(48, 24, 48, 24);
        new AlertDialog.Builder(this)
            .setTitle(R.string.federation_donor_command_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.wings_account_subscription_copy, (dialog, which) -> {
                android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("enroll", command));
                    Toast.makeText(this, R.string.wings_account_subscription_copied, Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }

    /**
     * Ошибку показываем диалогом: строчка наверху экрана теряется, а из окна
     * текст можно прочитать целиком и выделить
     */
    private void complain(@Nullable String message) {
        if (isFinishing() || isDestroyed() || Thread.currentThread().isInterrupted()) {
            return;
        }
        String text = TextUtils.isEmpty(message) ? getString(R.string.wings_account_error) : message;
        runOnUiThread(() -> {
            error.setVisibility(View.GONE);
            new AlertDialog.Builder(this)
                .setTitle(R.string.wings_account_error_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        });
    }
}
