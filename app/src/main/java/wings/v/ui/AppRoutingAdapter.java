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

import wings.v.R;
import wings.v.databinding.ItemAppRoutingBinding;
import wings.v.databinding.ItemAppRoutingHeaderBinding;

final class AppRoutingAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_APP = 1;

    interface Callback {
        void onPackageToggled(String packageName, boolean enabled, View sourceView);

        void onBypassModeChanged(boolean enabled, View sourceView);

        void onSelectedAppsRequested(View sourceView);
    }

    private final Callback callback;
    private final List<AppRoutingEntry> items = new ArrayList<>();
    private final Set<String> enabledPackages = new LinkedHashSet<>();
    private boolean bypassEnabled = true;

    AppRoutingAdapter(Callback callback) {
        this.callback = callback;
        setHasStableIds(true);
    }

    void replaceItems(List<AppRoutingEntry> entries, Set<String> enabled, boolean bypassEnabled) {
        items.clear();
        enabledPackages.clear();
        if (entries != null) {
            items.addAll(entries);
        }
        if (enabled != null) {
            enabledPackages.addAll(enabled);
        }
        this.bypassEnabled = bypassEnabled;
        notifyDataSetChanged();
    }

    void setPackageEnabled(String packageName, boolean enabled) {
        boolean changed;
        if (enabled) {
            changed = enabledPackages.add(packageName);
        } else {
            changed = enabledPackages.remove(packageName);
        }
        if (changed) {
            notifyItemChanged(0);
            int itemIndex = indexOfPackage(packageName);
            if (itemIndex >= 0) {
                notifyItemChanged(itemIndex + 1);
            }
        }
    }

    void setBypassEnabled(boolean enabled) {
        if (bypassEnabled == enabled) {
            return;
        }
        bypassEnabled = enabled;
        notifyItemChanged(0);
    }

    int getEnabledCount() {
        return enabledPackages.size();
    }

    boolean hasAnyApps() {
        return !items.isEmpty();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(ItemAppRoutingHeaderBinding.inflate(inflater, parent, false));
        }
        return new AppViewHolder(ItemAppRoutingBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_HEADER) {
            ((HeaderViewHolder) holder).bind();
            return;
        }
        AppRoutingEntry item = items.get(position - 1);
        boolean enabled = enabledPackages.contains(item.packageName);
        ((AppViewHolder) holder).bind(item, enabled);
    }

    @Override
    public int getItemCount() {
        return 1 + items.size();
    }

    @Override
    public long getItemId(int position) {
        if (position == 0) {
            return Long.MIN_VALUE;
        }
        return items.get(position - 1).packageName.hashCode();
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_APP;
    }

    private int indexOfPackage(String packageName) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).packageName.equals(packageName)) {
                return index;
            }
        }
        return -1;
    }

    final class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final ItemAppRoutingHeaderBinding binding;

        HeaderViewHolder(ItemAppRoutingHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind() {
            binding.switchBypassMode.setOnCheckedChangeListener(null);
            binding.switchBypassMode.setChecked(bypassEnabled);
            binding.switchBypassMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (callback != null) {
                    callback.onBypassModeChanged(isChecked, buttonView);
                }
            });

            binding.textModeSummary.setText(
                    bypassEnabled ? R.string.apps_mode_bypass_on : R.string.apps_mode_bypass_off
            );
            if (getEnabledCount() > 0) {
                binding.textAppsCount.setText(
                        binding.getRoot().getContext().getString(R.string.apps_count, getEnabledCount())
                );
            } else {
                binding.textAppsCount.setText(R.string.apps_count_zero);
            }
            binding.rowSelectedApps.setOnClickListener(v -> {
                if (callback != null) {
                    callback.onSelectedAppsRequested(v);
                }
            });
        }
    }

    final class AppViewHolder extends RecyclerView.ViewHolder {
        private final ItemAppRoutingBinding binding;

        AppViewHolder(ItemAppRoutingBinding binding) {
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
            binding.getRoot().setOnClickListener(v -> {
                boolean nextValue = !binding.switchAppRule.isChecked();
                if (callback != null) {
                    callback.onPackageToggled(item.packageName, nextValue, binding.getRoot());
                }
            });
        }
    }
}
