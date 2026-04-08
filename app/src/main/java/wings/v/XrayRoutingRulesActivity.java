package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.ListPopupWindow;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import wings.v.core.Haptics;
import wings.v.core.XrayRoutingRule;
import wings.v.core.XrayRoutingStore;
import wings.v.databinding.ActivityXrayRoutingRulesBinding;
import wings.v.databinding.DialogXrayRoutingRuleBinding;
import wings.v.databinding.ItemXrayRoutingRuleBinding;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.AvoidCatchingGenericException",
        "PMD.GodClass",
        "PMD.TooManyMethods",
        "PMD.LawOfDemeter",
        "PMD.CyclomaticComplexity",
        "PMD.CognitiveComplexity",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.CouplingBetweenObjects",
        "PMD.AtLeastOneConstructor",
        "PMD.LongVariable",
        "PMD.ShortVariable",
        "PMD.NullAssignment",
        "PMD.LooseCoupling",
        "PMD.AvoidDuplicateLiterals",
        "PMD.CommentDefaultAccessModifier",
        "PMD.UncommentedEmptyMethodBody",
        "PMD.OnlyOneReturn",
    }
)
public class XrayRoutingRulesActivity extends AppCompatActivity {

    private ActivityXrayRoutingRulesBinding binding;
    private ArrayList<XrayRoutingRule> rules;
    private RulesAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    public static Intent createIntent(Context context) {
        return new Intent(context, XrayRoutingRulesActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityXrayRoutingRulesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        rules = new ArrayList<>(XrayRoutingStore.getRules(this));
        adapter = new RulesAdapter();
        binding.recyclerRules.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerRules.setAdapter(adapter);
        itemTouchHelper = new ItemTouchHelper(new RuleTouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(binding.recyclerRules);
        binding.buttonAddRule.setOnClickListener(v -> {
            Haptics.softSelection(v);
            showRuleDialog(null, -1);
        });
        renderRules();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderRules();
    }

    private void renderRules() {
        binding.textRulesEmpty.setVisibility(rules.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void deleteRule(int position) {
        if (position < 0 || position >= rules.size()) {
            return;
        }
        rules.remove(position);
        persistRules();
        renderRules();
    }

    private void showRuleDialog(@Nullable XrayRoutingRule existingRule, int position) {
        DialogXrayRoutingRuleBinding dialogBinding = DialogXrayRoutingRuleBinding.inflate(getLayoutInflater());
        XrayRoutingRule.MatchType initialType =
            existingRule != null ? existingRule.matchType : XrayRoutingRule.MatchType.GEOIP;
        XrayRoutingRule.Action initialAction =
            existingRule != null ? existingRule.action : XrayRoutingRule.Action.PROXY;
        setSelectedMatchType(dialogBinding, initialType);
        dialogBinding.inputRuleCode.setText(existingRule != null ? existingRule.code : "");
        setSelectedAction(dialogBinding, initialAction);
        dialogBinding.switchRuleEnabled.setChecked(existingRule == null || existingRule.enabled);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(
                existingRule == null ? R.string.xray_routing_rule_dialog_add : R.string.xray_routing_rule_dialog_edit
            )
            .setView(dialogBinding.getRoot())
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();

        Runnable refreshValidation = () -> {
            XrayRoutingRule draft = buildRuleFromDialog(existingRule != null ? existingRule.id : null, dialogBinding);
            XrayRoutingStore.ValidationResult validation = XrayRoutingStore.validateRule(this, draft);
            dialogBinding.textValidationBadge.setText(validation.valid ? "OK" : validation.message);
            dialogBinding.textValidationBadge.setBackgroundResource(
                validation.valid ? R.drawable.bg_profile_ping_good : R.drawable.bg_profile_ping_bad
            );
        };

        TextWatcher watcher = new SimpleTextWatcher(refreshValidation);
        dialogBinding.inputRuleCode.addTextChangedListener(watcher);
        dialogBinding.fieldMatchType.setOnClickListener(v -> {
            Haptics.softSelection(v);
            showMatchTypeDialog(dialogBinding, refreshValidation);
        });
        dialogBinding.fieldRuleAction.setOnClickListener(v -> {
            Haptics.softSelection(v);
            showActionDialog(dialogBinding, refreshValidation);
        });
        dialogBinding.switchRuleEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> refreshValidation.run());
        refreshValidation.run();

        dialog.setOnShowListener(ignored ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    Haptics.softConfirm(v);
                    XrayRoutingRule rule = buildRuleFromDialog(
                        existingRule != null ? existingRule.id : null,
                        dialogBinding
                    );
                    XrayRoutingStore.ValidationResult validation = XrayRoutingStore.validateRule(this, rule);
                    if (!validation.valid && rule.enabled) {
                        dialogBinding.textValidationBadge.setText(validation.message);
                        dialogBinding.textValidationBadge.setBackgroundResource(R.drawable.bg_profile_ping_bad);
                        return;
                    }
                    if (position >= 0 && position < rules.size()) {
                        rules.set(position, rule);
                    } else {
                        rules.add(rule);
                    }
                    persistRules();
                    renderRules();
                    dialog.dismiss();
                })
        );
        dialog.show();
    }

    private XrayRoutingRule buildRuleFromDialog(@Nullable String id, DialogXrayRoutingRuleBinding binding) {
        return new XrayRoutingRule(
            id,
            getSelectedMatchType(binding),
            binding.inputRuleCode.getText() != null ? binding.inputRuleCode.getText().toString() : "",
            getSelectedAction(binding),
            binding.switchRuleEnabled.isChecked()
        );
    }

    private void setSelectedMatchType(
        @NonNull DialogXrayRoutingRuleBinding binding,
        @NonNull XrayRoutingRule.MatchType matchType
    ) {
        binding.fieldMatchType.setTag(matchType);
        binding.textMatchTypeValue.setText(resolveMatchTypeLabel(matchType));
    }

    @NonNull
    private XrayRoutingRule.MatchType getSelectedMatchType(@NonNull DialogXrayRoutingRuleBinding binding) {
        Object tag = binding.fieldMatchType.getTag();
        return tag instanceof XrayRoutingRule.MatchType
            ? (XrayRoutingRule.MatchType) tag
            : XrayRoutingRule.MatchType.GEOIP;
    }

    private void setSelectedAction(
        @NonNull DialogXrayRoutingRuleBinding binding,
        @NonNull XrayRoutingRule.Action action
    ) {
        binding.fieldRuleAction.setTag(action);
        binding.textRuleActionValue.setText(resolveActionLabel(action));
    }

    @NonNull
    private XrayRoutingRule.Action getSelectedAction(@NonNull DialogXrayRoutingRuleBinding binding) {
        Object tag = binding.fieldRuleAction.getTag();
        return tag instanceof XrayRoutingRule.Action ? (XrayRoutingRule.Action) tag : XrayRoutingRule.Action.PROXY;
    }

    private void showMatchTypeDialog(
        @NonNull DialogXrayRoutingRuleBinding binding,
        @NonNull Runnable refreshValidation
    ) {
        String[] options = {
            getString(R.string.xray_routing_match_geoip),
            getString(R.string.xray_routing_match_geosite),
        };
        showAnchoredDropdown(binding.fieldMatchType, options, which -> {
            setSelectedMatchType(
                binding,
                which == 1 ? XrayRoutingRule.MatchType.GEOSITE : XrayRoutingRule.MatchType.GEOIP
            );
            Haptics.softConfirm(binding.fieldMatchType);
            refreshValidation.run();
        });
    }

    private void showActionDialog(@NonNull DialogXrayRoutingRuleBinding binding, @NonNull Runnable refreshValidation) {
        String[] options = {
            getString(R.string.xray_routing_action_proxy),
            getString(R.string.xray_routing_action_direct),
            getString(R.string.xray_routing_action_block),
        };
        showAnchoredDropdown(binding.fieldRuleAction, options, which -> {
            XrayRoutingRule.Action selectedAction =
                which == 1
                    ? XrayRoutingRule.Action.DIRECT
                    : which == 2
                        ? XrayRoutingRule.Action.BLOCK
                        : XrayRoutingRule.Action.PROXY;
            setSelectedAction(binding, selectedAction);
            Haptics.softConfirm(binding.fieldRuleAction);
            refreshValidation.run();
        });
    }

    private void showAnchoredDropdown(
        @NonNull View anchor,
        @NonNull String[] options,
        @NonNull ItemSelectCallback callback
    ) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_xray_routing_spinner_dropdown, options);
        ListPopupWindow popupWindow = new ListPopupWindow(this);
        popupWindow.setAnchorView(anchor);
        popupWindow.setAdapter(adapter);
        popupWindow.setModal(true);
        popupWindow.setWidth(anchor.getWidth());
        popupWindow.setBackgroundDrawable(AppCompatResources.getDrawable(this, R.drawable.bg_xray_routing_popup));
        popupWindow.setOnItemClickListener((parent, view, position, id) -> {
            callback.onItemSelected(position);
            popupWindow.dismiss();
        });
        popupWindow.show();
    }

    private String resolveMatchTypeLabel(XrayRoutingRule.MatchType matchType) {
        if (matchType == XrayRoutingRule.MatchType.GEOSITE) {
            return getString(R.string.xray_routing_match_geosite);
        }
        return getString(R.string.xray_routing_match_geoip);
    }

    private String resolveActionLabel(XrayRoutingRule.Action action) {
        if (action == XrayRoutingRule.Action.DIRECT) {
            return getString(R.string.xray_routing_action_direct);
        }
        if (action == XrayRoutingRule.Action.BLOCK) {
            return getString(R.string.xray_routing_action_block);
        }
        return getString(R.string.xray_routing_action_proxy);
    }

    private void persistRules() {
        XrayRoutingStore.setRules(this, rules);
    }

    private final class RulesAdapter extends RecyclerView.Adapter<RulesAdapter.RuleViewHolder> {

        @NonNull
        @Override
        public RuleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RuleViewHolder(
                ItemXrayRoutingRuleBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)
            );
        }

        @Override
        public void onBindViewHolder(@NonNull RuleViewHolder holder, int position) {
            XrayRoutingRule rule = rules.get(position);
            XrayRoutingStore.ValidationResult validation = XrayRoutingStore.validateRule(
                XrayRoutingRulesActivity.this,
                rule
            );
            holder.binding.textRuleCode.setText(rule.prefixedCode());
            holder.binding.textRuleSummary.setText(
                getString(
                    R.string.xray_routing_rule_summary_value,
                    rule.enabled ? resolveActionLabel(rule.action) : getString(R.string.xray_routing_rule_disabled),
                    resolveMatchTypeLabel(rule.matchType)
                )
            );
            holder.binding.textRuleValidation.setText(validation.message);
            holder.binding.textRuleBadge.setText(
                validation.valid ? R.string.xray_routing_badge_ready : R.string.xray_routing_badge_invalid
            );
            holder.binding.textRuleBadge.setBackgroundResource(
                validation.valid ? R.drawable.bg_profile_ping_good : R.drawable.bg_profile_ping_bad
            );
            holder.binding
                .getRoot()
                .setOnClickListener(v -> {
                    Haptics.softSelection(v);
                    showRuleDialog(rule, holder.getBindingAdapterPosition());
                });
            holder.binding.buttonEditRule.setOnClickListener(v -> {
                Haptics.softSelection(v);
                showRuleDialog(rule, holder.getBindingAdapterPosition());
            });
            holder.binding.buttonDeleteRule.setOnClickListener(v -> {
                Haptics.softSelection(v);
                deleteRule(holder.getBindingAdapterPosition());
            });
            holder.binding.imageDragHandle.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && itemTouchHelper != null) {
                    Haptics.softSliderStep(view);
                    itemTouchHelper.startDrag(holder);
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return rules.size();
        }

        final class RuleViewHolder extends RecyclerView.ViewHolder {

            private final ItemXrayRoutingRuleBinding binding;

            RuleViewHolder(ItemXrayRoutingRuleBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    private final class RuleTouchHelperCallback extends ItemTouchHelper.SimpleCallback {

        RuleTouchHelperCallback() {
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
            Collections.swap(rules, from, to);
            adapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            persistRules();
            renderRules();
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}
    }

    private static final class SimpleTextWatcher implements TextWatcher {

        private final Runnable callback;

        SimpleTextWatcher(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            if (callback != null) {
                callback.run();
            }
        }
    }

    private interface ItemSelectCallback {
        void onItemSelected(int position);
    }
}
