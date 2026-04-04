package wings.v.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public enum CaptchaPromptSource {
    PRIMARY("primary"),
    POOL("pool");

    public final String wireValue;

    CaptchaPromptSource(String wireValue) {
        this.wireValue = wireValue;
    }

    @NonNull
    public static CaptchaPromptSource fromWireValue(@Nullable String rawValue) {
        if (rawValue == null) {
            return PRIMARY;
        }
        String normalized = rawValue.trim().toLowerCase();
        if (POOL.wireValue.equals(normalized) || "background".equals(normalized)) {
            return POOL;
        }
        return PRIMARY;
    }

    public boolean stopsConnectionOnCancel() {
        return this == PRIMARY;
    }
}
