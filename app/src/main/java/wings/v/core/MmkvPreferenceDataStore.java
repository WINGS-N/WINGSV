package wings.v.core;

import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceDataStore;
import java.util.Set;

// Bridges the AndroidX Preference framework to the MMKV-backed main preferences.
// PreferenceFragmentCompat screens set this as their data store so their widgets
// read and write MMKV instead of the default XML SharedPreferences. Writes go
// through SharedPreferences.edit().apply() on the wrapper, so its change
// listeners still fire when a widget is toggled.
@SuppressWarnings("PMD.CommentRequired")
public final class MmkvPreferenceDataStore extends PreferenceDataStore {

    private final SharedPreferences prefs;

    public MmkvPreferenceDataStore(@NonNull SharedPreferences prefs) {
        this.prefs = prefs;
    }

    @Override
    public void putString(String key, @Nullable String value) {
        prefs.edit().putString(key, value).apply();
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) {
        return prefs.getString(key, defValue);
    }

    @Override
    public void putStringSet(String key, @Nullable Set<String> values) {
        prefs.edit().putStringSet(key, values).apply();
    }

    @Nullable
    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        return prefs.getStringSet(key, defValues);
    }

    @Override
    public void putInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    @Override
    public int getInt(String key, int defValue) {
        return prefs.getInt(key, defValue);
    }

    @Override
    public void putLong(String key, long value) {
        prefs.edit().putLong(key, value).apply();
    }

    @Override
    public long getLong(String key, long defValue) {
        return prefs.getLong(key, defValue);
    }

    @Override
    public void putFloat(String key, float value) {
        prefs.edit().putFloat(key, value).apply();
    }

    @Override
    public float getFloat(String key, float defValue) {
        return prefs.getFloat(key, defValue);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return prefs.getBoolean(key, defValue);
    }
}
