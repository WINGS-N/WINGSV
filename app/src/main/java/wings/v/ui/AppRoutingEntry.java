package wings.v.ui;

import android.graphics.drawable.Drawable;

final class AppRoutingEntry {
    final String label;
    final String packageName;
    final Drawable icon;

    AppRoutingEntry(String label, String packageName, Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.icon = icon;
    }
}
