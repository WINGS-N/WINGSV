package wings.v.core;

import android.content.Context;
import android.content.SharedPreferences;
import com.tencent.mmkv.MMKV;

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

    private static volatile boolean initialized;

    private MmkvPrefs() {}

    public static void ensureInitialized(Context context) {
        if (initialized) {
            return;
        }
        synchronized (MmkvPrefs.class) {
            if (!initialized) {
                MMKV.initialize(context.getApplicationContext());
                initialized = true;
            }
        }
    }

    public static SharedPreferences multiProcess(Context context, String id, String legacyXmlName) {
        ensureInitialized(context);
        MMKV kv = MMKV.mmkvWithID(id, MMKV.MULTI_PROCESS_MODE);
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
}
