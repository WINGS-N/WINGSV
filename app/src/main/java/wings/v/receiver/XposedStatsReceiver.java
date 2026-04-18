package wings.v.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import wings.v.core.XposedAttackStatsStore;

public class XposedStatsReceiver extends BroadcastReceiver {

    public static final String ACTION_RECORD_EVENT = "wings.v.intent.action.XPOSED_RECORD_EVENT";
    public static final String ACTION_STATS_UPDATED = "wings.v.intent.action.XPOSED_STATS_UPDATED";
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    public static final String EXTRA_VECTOR = "vector";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_CALLER_METHOD = "caller_method";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_TIMESTAMP = "timestamp";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !TextUtils.equals(ACTION_RECORD_EVENT, intent.getAction())) {
            return;
        }
        String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
        String vector = intent.getStringExtra(EXTRA_VECTOR);
        String source = intent.getStringExtra(EXTRA_SOURCE);
        String callerMethod = intent.getStringExtra(EXTRA_CALLER_METHOD);
        String detail = intent.getStringExtra(EXTRA_DETAIL);
        long timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis());
        XposedAttackStatsStore.recordEvent(
            context,
            new XposedAttackStatsStore.AttackEvent(timestamp, packageName, vector, source, callerMethod, detail)
        );
        notifyStatsUpdated(context);
    }

    private void notifyStatsUpdated(@NonNull Context context) {
        Intent updateIntent = new Intent(ACTION_STATS_UPDATED).setPackage(context.getPackageName());
        context.sendBroadcast(updateIntent);
    }
}
