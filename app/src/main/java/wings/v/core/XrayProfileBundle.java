package wings.v.core;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class XrayProfileBundle {

    private XrayProfileBundle() {}

    static void write(@NonNull Bundle bundle, @NonNull String prefix, @Nullable XrayProfile profile) {
        if (profile == null) {
            bundle.putBoolean(prefix + "present", false);
            return;
        }
        bundle.putBoolean(prefix + "present", true);
        bundle.putString(prefix + "id", profile.id);
        bundle.putString(prefix + "title", profile.title);
        bundle.putString(prefix + "raw_link", profile.rawLink);
        bundle.putString(prefix + "subscription_id", profile.subscriptionId);
        bundle.putString(prefix + "subscription_title", profile.subscriptionTitle);
        bundle.putString(prefix + "address", profile.address);
        bundle.putInt(prefix + "port", profile.port);
    }

    @Nullable
    static XrayProfile read(@NonNull Bundle bundle, @NonNull String prefix) {
        if (!bundle.getBoolean(prefix + "present", false)) {
            return null;
        }
        return new XrayProfile(
            bundle.getString(prefix + "id", ""),
            bundle.getString(prefix + "title", ""),
            bundle.getString(prefix + "raw_link", ""),
            bundle.getString(prefix + "subscription_id", ""),
            bundle.getString(prefix + "subscription_title", ""),
            bundle.getString(prefix + "address", ""),
            bundle.getInt(prefix + "port", 0)
        );
    }
}
