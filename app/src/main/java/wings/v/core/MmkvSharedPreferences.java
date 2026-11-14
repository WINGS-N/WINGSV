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

// SharedPreferences on top of MMKV in multi-process mode: mmap + CRC, no truncate
// window, so the main and :tunnel processes can write the main settings file at the
// same time without corrupting it.
//
// MMKV implements SharedPreferences itself but has NO change listeners, so
// in-process OnSharedPreferenceChangeListener support is added here. Dispatch is
// always on the main thread, as the SharedPreferences contract promises: a listener
// fired on a worker thread races the Fragment lifecycle and crashes with "Fragment
// not attached to a context". Cross-process change callbacks are not delivered
@SuppressWarnings({ "PMD.CommentRequired", "PMD.AvoidSynchronizedAtMethodLevel", "PMD.GodClass" })
public final class MmkvSharedPreferences implements SharedPreferences {

    private static final Object PRESENT = new Object();

    private final MMKV kv;
    private final Map<OnSharedPreferenceChangeListener, Object> listeners = new WeakHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    MmkvSharedPreferences(@NonNull MMKV kv) {
        this.kv = kv;
    }

    @Override
    public String getString(String key, @Nullable String defValue) {
        return kv.getString(key, defValue);
    }

    @Nullable
    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        return kv.getStringSet(key, defValues);
    }

    @Override
    public int getInt(String key, int defValue) {
        return kv.getInt(key, defValue);
    }

    @Override
    public long getLong(String key, long defValue) {
        return kv.getLong(key, defValue);
    }

    @Override
    public float getFloat(String key, float defValue) {
        return kv.getFloat(key, defValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return kv.getBoolean(key, defValue);
    }

    @Override
    public boolean contains(String key) {
        return kv.contains(key);
    }

    @Override
    public Map<String, ?> getAll() {
        // MMKV keeps no value types, so a generic map cannot be rebuilt. Nothing
        // reads the main prefs through getAll(), and if that changes it should blow
        // up loudly rather than hand back half the data
        throw new UnsupportedOperationException("getAll() is not supported on MMKV-backed preferences");
    }

    @Override
    public Editor edit() {
        return new MmkvEditor();
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
                    // SharedPreferences promises listeners on the main thread, and a
                    // write from a worker (the root su probe, say) must not drag UI
                    // listeners onto it
                    mainHandler.post(() -> listener.onSharedPreferenceChanged(this, key));
                }
            }
        }
    }

    private final class MmkvEditor implements Editor {

        private final Map<String, Object> puts = new LinkedHashMap<>();
        private final Set<String> removes = new LinkedHashSet<>();
        private boolean clear;

        private Editor stage(String key, @Nullable Object value) {
            // SharedPreferences treats putString/putStringSet(key, null) as a removal
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
                kv.clearAll();
            }
            for (String key : removes) {
                kv.remove(key);
                changed.add(key);
            }
            for (Map.Entry<String, Object> entry : puts.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof String) {
                    kv.putString(key, (String) value);
                } else if (value instanceof Boolean) {
                    kv.putBoolean(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    kv.putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    kv.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    kv.putFloat(key, (Float) value);
                } else if (value instanceof Set) {
                    kv.putStringSet(key, (Set<String>) value);
                } else {
                    continue;
                }
                changed.add(key);
            }
            notifyChanged(changed);
        }
    }
}
