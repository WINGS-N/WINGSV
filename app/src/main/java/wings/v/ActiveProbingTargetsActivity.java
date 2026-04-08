package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.List;
import wings.v.core.ActiveProbingManager;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityActiveProbingTargetsBinding;
import wings.v.databinding.ItemActiveProbingTargetBinding;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.AtLeastOneConstructor",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.LooseCoupling",
    }
)
public class ActiveProbingTargetsActivity extends AppCompatActivity {

    private ActivityActiveProbingTargetsBinding binding;
    private final ArrayList<String> targets = new ArrayList<>();

    public static Intent createIntent(Context context) {
        return new Intent(context, ActiveProbingTargetsActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityActiveProbingTargetsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        binding.buttonAddTarget.setOnClickListener(view -> {
            Haptics.softSelection(view);
            showEditTargetDialog(-1, "");
        });
        reloadTargets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadTargets();
    }

    private void reloadTargets() {
        targets.clear();
        targets.addAll(ActiveProbingManager.getUrls(this));
        renderTargets();
    }

    private void renderTargets() {
        binding.containerTargets.removeAllViews();
        boolean hasTargets = !targets.isEmpty();
        binding.layoutTargetsList.setVisibility(hasTargets ? View.VISIBLE : View.GONE);
        binding.textTargetsEmpty.setVisibility(hasTargets ? View.GONE : View.VISIBLE);
        if (!hasTargets) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int index = 0; index < targets.size(); index++) {
            final int position = index;
            String url = targets.get(position);
            ItemActiveProbingTargetBinding itemBinding = ItemActiveProbingTargetBinding.inflate(
                inflater,
                binding.containerTargets,
                false
            );
            itemBinding.textTargetUrl.setText(url);
            itemBinding.viewTargetDivider.setVisibility(index == targets.size() - 1 ? View.GONE : View.VISIBLE);
            itemBinding
                .getRoot()
                .setOnClickListener(view -> {
                    Haptics.softSelection(view);
                    showEditTargetDialog(position, url);
                });
            itemBinding.buttonDeleteTarget.setOnClickListener(view -> {
                Haptics.softSelection(view);
                showDeleteDialog(position, url);
            });
            binding.containerTargets.addView(itemBinding.getRoot());
        }
    }

    private void showEditTargetDialog(int index, @Nullable String existingValue) {
        final EditText input = createTargetInput(existingValue);
        FrameLayout container = buildDialogContainer(input);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(
                index >= 0
                    ? R.string.active_probing_targets_edit_dialog_title
                    : R.string.active_probing_targets_add_dialog_title
            )
            .setView(container)
            .setNegativeButton(R.string.sharing_edit_dialog_cancel, null)
            .setPositiveButton(
                index >= 0 ? R.string.sharing_edit_dialog_save : R.string.active_probing_targets_add_button,
                null
            )
            .create();
        dialog.setOnShowListener(ignored ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    Haptics.softSelection(view);
                    String normalized = ActiveProbingManager.normalizeUrl(
                        input.getText() == null ? "" : input.getText().toString()
                    );
                    if (TextUtils.isEmpty(normalized)) {
                        Toast.makeText(this, R.string.active_probing_targets_invalid_url, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ArrayList<String> updatedTargets = new ArrayList<>(targets);
                    if (index >= 0 && index < updatedTargets.size()) {
                        updatedTargets.set(index, normalized);
                    } else {
                        updatedTargets.add(normalized);
                    }
                    persistTargets(updatedTargets);
                    dialog.dismiss();
                })
        );
        dialog.show();
    }

    private void showDeleteDialog(int index, String url) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.active_probing_targets_delete_title)
            .setMessage(getString(R.string.active_probing_targets_delete_message, url))
            .setNegativeButton(R.string.sharing_edit_dialog_cancel, null)
            .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                ArrayList<String> updatedTargets = new ArrayList<>(targets);
                if (index >= 0 && index < updatedTargets.size()) {
                    updatedTargets.remove(index);
                    persistTargets(updatedTargets);
                }
            })
            .show();
    }

    private void persistTargets(List<String> updatedTargets) {
        ActiveProbingManager.saveUrls(this, updatedTargets);
        reloadTargets();
    }

    private EditText createTargetInput(@Nullable String existingValue) {
        EditText input = new EditText(this);
        input.setHint(R.string.active_probing_targets_input_hint);
        input.setSingleLine(true);
        input.setInputType(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        if (!TextUtils.isEmpty(existingValue)) {
            input.setText(existingValue);
            input.setSelection(input.length());
        }
        return input;
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
