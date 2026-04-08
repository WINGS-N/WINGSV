package wings.v.byedpi;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import wings.v.R;
import wings.v.core.Haptics;
import wings.v.databinding.ItemByeDpiStrategyResultBinding;

@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.CommentDefaultAccessModifier",
        "PMD.FieldDeclarationsShouldBeAtStartOfClass",
        "PMD.CallsSuperInConstructor",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LooseCoupling",
        "PMD.ImplicitFunctionalInterface",
    }
)
public final class ByeDpiStrategyResultAdapter extends RecyclerView.Adapter<ByeDpiStrategyResultAdapter.ViewHolder> {

    public interface OnApplyListener {
        void onApply(ByeDpiStrategyResult result);
    }

    private final ArrayList<ByeDpiStrategyResult> items = new ArrayList<>();
    private final OnApplyListener onApplyListener;
    private boolean testing;

    public ByeDpiStrategyResultAdapter(OnApplyListener onApplyListener) {
        this.onApplyListener = onApplyListener;
    }

    public void setTesting(boolean testing) {
        this.testing = testing;
        notifyDataSetChanged();
    }

    public void replaceItems(List<ByeDpiStrategyResult> results) {
        items.clear();
        if (results != null) {
            items.addAll(results);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemByeDpiStrategyResultBinding binding = ItemByeDpiStrategyResultBinding.inflate(
            LayoutInflater.from(parent.getContext()),
            parent,
            false
        );
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ByeDpiStrategyResult result = items.get(position);
        holder.binding.textStrategyCommand.setText(result.command);
        holder.binding.progressStrategy.setMax(Math.max(1, result.totalRequests));
        holder.binding.progressStrategy.setProgress(Math.max(0, result.successCount));
        boolean hasProgress = result.totalRequests > 0;
        holder.binding.progressStrategy.setVisibility(hasProgress ? View.VISIBLE : View.GONE);
        holder.binding.textStrategyResult.setText(
            hasProgress
                ? holder.binding
                      .getRoot()
                      .getContext()
                      .getString(R.string.byedpi_strategy_result_summary, result.successCount, result.totalRequests)
                : holder.binding
                      .getRoot()
                      .getContext()
                      .getString(
                          testing ? R.string.byedpi_strategy_result_waiting : R.string.byedpi_strategy_result_not_tested
                      )
        );
        holder.binding.buttonApplyStrategy.setEnabled(result.completed && !testing);
        holder.binding.buttonApplyStrategy.setOnClickListener(view -> {
            Haptics.softSelection(view);
            if (onApplyListener != null) {
                onApplyListener.onApply(result);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {

        final ItemByeDpiStrategyResultBinding binding;

        ViewHolder(ItemByeDpiStrategyResultBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
