package wings.v.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import wings.v.R;
import wings.v.core.Haptics;
import wings.v.databinding.ItemUiReorderEntryBinding;

/**
 * Шаренная логика drag-reorder списка с per-item switch и заблокированными
 * (forced-visible) пунктами. Используется UI settings экранами навбара и
 * уведомления.
 */
@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.CommentDefaultAccessModifier",
        "PMD.AtLeastOneConstructor",
        "PMD.CallsSuperInConstructor",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.OnlyOneReturn",
    }
)
public final class UiReorderListController {

    public static final class Entry {

        public final String key;
        public final String title;
        public final boolean locked;

        public Entry(String key, String title, boolean locked) {
            this.key = key;
            this.title = title;
            this.locked = locked;
        }
    }

    @FunctionalInterface
    public interface Persister {
        void persist(List<String> order, Set<String> hiddenKeys);
    }

    private final Context context;
    private final List<Entry> entries;
    private final Set<String> hiddenKeys;
    private final Persister persister;
    private final ReorderAdapter adapter;
    private final ItemTouchHelper itemTouchHelper;

    public UiReorderListController(
        @NonNull Context context,
        @NonNull List<Entry> entries,
        @NonNull Set<String> initialHidden,
        @NonNull Persister persister
    ) {
        this.context = context;
        this.entries = new ArrayList<>(entries);
        this.hiddenKeys = new LinkedHashSet<>(initialHidden);
        this.persister = persister;
        this.adapter = new ReorderAdapter();
        this.itemTouchHelper = new ItemTouchHelper(new TouchCallback());
    }

    public void attach(@NonNull RecyclerView recyclerView) {
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setItemAnimator(new DefaultItemAnimator());
        recyclerView.setAdapter(adapter);
        itemTouchHelper.attachToRecyclerView(recyclerView);
    }

    private void persistCurrent() {
        List<String> order = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            order.add(entry.key);
        }
        persister.persist(order, new LinkedHashSet<>(hiddenKeys));
    }

    private final class ReorderAdapter extends RecyclerView.Adapter<ReorderAdapter.Holder> {

        ReorderAdapter() {
            setHasStableIds(true);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(
                ItemUiReorderEntryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)
            );
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Entry entry = entries.get(position);
            holder.binding.textEntryTitle.setText(entry.title);
            if (entry.locked) {
                holder.binding.textEntrySummary.setVisibility(android.view.View.VISIBLE);
                holder.binding.textEntrySummary.setText(R.string.ui_settings_locked_hint);
            } else {
                holder.binding.textEntrySummary.setVisibility(android.view.View.GONE);
            }
            holder.binding.switchEntryEnabled.setOnCheckedChangeListener(null);
            boolean visible = !hiddenKeys.contains(entry.key);
            holder.binding.switchEntryEnabled.setChecked(visible || entry.locked);
            holder.binding.switchEntryEnabled.setEnabled(!entry.locked);
            holder.binding.switchEntryEnabled.setOnCheckedChangeListener((view, isChecked) -> {
                if (entry.locked) {
                    view.setChecked(true);
                    return;
                }
                if (isChecked) {
                    hiddenKeys.remove(entry.key);
                } else {
                    hiddenKeys.add(entry.key);
                }
                Haptics.softSliderStep(view);
                persistCurrent();
            });
            holder.binding.imageDragHandle.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    Haptics.softSliderStep(view);
                    itemTouchHelper.startDrag(holder);
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        @Override
        public long getItemId(int position) {
            return entries.get(position).key.hashCode();
        }

        final class Holder extends RecyclerView.ViewHolder {

            private final ItemUiReorderEntryBinding binding;

            Holder(ItemUiReorderEntryBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    private final class TouchCallback extends ItemTouchHelper.SimpleCallback {

        TouchCallback() {
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
            Collections.swap(entries, from, to);
            adapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            persistCurrent();
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            // noop
        }
    }
}
