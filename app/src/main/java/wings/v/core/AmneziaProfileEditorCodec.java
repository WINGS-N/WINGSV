package wings.v.core;

import android.text.TextUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.amnezia.awg.config.Config;

/**
 * Converts an AmneziaProfile to and from an editable awg-quick text block for
 * the profile text editor. toEditableQuickConfig returns the profile's stored
 * raw config; parseQuickConfig validates a pasted/edited awg-quick config via
 * org.amnezia.awg.config.Config (throws on invalid) and returns a new
 * AmneziaProfile that keeps the base profile's id and title while storing the
 * normalized raw text. Mirrors XrayProfileEditorCodec for the AmneziaWG backend.
 */
@SuppressWarnings(
    {
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.OnlyOneReturn",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
    }
)
public final class AmneziaProfileEditorCodec {

    private AmneziaProfileEditorCodec() {}

    public static String toEditableQuickConfig(AmneziaProfile profile) {
        return profile == null ? "" : profile.quickConfig;
    }

    public static AmneziaProfile parseQuickConfig(AmneziaProfile base, String rawConfig) throws Exception {
        String normalized = normalize(rawConfig);
        if (TextUtils.isEmpty(normalized)) {
            throw new IllegalArgumentException("Empty AmneziaWG config");
        }
        // Validation: throws BadConfigException on malformed awg-quick input.
        Config parsed = Config.parse(new ByteArrayInputStream(normalized.getBytes(StandardCharsets.UTF_8)));
        if (parsed.getPeers().isEmpty()) {
            throw new IllegalArgumentException("AmneziaWG config has no [Peer]");
        }
        String id = base == null ? null : base.id;
        String title = base == null ? "" : base.title;
        return new AmneziaProfile(id, title, normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace("\r\n", "\n");
    }
}
