package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceDataStore;
import com.tencent.mmkv.MMKV;
import java.util.HashMap;
import java.util.Map;

// Process-safe key-value storage for state shared between the main UI process
// and the :tunnel process. Plain SharedPreferences opened with the deprecated
// MODE_MULTI_PROCESS corrupted (and could wipe) the whole file when one process
// was force-killed mid-write while another held its own cached copy. MMKV is
// crash-safe (mmap + CRC, no truncate window) and coherent across processes, so
// it replaces those multi-process SharedPreferences. Each instance migrates the
// values from its legacy SharedPreferences file once, then becomes the source
// of truth. MMKV implements the SharedPreferences interface, so call sites that
// used getSharedPreferences keep working unchanged.
public final class MmkvPrefs {

    private static final String MIGRATED_FLAG_KEY = "__mmkv_migrated_from_xml__";
    private static final String MAIN_PREFS_ID = "wingsv_main_prefs";

    private static volatile boolean initialized;
    private static volatile MmkvRoutedPreferences mainPrefs;
    private static volatile MmkvPreferenceDataStore mainDataStore;

    private MmkvPrefs() {}

    public static void ensureInitialized(Context context) {
        if (initialized) {
            return;
        }
        synchronized (MmkvPrefs.class) {
            if (!initialized) {
                MmkvCompat.initialize(context);
                initialized = true;
            }
        }
    }

    public static SharedPreferences multiProcess(Context context, String id, String legacyXmlName) {
        ensureInitialized(context);
        MMKV kv = MmkvCompat.open(id);
        if (!kv.getBoolean(MIGRATED_FLAG_KEY, false)) {
            SharedPreferences legacy = context
                .getApplicationContext()
                .getSharedPreferences(legacyXmlName, Context.MODE_PRIVATE);
            if (!legacy.getAll().isEmpty()) {
                kv.importFromSharedPreferences(legacy);
            }
            kv.putBoolean(MIGRATED_FLAG_KEY, true);
        }
        return kv;
    }

    // The main settings store: a listenable SharedPreferences spread over one MMKV
    // file per subsystem, replacing the default <pkg>_preferences XML file and the
    // single file that first replaced it. A single instance per process keeps
    // OnSharedPreferenceChangeListener registrations consistent.
    public static SharedPreferences mainPrefs(Context context) {
        MmkvRoutedPreferences cached = mainPrefs;
        if (cached != null) {
            return cached;
        }
        synchronized (MmkvPrefs.class) {
            if (mainPrefs == null) {
                ensureInitialized(context);
                // The pre-split file is still opened: it holds every key written
                // before the split and the routed store reads through to it until
                // each key has been touched once. A fresh install finds it empty.
                MMKV legacy = MmkvCompat.open(MAIN_PREFS_ID);
                if (!legacy.getBoolean(MIGRATED_FLAG_KEY, false)) {
                    SharedPreferences xml = context
                        .getApplicationContext()
                        .getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
                    if (!xml.getAll().isEmpty()) {
                        legacy.importFromSharedPreferences(xml);
                    }
                    legacy.putBoolean(MIGRATED_FLAG_KEY, true);
                }
                Map<String, MMKV> areas = new HashMap<>();
                for (String area : MmkvPrefsAreas.allAreas()) {
                    areas.put(area, MmkvCompat.open(area));
                }
                mainPrefs = new MmkvRoutedPreferences(areas, legacy);
            }
            return mainPrefs;
        }
    }

    // PreferenceDataStore over the main store, for PreferenceFragmentCompat
    // screens so their widgets read and write MMKV instead of the XML file.
    public static PreferenceDataStore mainDataStore(Context context) {
        MmkvPreferenceDataStore cached = mainDataStore;
        if (cached != null) {
            return cached;
        }
        synchronized (MmkvPrefs.class) {
            if (mainDataStore == null) {
                mainDataStore = new MmkvPreferenceDataStore(mainPrefs(context));
            }
            return mainDataStore;
        }
    }
}
