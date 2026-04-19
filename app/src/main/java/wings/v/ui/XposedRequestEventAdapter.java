package wings.v.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import wings.v.databinding.ItemXposedRequestEventBinding;

public final class XposedRequestEventAdapter extends RecyclerView.Adapter<XposedRequestEventAdapter.ViewHolder> {

    private final List<Item> items = new ArrayList<>();

    public XposedRequestEventAdapter(@NonNull List<Item> items) {
        this.items.addAll(items);
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
            ItemXposedRequestEventBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)
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

    static final class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemXposedRequestEventBinding binding;

        ViewHolder(ItemXposedRequestEventBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull Item item) {
            binding.textEventTitle.setText(item.title);
            binding.textEventTime.setText(item.time);
            binding.textEventDetail.setText(item.detail);
        }
    }

    public static final class Item {

        @NonNull
        public final String title;

        @NonNull
        public final String time;

        @NonNull
        public final String detail;

        public Item(@NonNull String title, @NonNull String time, @NonNull String detail) {
            this.title = title;
            this.time = time;
            this.detail = detail;
        }
    }
}
