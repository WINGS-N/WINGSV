package wings.v;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.preference.PreferenceManager;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.core.SubscriptionHwidStore;
import wings.v.core.WingsImportParser;
import wings.v.core.XrayStore;
import wings.v.core.XraySubscription;
import wings.v.core.XraySubscriptionUpdater;
import wings.v.databinding.ActivitySubscriptionsBinding;
import wings.v.databinding.ItemSubscriptionEntryBinding;
import wings.v.ui.SubscriptionUpdateSettingsFragment;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class SubscriptionsActivity extends AppCompatActivity {

    private final ExecutorService workExecutor = Executors.newSingleThreadExecutor();
    private final LinkedHashSet<String> selectedSubscriptionIds = new LinkedHashSet<>();
    private final LinkedHashMap<String, SubscriptionRowViews> rowViews = new LinkedHashMap<>();

    private ActivitySubscriptionsBinding binding;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesListener = (preferences, key) -> {
        if (!isSubscriptionUiPreference(key) || binding == null) {
            return;
        }
        binding.getRoot().post(this::refreshUi);
    };
    private final ArrayList<XraySubscription> currentSubscriptions = new ArrayList<>();
    private boolean selectionMode;
    private boolean refreshingSubscriptions;
    private String refreshingSubscriptionId = "";
    private OnBackPressedCallback selectionBackCallback;

    public static Intent createIntent(Context context) {
        return new Intent(context, SubscriptionsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySubscriptionsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        selectionBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                clearSelectionMode();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, selectionBackCallback);

        binding.rowAddSubscription.setTitle(getString(R.string.xray_subscriptions_add_title));
        binding.rowAddSubscription.setSummary(getString(R.string.xray_subscriptions_add_summary));
        binding.rowAddSubscription.setIcon(AppCompatResources.getDrawable(this, R.drawable.ic_add));
        binding.rowSubscriptionAutoRefresh.setTitle(getString(R.string.xray_subscriptions_auto_refresh_title));
        binding.rowSubscriptionHwid.setTitle(getString(R.string.subscription_hwid_title));
        binding.rowRefreshSubscriptionsNow.setTitle(getString(R.string.xray_subscriptions_refresh_now_title));
        binding.rowRefreshSubscriptionsNow.setSummary(getString(R.string.xray_subscriptions_refresh_now_summary));

        binding.rowAddSubscription.setOnClickListener(view -> {
            Haptics.softSelection(view);
            showSubscriptionDialog(null);
        });
        binding.rowSubscriptionAutoRefresh.setOnClickListener(view -> {
            Haptics.softSelection(view);
            startActivity(SubscriptionUpdateSettingsActivity.createIntent(this));
        });
        binding.rowSubscriptionHwid.setOnClickListener(view -> {
            Haptics.softSelection(view);
            startActivity(SubscriptionHwidSettingsActivity.createIntent(this));
        });
        binding.rowRefreshSubscriptionsNow.setOnClickListener(view -> {
            Haptics.softSelection(view);
            refreshSubscriptions();
        });
        binding.bottomTabSubscriptionSelection.inflateMenu(R.menu.menu_selection_actions, null);
        binding.bottomTabSubscriptionSelection.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_selection_share) {
                Haptics.softSelection(binding.bottomTabSubscriptionSelection);
                shareSelectedSubscriptions();
                return true;
            }
            if (item.getItemId() == R.id.menu_selection_delete) {
                Haptics.softSelection(binding.bottomTabSubscriptionSelection);
                deleteSelectedSubscriptions();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(
            preferencesListener
        );
        refreshUi();
    }

    @Override
    protected void onPause() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(
            preferencesListener
        );
        clearSelectionMode();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        workExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refreshUi() {
        refreshSubscriptionHwidRow();
        refreshSubscriptionsAutoRefreshRow();

        String lastError = XrayStore.getLastSubscriptionsError(this);
        long lastRefreshAt = XrayStore.getLastSubscriptionsRefreshAt(this);
        if (!TextUtils.isEmpty(lastError)) {
            binding.textSubscriptionHeaderSummary.setText(
                getString(R.string.xray_subscriptions_header_error, lastError)
            );
        } else if (lastRefreshAt > 0L) {
            binding.textSubscriptionHeaderSummary.setText(
                getString(
                    R.string.xray_subscriptions_header_last_refresh,
                    DateFormat.getDateTimeInstance().format(lastRefreshAt)
                )
            );
        } else {
            binding.textSubscriptionHeaderSummary.setText(R.string.xray_subscriptions_header_summary);
        }

        List<XraySubscription> subscriptions = XrayStore.getSubscriptions(this);
        currentSubscriptions.clear();
        currentSubscriptions.addAll(subscriptions);
        pruneSelection(subscriptions);
        binding.textSubscriptionsEmpty.setVisibility(subscriptions.isEmpty() ? View.VISIBLE : View.GONE);
        binding.layoutSubscriptionsList.setVisibility(subscriptions.isEmpty() ? View.GONE : View.VISIBLE);
        binding.containerSubscriptions.removeAllViews();
        rowViews.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int index = 0; index < subscriptions.size(); index++) {
            XraySubscription subscription = subscriptions.get(index);
            ItemSubscriptionEntryBinding rowBinding = ItemSubscriptionEntryBinding.inflate(
                inflater,
                binding.containerSubscriptions,
                false
            );
            rowBinding.textSubscriptionTitle.setText(
                TextUtils.isEmpty(subscription.title)
                    ? getString(R.string.xray_subscriptions_untitled)
                    : subscription.title
            );
            StringBuilder summary = new StringBuilder(subscription.url);
            if (subscription.lastUpdatedAt > 0L) {
                summary
                    .append('\n')
                    .append(
                        getString(
                            R.string.xray_subscriptions_last_updated_label,
                            DateFormat.getDateTimeInstance().format(subscription.lastUpdatedAt)
                        )
                    );
            }
            rowBinding.textSubscriptionSummary.setText(summary.toString());
            rowBinding.viewSubscriptionDivider.setVisibility(
                index == subscriptions.size() - 1 ? View.GONE : View.VISIBLE
            );
            rowBinding.checkboxSubscriptionSelected.setClickable(false);
            rowBinding.checkboxSubscriptionSelected.setFocusable(false);
            rowBinding.rowSubscriptionEntry.setOnClickListener(view -> {
                Haptics.softSelection(view);
                onSubscriptionClicked(subscription);
            });
            rowBinding.rowSubscriptionEntry.setOnLongClickListener(view -> {
                Haptics.softSelection(view);
                beginSelection(subscription.id);
                return true;
            });
            binding.containerSubscriptions.addView(rowBinding.getRoot());
            rowViews.put(subscription.id, new SubscriptionRowViews(subscription, rowBinding));
        }
        updateRefreshStateUi();
        updateSelectionUi();
        updateAllRowStates();
    }

    private boolean isSubscriptionUiPreference(@Nullable String key) {
        return (
            TextUtils.equals(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_JSON, key) ||
            TextUtils.equals(AppPrefs.KEY_XRAY_PROFILES_JSON, key) ||
            TextUtils.equals(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_AUTO_REFRESH_ENABLED, key) ||
            TextUtils.equals(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_LAST_REFRESH_AT, key) ||
            TextUtils.equals(AppPrefs.KEY_XRAY_SUBSCRIPTIONS_LAST_ERROR, key)
        );
    }

    private void refreshSubscriptionsAutoRefreshRow() {
        boolean enabled = XrayStore.isSubscriptionsAutoRefreshEnabled(this);
        binding.rowSubscriptionAutoRefresh.setSummary(
            enabled
                ? getString(
                      R.string.xray_subscriptions_auto_refresh_summary_interval,
                      SubscriptionUpdateSettingsFragment.formatRefreshIntervalMinutes(
                          XrayStore.getRefreshIntervalMinutes(this)
                      )
                  )
                : getString(R.string.xray_subscriptions_auto_refresh_summary_off)
        );
        binding.switchSubscriptionAutoRefresh.setOnCheckedChangeListener(null);
        binding.switchSubscriptionAutoRefresh.setChecked(enabled);
        binding.switchSubscriptionAutoRefresh.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Haptics.softSliderStep(buttonView);
            XrayStore.setSubscriptionsAutoRefreshEnabled(this, isChecked);
            refreshSubscriptionsAutoRefreshRow();
        });
    }

    private void refreshSubscriptionHwidRow() {
        SubscriptionHwidStore.SettingsModel settings = SubscriptionHwidStore.getSettings(this);
        binding.rowSubscriptionHwid.setSummary(SubscriptionHwidStore.getSubscriptionsRowSummary(this));
        binding.switchSubscriptionHwid.setOnCheckedChangeListener(null);
        binding.switchSubscriptionHwid.setChecked(settings.enabled);
        binding.switchSubscriptionHwid.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Haptics.softSliderStep(buttonView);
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean(AppPrefs.KEY_SUBSCRIPTION_HWID_ENABLED, isChecked)
                .apply();
            refreshSubscriptionHwidRow();
        });
    }

    private void showSubscriptionDialog(@Nullable XraySubscription existing) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = getResources().getDimensionPixelSize(android.R.dimen.app_icon_size) / 2;
        container.setPadding(padding, padding / 2, padding, 0);

        EditText titleInput = new EditText(this);
        titleInput.setHint(R.string.xray_subscriptions_title_hint);
        titleInput.setText(existing != null ? existing.title : "");
        container.addView(
            titleInput,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        );

        EditText urlInput = new EditText(this);
        urlInput.setHint(R.string.xray_subscriptions_url_hint);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(existing != null ? existing.url : "");
        container.addView(
            urlInput,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        );

        int dialogTitle =
            existing == null ? R.string.xray_subscriptions_add_title : R.string.xray_subscriptions_edit_title;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(container)
            .setNegativeButton(R.string.sharing_edit_dialog_cancel, null)
            .setPositiveButton(R.string.sharing_edit_dialog_save, (dialog, which) -> {
                String url = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
                if (TextUtils.isEmpty(url)) {
                    Toast.makeText(this, R.string.xray_subscriptions_url_required, Toast.LENGTH_SHORT).show();
                    return;
                }
                List<XraySubscription> subscriptions = new ArrayList<>(XrayStore.getSubscriptions(this));
                XraySubscription updated = new XraySubscription(
                    existing != null ? existing.id : null,
                    titleInput.getText() != null ? titleInput.getText().toString().trim() : "",
                    url,
                    "auto",
                    existing != null ? existing.refreshIntervalMinutes : XrayStore.getRefreshIntervalMinutes(this),
                    existing == null || existing.autoUpdate,
                    existing != null ? existing.lastUpdatedAt : 0L,
                    existing != null ? existing.advertisedUploadBytes : 0L,
                    existing != null ? existing.advertisedDownloadBytes : 0L,
                    existing != null ? existing.advertisedTotalBytes : 0L,
                    existing != null ? existing.advertisedExpireAt : 0L
                );
                if (existing != null) {
                    for (int index = 0; index < subscriptions.size(); index++) {
                        if (TextUtils.equals(subscriptions.get(index).id, existing.id)) {
                            subscriptions.set(index, updated);
                            XrayStore.setSubscriptions(this, subscriptions);
                            refreshUi();
                            return;
                        }
                    }
                }
                subscriptions.add(updated);
                XrayStore.setSubscriptions(this, subscriptions);
                refreshUi();
            });

        if (existing != null) {
            builder.setNeutralButton(R.string.xray_subscriptions_delete_title, (dialog, which) -> {
                List<XraySubscription> subscriptions = new ArrayList<>(XrayStore.getSubscriptions(this));
                subscriptions.removeIf(item -> TextUtils.equals(item.id, existing.id));
                XrayStore.setSubscriptions(this, subscriptions);
                refreshUi();
            });
        }
        builder.show();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void refreshSubscriptions() {
        if (refreshingSubscriptions) {
            return;
        }
        refreshingSubscriptions = true;
        refreshingSubscriptionId = "";
        updateRefreshStateUi();
        workExecutor.execute(() -> {
            String message;
            try {
                XraySubscriptionUpdater.RefreshResult result = XraySubscriptionUpdater.refreshAll(
                    this,
                    new XraySubscriptionUpdater.ProgressListener() {
                        @Override
                        public void onSubscriptionStarted(XraySubscription subscription) {
                            runOnUiThread(() -> {
                                refreshingSubscriptionId = subscription != null ? subscription.id : "";
                                updateRefreshStateUi();
                                updateAllRowStates();
                            });
                        }

                        @Override
                        public void onSubscriptionFinished(XraySubscription subscription, String error) {}
                    }
                );
                message = TextUtils.isEmpty(result.error)
                    ? getString(R.string.xray_subscriptions_refresh_success, result.profiles.size())
                    : getString(R.string.xray_subscriptions_refresh_partial, result.error);
            } catch (Exception error) {
                message = getString(R.string.xray_subscriptions_refresh_failed, error.getMessage());
            }
            String toastMessage = message;
            runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                refreshingSubscriptions = false;
                refreshingSubscriptionId = "";
                refreshUi();
                Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void onSubscriptionClicked(XraySubscription subscription) {
        if (subscription == null) {
            return;
        }
        if (selectionMode) {
            toggleSelection(subscription.id);
            return;
        }
        showSubscriptionDialog(subscription);
    }

    private void beginSelection(String subscriptionId) {
        if (!selectionMode) {
            selectionMode = true;
            selectionBackCallback.setEnabled(true);
        }
        selectedSubscriptionIds.add(subscriptionId);
        updateSelectionUi();
        updateAllRowStates();
    }

    private void toggleSelection(String subscriptionId) {
        if (TextUtils.isEmpty(subscriptionId)) {
            return;
        }
        if (selectedSubscriptionIds.contains(subscriptionId)) {
            selectedSubscriptionIds.remove(subscriptionId);
        } else {
            selectedSubscriptionIds.add(subscriptionId);
        }
        if (selectedSubscriptionIds.isEmpty()) {
            clearSelectionMode();
            return;
        }
        updateSelectionUi();
        updateAllRowStates();
    }

    private void clearSelectionMode() {
        if (!selectionMode && selectedSubscriptionIds.isEmpty()) {
            return;
        }
        selectionMode = false;
        selectedSubscriptionIds.clear();
        selectionBackCallback.setEnabled(false);
        updateSelectionUi();
        updateAllRowStates();
    }

    private void updateSelectionUi() {
        if (binding == null) {
            return;
        }
        boolean visible = selectionMode && !selectedSubscriptionIds.isEmpty();
        binding.layoutSubscriptionSelectionActions.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.textSubscriptionSelectionCount.setText(
            getString(R.string.xray_subscriptions_selected_count, selectedSubscriptionIds.size())
        );
    }

    private void updateAllRowStates() {
        for (SubscriptionRowViews row : rowViews.values()) {
            boolean selected = selectedSubscriptionIds.contains(row.subscription.id);
            row.root.setActivated(selected);
            row.checkbox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            row.checkbox.setChecked(selected);
            row.progress.setVisibility(
                refreshingSubscriptions && TextUtils.equals(refreshingSubscriptionId, row.subscription.id)
                    ? View.VISIBLE
                    : View.GONE
            );
        }
    }

    private void pruneSelection(List<XraySubscription> subscriptions) {
        LinkedHashSet<String> existingIds = new LinkedHashSet<>();
        for (XraySubscription subscription : subscriptions) {
            if (subscription != null) {
                existingIds.add(subscription.id);
            }
        }
        selectedSubscriptionIds.retainAll(existingIds);
        if (selectedSubscriptionIds.isEmpty()) {
            selectionMode = false;
            if (selectionBackCallback != null) {
                selectionBackCallback.setEnabled(false);
            }
        }
    }

    private List<XraySubscription> selectedSubscriptions() {
        ArrayList<XraySubscription> result = new ArrayList<>();
        for (XraySubscription subscription : currentSubscriptions) {
            if (subscription != null && selectedSubscriptionIds.contains(subscription.id)) {
                result.add(subscription);
            }
        }
        return result;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void shareSelectedSubscriptions() {
        if (selectedSubscriptionIds.isEmpty()) {
            return;
        }
        List<XraySubscription> selected = selectedSubscriptions();
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.xray_subscriptions_share_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String link = WingsImportParser.buildXraySubscriptionsLink(this, selected);
            Intent sendIntent = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link);
            startActivity(Intent.createChooser(sendIntent, getString(R.string.xray_subscriptions_share_chooser)));
            clearSelectionMode();
        } catch (Exception ignored) {
            Toast.makeText(this, R.string.xray_subscriptions_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteSelectedSubscriptions() {
        if (selectedSubscriptionIds.isEmpty()) {
            return;
        }
        List<XraySubscription> subscriptions = new ArrayList<>(XrayStore.getSubscriptions(this));
        int removed = 0;
        for (int index = subscriptions.size() - 1; index >= 0; index--) {
            if (selectedSubscriptionIds.contains(subscriptions.get(index).id)) {
                subscriptions.remove(index);
                removed++;
            }
        }
        XrayStore.setSubscriptions(this, subscriptions);
        clearSelectionMode();
        refreshUi();
        Toast.makeText(this, getString(R.string.xray_subscriptions_delete_done, removed), Toast.LENGTH_SHORT).show();
    }

    private static final class SubscriptionRowViews {

        final XraySubscription subscription;
        final View root;
        final AppCompatCheckBox checkbox;
        final View progress;

        SubscriptionRowViews(XraySubscription subscription, ItemSubscriptionEntryBinding binding) {
            this.subscription = subscription;
            this.root = binding.rowSubscriptionEntry;
            this.checkbox = binding.checkboxSubscriptionSelected;
            this.progress = binding.progressSubscriptionRefresh;
        }
    }

    private void updateRefreshStateUi() {
        if (binding == null) {
            return;
        }
        binding.rowRefreshSubscriptionsNow.setEnabled(!refreshingSubscriptions);
        binding.rowRefreshSubscriptionsNow.setSummary(
            refreshingSubscriptions
                ? getString(R.string.xray_profiles_refresh_subscriptions_running)
                : getString(R.string.xray_subscriptions_refresh_now_summary)
        );
        binding.progressRefreshSubscriptionsNow.setVisibility(refreshingSubscriptions ? View.VISIBLE : View.GONE);
    }
}
