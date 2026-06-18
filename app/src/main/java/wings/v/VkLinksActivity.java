package wings.v;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import dev.oneuiproject.oneui.widget.CardItemView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityVkLinksBinding;
import wings.v.service.ProxyTunnelService;
import wings.v.vk.VkCallsApi;
import wings.v.vk.VkOAuthAuth;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class VkLinksActivity extends AppCompatActivity {

    private static final List<String> VALID_PREFIXES = Arrays.asList(
        "https://vk.com/call/join/",
        "https://vk.ru/call/join/",
        "vk.com/call/join/",
        "vk.ru/call/join/"
    );

    private ActivityVkLinksBinding binding;
    private final ArrayList<String> links = new ArrayList<>();
    private LinkAdapter adapter;
    private ItemTouchHelper itemTouchHelper;
    private final ExecutorService autolinkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean autolinkInFlight;
    private boolean autoPromptShown;

    private final ActivityResultLauncher<Intent> oauthLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == VkOAuthActivity.RESULT_AUTHORIZED) {
                runJoinLinkGeneration();
            } else {
                autolinkInFlight = false;
            }
        }
    );

    public static Intent createIntent(Context context) {
        return new Intent(context, VkLinksActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVkLinksBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ToolbarLayout toolbar = binding.toolbarLayout;
        toolbar.setShowNavigationButtonAsBack(true);

        binding.rowAddVkLink.setIcon(AppCompatResources.getDrawable(this, R.drawable.ic_add));
        binding.rowAddVkLink.setOnClickListener(view -> {
            Haptics.softSelection(view);
            showAddDialog();
        });
        binding.rowAutolinkVk.setOnClickListener(view -> {
            Haptics.softSelection(view);
            startAutolinkFlow();
        });
        binding.rowVkLinkSecondary.setOnClickListener(view -> {
            Haptics.softSelection(view);
            showSecondaryDialog();
        });

        adapter = new LinkAdapter();
        binding.recyclerVkLinks.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerVkLinks.setAdapter(adapter);
        binding.recyclerVkLinks.setItemAnimator(null);
        itemTouchHelper = new ItemTouchHelper(new LinkTouchCallback());
        itemTouchHelper.attachToRecyclerView(binding.recyclerVkLinks);

        loadLinks();
        renderSecondary();
        maybeOfferAutolinkOnEmpty();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        autolinkExecutor.shutdownNow();
        binding = null;
    }

    private void maybeOfferAutolinkOnEmpty() {
        if (autoPromptShown || !links.isEmpty() || !VkOAuthAuth.isClientConfigured()) {
            return;
        }
        autoPromptShown = true;
        new AlertDialog.Builder(this)
            .setTitle(R.string.vk_links_autolink_prompt_title)
            .setMessage(R.string.vk_links_autolink_prompt_message)
            .setPositiveButton(R.string.vk_links_autolink_prompt_yes, (dialog, which) -> startAutolinkFlow())
            .setNegativeButton(R.string.vk_links_autolink_prompt_no, null)
            .show();
    }

    private void startAutolinkFlow() {
        if (autolinkInFlight) {
            return;
        }
        if (!VkOAuthAuth.isClientConfigured()) {
            Toast.makeText(this, R.string.vk_links_autolink_unconfigured, Toast.LENGTH_LONG).show();
            return;
        }
        autolinkInFlight = true;
        if (VkOAuthAuth.isAuthorized(getApplicationContext())) {
            runJoinLinkGeneration();
            return;
        }
        oauthLauncher.launch(VkOAuthActivity.createIntent(this));
    }

    private void runJoinLinkGeneration() {
        showAutolinkLoader();
        autolinkExecutor.execute(() -> {
            String error = null;
            String joinLink = null;
            try {
                joinLink = VkCallsApi.generateJoinLink(getApplicationContext());
            } catch (Exception failure) {
                error = failure.getMessage();
            }
            final String finalLink = joinLink;
            final String finalError = error;
            mainHandler.post(() -> finishAutolink(finalLink, finalError));
        });
    }

    private void finishAutolink(@Nullable String joinLink, @Nullable String error) {
        autolinkInFlight = false;
        hideAutolinkLoader();
        if (binding == null) {
            return;
        }
        if (joinLink == null || joinLink.isEmpty()) {
            String detail = TextUtils.isEmpty(error)
                ? getString(R.string.vk_links_autolink_failed)
                : getString(R.string.vk_links_autolink_failed) + ": " + error;
            Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
            return;
        }
        String normalized = normalizeAndValidate(joinLink);
        if (normalized == null) {
            Toast.makeText(this, R.string.vk_links_invalid_url, Toast.LENGTH_LONG).show();
            return;
        }
        if (links.contains(normalized)) {
            Toast.makeText(this, R.string.vk_links_autolink_success, Toast.LENGTH_SHORT).show();
            return;
        }
        links.add(normalized);
        persistAndRefresh();
        Toast.makeText(this, R.string.vk_links_autolink_success, Toast.LENGTH_SHORT).show();
    }

    private void showAutolinkLoader() {
        if (binding == null) {
            return;
        }
        binding.progressAutolinkVk.setVisibility(View.VISIBLE);
        binding.rowAutolinkVk.getEndImageView().setVisibility(View.INVISIBLE);
        binding.rowAutolinkVk.setEnabled(false);
    }

    private void hideAutolinkLoader() {
        if (binding == null) {
            return;
        }
        binding.progressAutolinkVk.setVisibility(View.GONE);
        binding.rowAutolinkVk.getEndImageView().setVisibility(View.VISIBLE);
        binding.rowAutolinkVk.setEnabled(true);
    }

    private void loadLinks() {
        links.clear();
        links.addAll(AppPrefs.getVkLinks(this));
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (binding == null) {
            return;
        }
        if (links.isEmpty()) {
            binding.layoutVkLinksList.setVisibility(View.GONE);
            binding.textVkLinksEmpty.setVisibility(View.VISIBLE);
        } else {
            binding.layoutVkLinksList.setVisibility(View.VISIBLE);
            binding.textVkLinksEmpty.setVisibility(View.GONE);
        }
    }

    private void renderSecondary() {
        String secondary = AppPrefs.getVkLinkSecondary(this);
        CardItemView row = binding.rowVkLinkSecondary;
        if (TextUtils.isEmpty(secondary)) {
            row.setSummary(getString(R.string.vk_links_secondary_summary_empty));
        } else {
            row.setSummary(secondary);
        }
    }

    private void confirmDelete(int index) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.vk_links_remove_confirm_title)
            .setMessage(R.string.vk_links_remove_confirm_message)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                if (index < 0 || index >= links.size()) {
                    return;
                }
                links.remove(index);
                persistAndRefresh();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showAddDialog() {
        showInputDialog(getString(R.string.vk_links_add_dialog_title), "", value -> {
            if (links.contains(value)) {
                return;
            }
            links.add(value);
            persistAndRefresh();
        });
    }

    private void showEditDialog(int index) {
        if (index < 0 || index >= links.size()) {
            return;
        }
        String current = links.get(index);
        showInputDialog(getString(R.string.vk_links_edit_dialog_title), current, value -> {
            links.set(index, value);
            persistAndRefresh();
        });
    }

    private void showSecondaryDialog() {
        String current = AppPrefs.getVkLinkSecondary(this);
        EditText input = createUrlInput(current);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.vk_links_secondary_dialog_title)
            .setView(wrapEditTextWithPadding(input))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.vk_links_dialog_clear, (d, w) -> {
                AppPrefs.setVkLinkSecondary(this, "");
                renderSecondary();
                requestRuntimeReconnect();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    if (TextUtils.isEmpty(value)) {
                        AppPrefs.setVkLinkSecondary(this, "");
                        renderSecondary();
                        requestRuntimeReconnect();
                        dialog.dismiss();
                        return;
                    }
                    String normalized = normalizeAndValidate(value);
                    if (normalized == null) {
                        Toast.makeText(this, R.string.vk_links_invalid_url, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AppPrefs.setVkLinkSecondary(this, normalized);
                    renderSecondary();
                    requestRuntimeReconnect();
                    dialog.dismiss();
                });
        });
        dialog.show();
    }

    private void showInputDialog(String title, String initialValue, OnLinkAccepted accepted) {
        EditText input = createUrlInput(initialValue);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrapEditTextWithPadding(input))
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    String normalized = normalizeAndValidate(value);
                    if (normalized == null) {
                        Toast.makeText(this, R.string.vk_links_invalid_url, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    accepted.onAccepted(normalized);
                    dialog.dismiss();
                });
        });
        dialog.show();
    }

    private EditText createUrlInput(String initialValue) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.vk_links_add_hint);
        input.setText(initialValue == null ? "" : initialValue);
        input.setSingleLine(true);
        return input;
    }

    private View wrapEditTextWithPadding(EditText input) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        int paddingHorizontal = (int) (24 * getResources().getDisplayMetrics().density);
        int paddingVertical = (int) (12 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
        wrapper.addView(
            input,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        );
        return wrapper;
    }

    @Nullable
    private String normalizeAndValidate(String raw) {
        String trimmed = raw.trim();
        if (TextUtils.isEmpty(trimmed)) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        boolean matched = false;
        for (String prefix : VALID_PREFIXES) {
            if (lower.startsWith(prefix)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            return null;
        }
        if (!trimmed.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        }
        return trimmed;
    }

    private void persistAndRefresh() {
        AppPrefs.setVkLinks(this, links);
        requestRuntimeReconnect();
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void persistOrderOnly() {
        AppPrefs.setVkLinks(this, links);
        requestRuntimeReconnect();
    }

    private void requestRuntimeReconnect() {
        if (ProxyTunnelService.isActive()) {
            ProxyTunnelService.requestReconnect(getApplicationContext(), "VK links updated");
        }
    }

    @FunctionalInterface
    private interface OnLinkAccepted {
        void onAccepted(@NonNull String value);
    }

    private final class LinkAdapter extends RecyclerView.Adapter<LinkAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_vk_link_entry, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(position);
        }

        @Override
        public int getItemCount() {
            return links.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {

            final TextView indexView;
            final TextView urlView;
            final AppCompatImageButton deleteButton;
            final AppCompatImageView dragHandle;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                indexView = itemView.findViewById(R.id.text_vk_link_index);
                urlView = itemView.findViewById(R.id.text_vk_link_url);
                deleteButton = itemView.findViewById(R.id.button_vk_link_delete);
                dragHandle = itemView.findViewById(R.id.image_vk_link_drag_handle);
            }

            void bind(int position) {
                indexView.setText(String.valueOf(position + 1));
                urlView.setText(links.get(position));

                itemView.setOnClickListener(view -> {
                    Haptics.softSelection(view);
                    int currentPos = getBindingAdapterPosition();
                    if (currentPos != RecyclerView.NO_POSITION) {
                        showEditDialog(currentPos);
                    }
                });

                deleteButton.setOnClickListener(view -> {
                    Haptics.softSelection(view);
                    int currentPos = getBindingAdapterPosition();
                    if (currentPos != RecyclerView.NO_POSITION) {
                        confirmDelete(currentPos);
                    }
                });

                dragHandle.setOnTouchListener((view, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN && itemTouchHelper != null) {
                        Haptics.softSelection(view);
                        itemTouchHelper.startDrag(this);
                    }
                    return false;
                });
            }
        }
    }

    private final class LinkTouchCallback extends ItemTouchHelper.SimpleCallback {

        LinkTouchCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public boolean onMove(
            @NonNull RecyclerView recyclerView,
            @NonNull RecyclerView.ViewHolder viewHolder,
            @NonNull RecyclerView.ViewHolder target
        ) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                return false;
            }
            Collections.swap(links, from, to);
            adapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            persistOrderOnly();
            adapter.notifyDataSetChanged();
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}
    }
}
