package wings.v.core;

import android.content.Context;
import com.tencent.mmkv.MMKV;
import com.tencent.mmkv.MMKVConfig;
import com.tencent.mmkv.MMKVRecoverStrategic;

/**
 * MMKV 2.x binding (arm64).
 *
 * <p>MMKV 2.x dropped the 32-bit ABI, so the two ABIs build against different
 * majors and everything version-specific lives in this class, once per flavor.
 * This variant opens stores through MMKVConfig, which 1.3.x has no equivalent for.
 */
final class MmkvCompat {

    private MmkvCompat() {}

    static void initialize(Context context) {
        MMKV.initialize(context.getApplicationContext());
        MMKV.registerHandler(new MmkvErrorHandler());
    }

    static MMKV open(String id) {
        MMKVConfig config = new MMKVConfig();
        config.mode = MMKV.MULTI_PROCESS_MODE;
        // Both processes rewrite the same settings on every sync, and most of those
        // writes carry the value already stored. Comparing first turns them into
        // no-ops, which keeps the file from growing and forcing a full rewrite.
        config.enableCompareBeforeSet = true;
        // Salvage a damaged store rather than starting it empty; the same choice the
        // error handler makes when the library asks.
        config.recover = MMKVRecoverStrategic.OnErrorRecover;
        return MMKV.mmkvWithID(id, config);
    }
}
