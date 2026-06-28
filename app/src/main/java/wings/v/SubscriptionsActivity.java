package wings.v;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatCheckBox;
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
    private String draggingSubscriptionId;
    private View draggingRow;
    private float dragStartRawY;
    private float lastDragRawY;
    private int dragStartScrollY;
    private final Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (draggingRow == null || binding == null) {
                return;
            }
            int dy = computeAutoScrollDelta(lastDragRawY);
            if (dy != 0 && binding.scrollSubscriptions.canScrollVertically(dy > 0 ? 1 : -1)) {
                binding.scrollSubscriptions.scrollBy(0, dy);
                applyDragTranslation();
            }
            binding.scrollSubscriptions.postOnAnimation(this);
        }
    };

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
        AppPrefs.defaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(preferencesListener);
        refreshUi();
    }

    @Override
    protected void onPause() {
        AppPrefs.defaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(preferencesListener);
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
            if (!subscription.autoUpdate) {
                summary.append('\n').append(getString(R.string.xray_subscriptions_auto_update_disabled_label));
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
            View itemRoot = rowBinding.getRoot();
            rowBinding.imageSubscriptionDragHandle.setOnTouchListener((view, event) ->
                handleSubscriptionDragTouch(subscription.id, itemRoot, view, event)
            );
            binding.containerSubscriptions.addView(itemRoot);
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
            AppPrefs.defaultSharedPreferences(this)
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

        AppCompatCheckBox autoUpdateCheckbox = new AppCompatCheckBox(this);
        autoUpdateCheckbox.setText(R.string.xray_subscriptions_auto_update_per_subscription);
        autoUpdateCheckbox.setChecked(existing == null || existing.autoUpdate);
        LinearLayout.LayoutParams autoUpdateLayout = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        autoUpdateLayout.topMargin = padding / 2;
        container.addView(autoUpdateCheckbox, autoUpdateLayout);

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
                    autoUpdateCheckbox.isChecked(),
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
            boolean activeProfileChanged = false;
            String activeProfileId = "";
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
                activeProfileChanged = result.activeProfileChanged;
                activeProfileId = result.activeProfileId;
            } catch (Exception error) {
                message = getString(R.string.xray_subscriptions_refresh_failed, error.getMessage());
            }
            String toastMessage = message;
            if (activeProfileChanged) {
                wings.v.core.BackendType backendType = wings.v.core.XrayStore.getBackendType(getApplicationContext());
                if (
                    backendType != null && backendType.usesXrayCore() && wings.v.service.ProxyTunnelService.isActive()
                ) {
                    wings.v.service.ProxyTunnelService.requestReconnect(
                        getApplicationContext(),
                        "Xray subscription refresh updated active profile",
                        null,
                        activeProfileId
                    );
                }
            }
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
        boolean draggable = !selectionMode && currentSubscriptions.size() > 1;
        for (SubscriptionRowViews row : rowViews.values()) {
            boolean selected = selectedSubscriptionIds.contains(row.subscription.id);
            row.root.setActivated(selected);
            row.checkbox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            row.checkbox.setChecked(selected);
            row.dragHandle.setVisibility(draggable ? View.VISIBLE : View.GONE);
            row.progress.setVisibility(
                refreshingSubscriptions && TextUtils.equals(refreshingSubscriptionId, row.subscription.id)
                    ? View.VISIBLE
                    : View.GONE
            );
        }
    }

    // Drag-to-reorder for the subscriptions list. The list lives inside a
    // NestedScrollView, so instead of a RecyclerView/ItemTouchHelper (which fights
    // the nested scroll) the dragged row floats under the finger and snaps to the
    // dropped position on release. The new order is persisted to XrayStore; the
    // profiles screen groups by subscription order, so it reorders to match.
    private boolean handleSubscriptionDragTouch(String id, View itemRoot, View handle, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (selectionMode || currentSubscriptions.size() < 2) {
                    return false;
                }
                startSubscriptionDrag(id, itemRoot, handle, event.getRawY());
                return true;
            case MotionEvent.ACTION_MOVE:
                onSubscriptionDragMove(event.getRawY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finishSubscriptionDrag();
                return true;
            default:
                return false;
        }
    }

    private void startSubscriptionDrag(String id, View itemRoot, View handle, float rawY) {
        draggingSubscriptionId = id;
        draggingRow = itemRoot;
        dragStartRawY = rawY;
        lastDragRawY = rawY;
        dragStartScrollY = binding.scrollSubscriptions.getScrollY();
        float density = getResources().getDisplayMetrics().density;
        itemRoot.setTranslationZ(8f * density);
        itemRoot.setAlpha(0.95f);
        ViewParent parent = itemRoot.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
        Haptics.softSliderStep(handle);
        binding.scrollSubscriptions.postOnAnimation(autoScrollRunnable);
    }

    private void onSubscriptionDragMove(float rawY) {
        lastDragRawY = rawY;
        applyDragTranslation();
    }

    // Keeps the floating row under the finger even while the scroll view auto-scrolls:
    // as the content scrolls, the row's layout slot stays put, so we add the scroll
    // delta to its translation to track the finger in screen space.
    private void applyDragTranslation() {
        if (draggingRow == null || binding == null) {
            return;
        }
        int scrollDelta = binding.scrollSubscriptions.getScrollY() - dragStartScrollY;
        draggingRow.setTranslationY((lastDragRawY - dragStartRawY) + scrollDelta);
    }

    // Scroll speed when the finger is held within an edge band of the viewport, so a
    // row can be dragged past the visible area of a long list. Returns px per frame
    // (negative = up), 0 when the finger is away from the edges.
    private int computeAutoScrollDelta(float rawY) {
        if (binding == null) {
            return 0;
        }
        int[] location = new int[2];
        binding.scrollSubscriptions.getLocationOnScreen(location);
        float top = location[1];
        float bottom = location[1] + binding.scrollSubscriptions.getHeight();
        float density = getResources().getDisplayMetrics().density;
        float zone = 72f * density;
        float maxStep = 18f * density;
        if (rawY < top + zone) {
            float fraction = Math.min(1f, (top + zone - rawY) / zone);
            return -Math.max(1, (int) (fraction * maxStep));
        }
        if (rawY > bottom - zone) {
            float fraction = Math.min(1f, (rawY - (bottom - zone)) / zone);
            return Math.max(1, (int) (fraction * maxStep));
        }
        return 0;
    }

    private void finishSubscriptionDrag() {
        View row = draggingRow;
        String id = draggingSubscriptionId;
        draggingRow = null;
        draggingSubscriptionId = null;
        if (binding != null) {
            binding.scrollSubscriptions.removeCallbacks(autoScrollRunnable);
        }
        if (row == null) {
            return;
        }
        float floatCenter = row.getTop() + row.getTranslationY() + row.getHeight() / 2f;
        row.setTranslationY(0f);
        row.setTranslationZ(0f);
        row.setAlpha(1f);
        reorderSubscriptionTo(id, floatCenter);
    }

    private void reorderSubscriptionTo(String id, float floatCenter) {
        if (binding == null) {
            return;
        }
        int oldIndex = indexOfSubscription(id);
        if (oldIndex < 0 || oldIndex >= currentSubscriptions.size()) {
            return;
        }
        int count = binding.containerSubscriptions.getChildCount();
        int targetIndex = 0;
        for (int k = 0; k < count; k++) {
            if (k == oldIndex) {
                continue;
            }
            View child = binding.containerSubscriptions.getChildAt(k);
            float centerK = child.getTop() + child.getHeight() / 2f;
            if (centerK < floatCenter) {
                targetIndex++;
            }
        }
        if (targetIndex == oldIndex) {
            return;
        }
        ArrayList<XraySubscription> newOrder = new ArrayList<>(currentSubscriptions);
        XraySubscription moved = newOrder.remove(oldIndex);
        targetIndex = Math.max(0, Math.min(targetIndex, newOrder.size()));
        newOrder.add(targetIndex, moved);
        Haptics.softConfirm(binding.containerSubscriptions);
        XrayStore.setSubscriptions(this, newOrder);
        refreshUi();
    }

    private int indexOfSubscription(String id) {
        for (int index = 0; index < currentSubscriptions.size(); index++) {
            XraySubscription subscription = currentSubscriptions.get(index);
            if (subscription != null && TextUtils.equals(subscription.id, id)) {
                return index;
            }
        }
        return -1;
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
        // Backend profiles (WG / AWG / VK TURN) imported from a deleted subscription
        // are only ever touched by that subscription's own refresh, which will never
        // run again - so prune them here explicitly. Xray profiles are dropped by the
        // next full refresh rebuild, but the per-subscription backend stores need an
        // explicit empty sync to release the deleted subscription's slice.
        for (String deletedId : selectedSubscriptionIds) {
            if (android.text.TextUtils.isEmpty(deletedId)) {
                continue;
            }
            wings.v.core.WireGuardProfileStore.syncSubscriptionProfiles(
                this,
                deletedId,
                java.util.Collections.emptyList()
            );
            wings.v.core.AmneziaProfileStore.syncSubscriptionProfiles(
                this,
                deletedId,
                java.util.Collections.emptyList()
            );
            wings.v.core.VkTurnProfileStore.syncSubscriptionProfiles(
                this,
                deletedId,
                java.util.Collections.emptyList()
            );
        }
        clearSelectionMode();
        refreshUi();
        Toast.makeText(this, getString(R.string.xray_subscriptions_delete_done, removed), Toast.LENGTH_SHORT).show();
    }

    private static final class SubscriptionRowViews {

        final XraySubscription subscription;
        final View root;
        final AppCompatCheckBox checkbox;
        final View progress;
        final View dragHandle;

        SubscriptionRowViews(XraySubscription subscription, ItemSubscriptionEntryBinding binding) {
            this.subscription = subscription;
            this.root = binding.rowSubscriptionEntry;
            this.checkbox = binding.checkboxSubscriptionSelected;
            this.progress = binding.progressSubscriptionRefresh;
            this.dragHandle = binding.imageSubscriptionDragHandle;
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
