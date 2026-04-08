package wings.v.ui;

import android.graphics.drawable.Drawable;

final class AppRoutingEntry {

    final String label;
    final String packageName;
    final Drawable icon;
    final boolean systemApp;

    AppRoutingEntry(String label, String packageName, Drawable icon, boolean systemApp) {
        this.label = label;
        this.packageName = packageName;
        this.icon = icon;
        this.systemApp = systemApp;
    }
}
