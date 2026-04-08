package wings.v.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import wings.v.databinding.ItemAppRoutingBinding;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.CommentDefaultAccessModifier",
        "PMD.FieldDeclarationsShouldBeAtStartOfClass",
        "PMD.CallsSuperInConstructor",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
    }
)
final class AppSearchAdapter extends RecyclerView.Adapter<AppSearchAdapter.ViewHolder> {

    interface Callback {
        void onPackageToggled(String packageName, boolean enabled, View sourceView);
    }

    private final Callback callback;
    private final List<AppRoutingEntry> items = new ArrayList<>();
    private final Set<String> enabledPackages = new LinkedHashSet<>();

    AppSearchAdapter(Callback callback) {
        this.callback = callback;
        setHasStableIds(true);
    }

    void replaceItems(List<AppRoutingEntry> entries, Set<String> enabledPackages) {
        items.clear();
        this.enabledPackages.clear();
        if (entries != null) {
            items.addAll(entries);
        }
        if (enabledPackages != null) {
            this.enabledPackages.addAll(enabledPackages);
        }
        notifyDataSetChanged();
    }

    void setPackageEnabled(String packageName, boolean enabled) {
        if (enabled) {
            this.enabledPackages.add(packageName);
        } else {
            this.enabledPackages.remove(packageName);
        }
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).packageName.hashCode();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return new ViewHolder(ItemAppRoutingBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppRoutingEntry item = items.get(position);
        boolean enabled = enabledPackages.contains(item.packageName);
        holder.bind(item, enabled);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    final class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemAppRoutingBinding binding;

        ViewHolder(ItemAppRoutingBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(AppRoutingEntry item, boolean enabled) {
            binding.imageAppIcon.setImageDrawable(item.icon);
            binding.textAppTitle.setText(item.label);
            binding.textAppPackage.setText(item.packageName);
            binding.switchAppRule.setOnCheckedChangeListener(null);
            binding.switchAppRule.setChecked(enabled);
            binding.switchAppRule.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (callback != null) {
                    callback.onPackageToggled(item.packageName, isChecked, buttonView);
                }
            });
            binding
                .getRoot()
                .setOnClickListener(v -> {
                    boolean nextValue = !binding.switchAppRule.isChecked();
                    if (callback != null) {
                        callback.onPackageToggled(item.packageName, nextValue, binding.getRoot());
                    }
                });
        }
    }
}
