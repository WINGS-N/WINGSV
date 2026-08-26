package wings.v.core;

import android.content.Context;
import com.tencent.mmkv.MMKV;

/**
 * MMKV 1.3.x LTS binding (armeabi-v7a).
 *
 * <p>MMKV 2.x dropped the 32-bit ABI, so this flavor stays on the LTS line, which
 * carries the same data-safety fixes but has no MMKVConfig: stores are opened with
 * the plain multi-process factory. The error handler is shared - registerHandler
 * exists in both majors.
 */
final class MmkvCompat {

    private MmkvCompat() {}

    static void initialize(Context context) {
        MMKV.initialize(context.getApplicationContext());
        MMKV.registerHandler(new MmkvErrorHandler());
    }

    static MMKV open(String id) {
        return MMKV.mmkvWithID(id, MMKV.MULTI_PROCESS_MODE);
    }
}
