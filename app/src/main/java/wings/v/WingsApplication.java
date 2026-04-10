package wings.v;

import android.app.Application;
import wings.v.core.ActiveProbingBackgroundScheduler;
import wings.v.core.AppPrefs;
import wings.v.core.AppUpdateBackgroundScheduler;
import wings.v.core.XraySubscriptionBackgroundScheduler;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AtLeastOneConstructor" })
public class WingsApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppPrefs.ensureDefaults(this);
        AppUpdateBackgroundScheduler.schedule(this);
        ActiveProbingBackgroundScheduler.refresh(this);
        XraySubscriptionBackgroundScheduler.refresh(this);
    }
}
