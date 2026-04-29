package wings.v.core;

import android.os.Bundle;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class ByeDpiSettingsBundle {

    private ByeDpiSettingsBundle() {}

    static void write(@NonNull Bundle bundle, @NonNull String prefix, @Nullable ByeDpiSettings settings) {
        if (settings == null) {
            return;
        }
        bundle.putString(prefix + "dial_host", settings.resolveRuntimeDialHost());
        bundle.putInt(prefix + "dial_port", settings.resolveRuntimeListenPort());
        bundle.putBoolean(prefix + "auth_enabled", settings.proxyAuthEnabled);
        bundle.putString(prefix + "username", settings.resolveRuntimeProxyUsername());
        bundle.putString(prefix + "password", settings.resolveRuntimeProxyPassword());
    }

    @NonNull
    static ByeDpiSettings read(@NonNull Bundle bundle, @NonNull String prefix) {
        ByeDpiSettings settings = new ByeDpiSettings();
        settings.useCommandLineSettings = false;
        String host = bundle.getString(prefix + "dial_host", "127.0.0.1");
        settings.proxyIp = TextUtils.isEmpty(host) ? "127.0.0.1" : host;
        settings.proxyPort = bundle.getInt(prefix + "dial_port", 1080);
        settings.proxyAuthEnabled = bundle.getBoolean(prefix + "auth_enabled", false);
        settings.proxyUsername = bundle.getString(prefix + "username", "");
        settings.proxyPassword = bundle.getString(prefix + "password", "");
        return settings;
    }
}
