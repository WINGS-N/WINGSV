package wings.v.ui;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import wings.v.databinding.ItemXposedRequestAppBinding;

public final class XposedRequestHistoryAdapter extends RecyclerView.Adapter<XposedRequestHistoryAdapter.ViewHolder> {

    @FunctionalInterface
    public interface Callback {
        void onItemClicked(@NonNull Item item);
    }

    private final List<Item> items = new ArrayList<>();
    private final Callback callback;

    public XposedRequestHistoryAdapter(@NonNull List<Item> items, @NonNull Callback callback) {
        this.items.addAll(items);
        this.callback = callback;
    }

    public void replaceItems(@NonNull List<Item> items) {
        this.items.clear();
        this.items.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(
            ItemXposedRequestAppBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)
        );
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    final class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemXposedRequestAppBinding binding;

        ViewHolder(ItemXposedRequestAppBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Item item) {
            binding.imageAppIcon.setImageDrawable(item.icon);
            binding.textAppTitle.setText(item.title);
            binding.textAppPackage.setText(item.packageName);
            binding.textAppSummary.setText(item.summary);
            binding.textAppSubsummary.setText(item.subSummary);
            binding.getRoot().setOnClickListener(v -> callback.onItemClicked(item));
        }
    }

    public static final class Item {

        @NonNull
        public final String packageName;

        @NonNull
        public final String title;

        @NonNull
        public final Drawable icon;

        @NonNull
        public final String summary;

        @NonNull
        public final String subSummary;

        public Item(
            @NonNull String packageName,
            @NonNull String title,
            @NonNull Drawable icon,
            @NonNull String summary,
            @NonNull String subSummary
        ) {
            this.packageName = packageName;
            this.title = title;
            this.icon = icon;
            this.summary = summary;
            this.subSummary = subSummary;
        }
    }
}
