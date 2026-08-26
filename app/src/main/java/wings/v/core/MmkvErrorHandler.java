package wings.v.core;

import android.util.Log;
import com.tencent.mmkv.MMKVHandler;
import com.tencent.mmkv.MMKVLogLevel;
import com.tencent.mmkv.MMKVRecoverStrategic;

/**
 * Decides what happens when MMKV finds a store damaged.
 *
 * <p>Without a handler the library picks for us and the settings simply vanish,
 * which is exactly the failure this storage was moved to MMKV to prevent. A CRC
 * mismatch or a wrong file length usually costs the tail of the file rather than
 * all of it, so recovering keeps whatever is still readable instead of discarding
 * a whole subsystem's settings. The store id is logged so a damaged file can be
 * traced to the feature that owns it.
 */
final class MmkvErrorHandler implements MMKVHandler {

    private static final String TAG = "MmkvErrorHandler";

    @Override
    public MMKVRecoverStrategic onMMKVCRCCheckFail(String mmapID) {
        Log.w(TAG, "CRC check failed for " + mmapID + ", recovering what is readable");
        return MMKVRecoverStrategic.OnErrorRecover;
    }

    @Override
    public MMKVRecoverStrategic onMMKVFileLengthError(String mmapID) {
        Log.w(TAG, "file length error for " + mmapID + ", recovering what is readable");
        return MMKVRecoverStrategic.OnErrorRecover;
    }

    @Override
    public boolean wantLogRedirecting() {
        // MMKV's own logging goes to logcat already; redirecting it would only add
        // a hop and force this class to stay alive for the process lifetime.
        return false;
    }

    @Override
    public void mmkvLog(MMKVLogLevel level, String file, int line, String function, String message) {
        // Not reached while wantLogRedirecting() is false.
    }
}
