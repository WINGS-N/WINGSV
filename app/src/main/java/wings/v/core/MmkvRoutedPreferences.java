package wings.v.core;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.tencent.mmkv.MMKV;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A SharedPreferences facade over several per-subsystem MMKV files.
 *
 * <p>Callers keep talking to one SharedPreferences; each key is routed to the file
 * its subsystem owns (see {@link MmkvPrefsAreas}), so a write only ever touches its
 * own mapping instead of the single file every process shared.
 *
 * <p>Keys written before the split still live in the legacy file. Rather than
 * guessing their types up front - MMKV does not record them, so a bulk copy cannot
 * reconstruct one - a read that misses its area falls through to the legacy file
 * and writes the value into the area with the type the caller just asked for. The
 * type is therefore always right, and a key migrates the first time anything reads
 * it. Keys nothing ever reads simply stay behind, which costs nothing.
 */
@SuppressWarnings({ "PMD.CommentRequired", "PMD.AvoidSynchronizedAtMethodLevel", "PMD.GodClass", "PMD.TooManyMethods" })
public final class MmkvRoutedPreferences implements SharedPreferences {

    private static final Object PRESENT = new Object();

    private final Map<String, MMKV> areas;
    private final MMKV legacy;
    private final Map<OnSharedPreferenceChangeListener, Object> listeners = new WeakHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    MmkvRoutedPreferences(@NonNull Map<String, MMKV> areas, @NonNull MMKV legacy) {
        this.areas = areas;
        this.legacy = legacy;
    }

    private MMKV storeFor(String key) {
        MMKV store = areas.get(MmkvPrefsAreas.areaFor(key));
        return store == null ? legacy : store;
    }

    /** True when the value still lives only in the pre-split file. */
    private boolean pendingInLegacy(MMKV store, String key) {
        return !store.containsKey(key) && legacy.containsKey(key);
    }

    @Override
    public String getString(String key, @Nullable String defValue) {
        MMKV store = storeFor(key);
        if (pendingInLegacy(store, key)) {
            String value = legacy.getString(key, defValue);
            store.putString(key, value);
            return value;
        }
        return store.getString(key, defValue);
    }

    @Nullable
    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        MMKV store = storeFor(key);
        if (pendingInLegacy(store, key)) {
            Set<String> value = legacy.getStringSet(key, defValues);
            store.putStringSet(key, value);
            return value;
        }
        return store.getStringSet(key, defValues);
    }

    @Override
    public int getInt(String key, int defValue) {
        MMKV store = storeFor(key);
        if (pendingInLegacy(store, key)) {
            int value = legacy.getInt(key, defValue);
            store.putInt(key, value);
            return value;
        }
        return store.getInt(key, defValue);
    }

    @Override
    public long getLong(String key, long defValue) {
        MMKV store = storeFor(key);
        if (pendingInLegacy(store, key)) {
            long value = legacy.getLong(key, defValue);
            store.putLong(key, value);
            return value;
        }
        return store.getLong(key, defValue);
    }

    @Override
    public float getFloat(String key, float defValue) {
        MMKV store = storeFor(key);
        if (pendingInLegacy(store, key)) {
            float value = legacy.getFloat(key, defValue);
            store.putFloat(key, value);
            return value;
        }
        return store.getFloat(key, defValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        MMKV store = storeFor(key);
        if (pendingInLegacy(store, key)) {
            boolean value = legacy.getBoolean(key, defValue);
            store.putBoolean(key, value);
            return value;
        }
        return store.getBoolean(key, defValue);
    }

    @Override
    public boolean contains(String key) {
        return storeFor(key).containsKey(key) || legacy.containsKey(key);
    }

    @Override
    public Map<String, ?> getAll() {
        // MMKV does not retain value types, so no store here can rebuild a generic
        // map. Nothing reads these preferences that way; fail loudly if that changes.
        throw new UnsupportedOperationException("getAll() is not supported on MMKV-backed preferences");
    }

    @Override
    public Editor edit() {
        return new RoutedEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (listeners) {
            listeners.put(listener, PRESENT);
        }
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyChanged(List<String> changedKeys) {
        if (changedKeys.isEmpty()) {
            return;
        }
        List<OnSharedPreferenceChangeListener> snapshot;
        synchronized (listeners) {
            if (listeners.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(listeners.keySet());
        }
        boolean onMain = Looper.myLooper() == Looper.getMainLooper();
        for (OnSharedPreferenceChangeListener listener : snapshot) {
            if (listener == null) {
                continue;
            }
            for (String key : changedKeys) {
                if (onMain) {
                    listener.onSharedPreferenceChanged(this, key);
                } else {
                    // SharedPreferences guarantees listeners run on the main thread.
                    mainHandler.post(() -> listener.onSharedPreferenceChanged(this, key));
                }
            }
        }
    }

    private final class RoutedEditor implements Editor {

        private final Map<String, Object> puts = new LinkedHashMap<>();
        private final Set<String> removes = new LinkedHashSet<>();
        private boolean clear;

        private Editor stage(String key, @Nullable Object value) {
            // SharedPreferences treats putString/putStringSet(key, null) as a removal.
            if (value == null) {
                return remove(key);
            }
            removes.remove(key);
            puts.put(key, value);
            return this;
        }

        @Override
        public Editor putString(String key, @Nullable String value) {
            return stage(key, value);
        }

        @Override
        public Editor putStringSet(String key, @Nullable Set<String> values) {
            return stage(key, values == null ? null : new LinkedHashSet<>(values));
        }

        @Override
        public Editor putInt(String key, int value) {
            return stage(key, value);
        }

        @Override
        public Editor putLong(String key, long value) {
            return stage(key, value);
        }

        @Override
        public Editor putFloat(String key, float value) {
            return stage(key, value);
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            return stage(key, value);
        }

        @Override
        public Editor remove(String key) {
            puts.remove(key);
            removes.add(key);
            return this;
        }

        @Override
        public Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            applyChanges();
            return true;
        }

        @Override
        public void apply() {
            applyChanges();
        }

        @SuppressWarnings("unchecked")
        private void applyChanges() {
            List<String> changed = new ArrayList<>(puts.size() + removes.size());
            if (clear) {
                for (MMKV store : areas.values()) {
                    store.clearAll();
                }
                legacy.clearAll();
            }
            for (String key : removes) {
                storeFor(key).remove(key);
                // The pre-split copy has to go too, or the next read would fall
                // through and resurrect the value that was just removed.
                legacy.remove(key);
                changed.add(key);
            }
            for (Map.Entry<String, Object> entry : puts.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                MMKV store = storeFor(key);
                if (value instanceof String) {
                    store.putString(key, (String) value);
                } else if (value instanceof Boolean) {
                    store.putBoolean(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    store.putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    store.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    store.putFloat(key, (Float) value);
                } else if (value instanceof Set) {
                    store.putStringSet(key, (Set<String>) value);
                } else {
                    continue;
                }
                changed.add(key);
            }
            notifyChanged(changed);
        }
    }
}
