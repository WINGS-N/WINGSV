package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import wings.v.core.ByeDpiDomainList;
import wings.v.core.ByeDpiDomainListStore;
import wings.v.core.ByeDpiStore;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityByeDpiTargetsBinding;
import wings.v.databinding.ItemByeDpiTargetBinding;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.TooManyMethods",
        "PMD.CognitiveComplexity",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.LooseCoupling",
    }
)
public class ByeDpiTargetsActivity extends AppCompatActivity {

    private ActivityByeDpiTargetsBinding binding;
    private final ArrayList<ByeDpiDomainList> domainLists = new ArrayList<>();

    public static Intent createIntent(Context context) {
        return new Intent(context, ByeDpiTargetsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityByeDpiTargetsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        binding.buttonAddTarget.setOnClickListener(view -> {
            Haptics.softSelection(view);
            showEditTargetDialog(-1, null);
        });
        reloadTargets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadTargets();
    }

    private void reloadTargets() {
        domainLists.clear();
        domainLists.addAll(ByeDpiDomainListStore.getLists(this));
        renderTargets();
    }

    private void renderTargets() {
        binding.containerTargets.removeAllViews();
        boolean hasTargets = !domainLists.isEmpty();
        binding.layoutTargetsList.setVisibility(hasTargets ? View.VISIBLE : View.GONE);
        binding.textTargetsEmpty.setVisibility(hasTargets ? View.GONE : View.VISIBLE);
        if (!hasTargets) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int index = 0; index < domainLists.size(); index++) {
            final int position = index;
            ByeDpiDomainList item = domainLists.get(position);
            ItemByeDpiTargetBinding itemBinding = ItemByeDpiTargetBinding.inflate(
                inflater,
                binding.containerTargets,
                false
            );
            itemBinding.textTargetUrl.setText(item.name);
            itemBinding.textTargetSummary.setText(buildSummary(item));
            itemBinding.switchTargetEnabled.setOnCheckedChangeListener(null);
            itemBinding.switchTargetEnabled.setChecked(item.isActive);
            itemBinding.switchTargetEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                handleToggle(item, buttonView, isChecked)
            );
            itemBinding.viewTargetDivider.setVisibility(index == domainLists.size() - 1 ? View.GONE : View.VISIBLE);
            itemBinding
                .getRoot()
                .setOnClickListener(view -> {
                    Haptics.softSelection(view);
                    if (item.isBuiltIn) {
                        showViewTargetDialog(item);
                    } else {
                        showEditTargetDialog(position, item);
                    }
                });
            itemBinding.buttonDeleteTarget.setVisibility(item.isBuiltIn ? View.GONE : View.VISIBLE);
            itemBinding.buttonDeleteTarget.setOnClickListener(view -> {
                Haptics.softSelection(view);
                showDeleteDialog(position, item);
            });
            binding.containerTargets.addView(itemBinding.getRoot());
        }
    }

    private void handleToggle(ByeDpiDomainList item, CompoundButton buttonView, boolean isChecked) {
        if (item.isActive == isChecked) {
            return;
        }
        Haptics.softSliderStep(buttonView);
        ByeDpiDomainListStore.toggleListActive(this, item.id);
        reloadTargets();
    }

    private void showEditTargetDialog(int index, @Nullable ByeDpiDomainList existingItem) {
        EditText nameInput = createNameInput(existingItem == null ? "" : existingItem.name);
        EditText domainsInput = createDomainsInput(
            existingItem == null ? "" : TextUtils.join("\n", existingItem.domains)
        );
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.addView(
            nameInput,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        );
        LinearLayout.LayoutParams domainsLayoutParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        domainsLayoutParams.topMargin = Math.round(getResources().getDisplayMetrics().density * 12f);
        form.addView(domainsInput, domainsLayoutParams);
        FrameLayout container = buildDialogContainer(form);
        int titleRes =
            index >= 0 ? R.string.byedpi_targets_edit_dialog_title : R.string.byedpi_targets_add_dialog_title;
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(container)
            .setNegativeButton(R.string.sharing_edit_dialog_cancel, null)
            .setPositiveButton(
                index >= 0 ? R.string.sharing_edit_dialog_save : R.string.byedpi_targets_add_button,
                null
            )
            .create();
        dialog.setOnShowListener(ignored ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    Haptics.softSelection(view);
                    String normalizedName = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                    ArrayList<String> domains = parseDomains(
                        domainsInput.getText() == null ? "" : domainsInput.getText().toString()
                    );
                    if (TextUtils.isEmpty(normalizedName)) {
                        Toast.makeText(this, R.string.byedpi_targets_name_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (domains.isEmpty()) {
                        Toast.makeText(this, R.string.byedpi_targets_domains_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean success;
                    if (existingItem != null && index >= 0) {
                        success = ByeDpiDomainListStore.updateList(this, existingItem.id, normalizedName, domains);
                    } else {
                        success = ByeDpiDomainListStore.addList(this, normalizedName, domains);
                        if (!success) {
                            Toast.makeText(this, R.string.byedpi_targets_already_exists, Toast.LENGTH_SHORT).show();
                        }
                    }
                    if (!success) {
                        return;
                    }
                    reloadTargets();
                    dialog.dismiss();
                })
        );
        dialog.show();
    }

    private void showViewTargetDialog(ByeDpiDomainList item) {
        EditText input = createDomainsInput(TextUtils.join("\n", item.domains));
        input.setEnabled(false);
        input.setMovementMethod(new ScrollingMovementMethod());
        new AlertDialog.Builder(this)
            .setTitle(item.name)
            .setView(buildDialogContainer(input))
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void showDeleteDialog(int index, ByeDpiDomainList item) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.byedpi_targets_delete_title)
            .setMessage(getString(R.string.byedpi_targets_delete_message, item.name))
            .setNegativeButton(R.string.sharing_edit_dialog_cancel, null)
            .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                if (index >= 0 && index < domainLists.size()) {
                    ByeDpiDomainListStore.deleteList(this, item.id);
                    reloadTargets();
                }
            })
            .show();
    }

    private EditText createNameInput(@Nullable String existingValue) {
        EditText input = new EditText(this);
        input.setHint(R.string.byedpi_targets_name_input_hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (!TextUtils.isEmpty(existingValue)) {
            input.setText(existingValue);
            input.setSelection(input.length());
        }
        return input;
    }

    private EditText createDomainsInput(@Nullable String existingValue) {
        EditText input = new EditText(this);
        input.setHint(R.string.byedpi_targets_domains_input_hint);
        input.setMinLines(6);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setInputType(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        if (!TextUtils.isEmpty(existingValue)) {
            input.setText(existingValue);
            input.setSelection(input.length());
        }
        return input;
    }

    private ArrayList<String> parseDomains(@Nullable String rawInput) {
        ArrayList<String> domains = new ArrayList<>();
        if (rawInput == null) {
            return domains;
        }
        for (String line : rawInput.split("\n")) {
            String normalized = ByeDpiStore.normalizeTarget(line);
            if (!TextUtils.isEmpty(normalized) && !domains.contains(normalized)) {
                domains.add(normalized);
            }
        }
        return domains;
    }

    private String buildSummary(ByeDpiDomainList item) {
        ArrayList<String> preview = new ArrayList<>();
        int previewCount = Math.min(3, item.domains.size());
        for (int index = 0; index < previewCount; index++) {
            preview.add(item.domains.get(index));
        }
        String summary = TextUtils.join(", ", preview);
        int hidden = item.domains.size() - previewCount;
        if (hidden > 0) {
            summary = summary + "\n" + getString(R.string.byedpi_targets_more_domains, hidden);
        }
        return summary;
    }

    private FrameLayout buildDialogContainer(View child) {
        int horizontalPadding = Math.round(getResources().getDisplayMetrics().density * 24f);
        int verticalPadding = Math.round(getResources().getDisplayMetrics().density * 8f);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0);
        container.addView(
            child,
            new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        );
        return container;
    }
}
