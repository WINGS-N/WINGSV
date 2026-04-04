package wings.v;

import android.app.Application;

import wings.v.core.AppPrefs;
import wings.v.core.AppUpdateBackgroundScheduler;

public class WingsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppPrefs.ensureDefaults(this);
        AppUpdateBackgroundScheduler.schedule(this);
    }
}
