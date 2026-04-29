package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import wings.v.core.WingsImportParser.ImportedConfig;

public final class ImportConfigSummary {

    private static final int MAX_LIST_ITEMS = 5;

    private ImportConfigSummary() {}

    @NonNull
    public static String forUser(@NonNull Context context, @NonNull ImportedConfig cfg) {
        List<String> sections = new ArrayList<>();

        sections.add(buildHeader(cfg));

        String turn = buildTurnBlock(cfg);
        if (turn != null) {
            sections.add(turn);
        }

        String wg = buildWireGuardBlock(cfg);
        if (wg != null) {
            sections.add(wg);
        }

        String xray = buildXrayBlock(cfg);
        if (xray != null) {
            sections.add(xray);
        }

        String awg = buildAmneziaBlock(cfg);
        if (awg != null) {
            sections.add(awg);
        }

        String routing = buildXrayRoutingBlock(cfg);
        if (routing != null) {
            sections.add(routing);
        }

        String appRouting = buildAppRoutingBlock(cfg);
        if (appRouting != null) {
            sections.add(appRouting);
        }

        return TextUtils.join("\n\n", sections);
    }

    @NonNull
    private static String buildHeader(@NonNull ImportedConfig cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("Backend: ").append(backendLabel(cfg.backendType));
        if (cfg.hasAllSettings) {
            sb.append("\nТип: Все настройки");
        } else if (cfg.xrayMergeOnly) {
            sb.append("\nТип: Только дополнение Xray");
        }
        return sb.toString();
    }

    @Nullable
    private static String buildTurnBlock(@NonNull ImportedConfig cfg) {
        if (!cfg.hasTurnSettings) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("VK TURN");
        addLine(lines, "TURN", cfg.endpoint);
        addLine(lines, "Local endpoint", cfg.localEndpoint);
        addLine(lines, "Сессия", cfg.turnSessionMode);
        if (cfg.threads != null) {
            addLine(lines, "Воркеров", String.valueOf(cfg.threads));
        }
        if (cfg.credsGroupSize != null) {
            addLine(lines, "Размер группы кредов", String.valueOf(cfg.credsGroupSize));
        }
        if (cfg.useUdp != null) {
            addLine(lines, "Транспорт", cfg.useUdp ? "UDP" : "TCP");
        }
        if (cfg.noObfuscation != null) {
            addLine(lines, "Обфускация", cfg.noObfuscation ? "Выкл" : "Вкл");
        }
        if (cfg.links != null && !cfg.links.isEmpty()) {
            addLine(lines, "VK ссылок", String.valueOf(cfg.links.size()));
            int shown = Math.min(cfg.links.size(), MAX_LIST_ITEMS);
            for (int i = 0; i < shown; i++) {
                lines.add("  " + (i + 1) + ". " + truncate(cfg.links.get(i), 64));
            }
            if (cfg.links.size() > shown) {
                lines.add("  …и ещё " + (cfg.links.size() - shown));
            }
        } else {
            addLine(lines, "Ссылка", truncate(cfg.link, 64));
        }
        if (!TextUtils.isEmpty(cfg.linkSecondary)) {
            addLine(lines, "Резервная ссылка", truncate(cfg.linkSecondary, 64));
        }
        if (lines.size() <= 1) {
            return null;
        }
        return TextUtils.join("\n", lines);
    }

    @Nullable
    private static String buildWireGuardBlock(@NonNull ImportedConfig cfg) {
        if (!cfg.hasWireGuardSettings) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("WireGuard");
        addLine(lines, "Endpoint", cfg.wgEndpoint);
        addLine(lines, "Адреса", cfg.wgAddresses);
        addLine(lines, "DNS", cfg.wgDns);
        if (cfg.wgMtu != null) {
            addLine(lines, "MTU", String.valueOf(cfg.wgMtu));
        }
        addLine(lines, "Allowed IPs", cfg.wgAllowedIps);
        if (lines.size() <= 1) {
            return null;
        }
        return TextUtils.join("\n", lines);
    }

    @Nullable
    private static String buildXrayBlock(@NonNull ImportedConfig cfg) {
        boolean hasProfiles = cfg.xrayProfiles != null && !cfg.xrayProfiles.isEmpty();
        boolean hasSubs = cfg.xraySubscriptions != null && !cfg.xraySubscriptions.isEmpty();
        if (!hasProfiles && !hasSubs && !cfg.hasXraySettings && !cfg.hasXraySubscriptionJson) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Xray");
        if (hasProfiles) {
            addLine(lines, "Профилей", String.valueOf(cfg.xrayProfiles.size()));
            int shown = Math.min(cfg.xrayProfiles.size(), MAX_LIST_ITEMS);
            for (int i = 0; i < shown; i++) {
                XrayProfile p = cfg.xrayProfiles.get(i);
                String label = profileLabel(p);
                if (!TextUtils.isEmpty(label)) {
                    lines.add("  " + (i + 1) + ". " + truncate(label, 64));
                }
            }
            if (cfg.xrayProfiles.size() > shown) {
                lines.add("  …и ещё " + (cfg.xrayProfiles.size() - shown));
            }
        }
        if (hasSubs) {
            addLine(lines, "Подписок", String.valueOf(cfg.xraySubscriptions.size()));
            int shown = Math.min(cfg.xraySubscriptions.size(), MAX_LIST_ITEMS);
            for (int i = 0; i < shown; i++) {
                XraySubscription s = cfg.xraySubscriptions.get(i);
                String label = subscriptionLabel(s);
                if (!TextUtils.isEmpty(label)) {
                    lines.add("  " + (i + 1) + ". " + truncate(label, 64));
                }
            }
            if (cfg.xraySubscriptions.size() > shown) {
                lines.add("  …и ещё " + (cfg.xraySubscriptions.size() - shown));
            }
        }
        if (cfg.hasXraySettings && cfg.xraySettings != null && cfg.xraySettings.transportMode != null) {
            addLine(lines, "Транспорт", cfg.xraySettings.transportMode.name());
        }
        if (lines.size() <= 1) {
            return null;
        }
        return TextUtils.join("\n", lines);
    }

    @Nullable
    private static String buildAmneziaBlock(@NonNull ImportedConfig cfg) {
        if (!cfg.hasAmneziaSettings || TextUtils.isEmpty(cfg.awgQuickConfig)) {
            return null;
        }
        int lineCount = cfg.awgQuickConfig.split("\\R").length;
        return "AmneziaWG\nКонфиг: " + lineCount + " строк";
    }

    @Nullable
    private static String buildXrayRoutingBlock(@NonNull ImportedConfig cfg) {
        if (!cfg.hasXrayRouting) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Xray routing");
        if (cfg.xrayRoutingRules != null && !cfg.xrayRoutingRules.isEmpty()) {
            addLine(lines, "Правил", String.valueOf(cfg.xrayRoutingRules.size()));
        }
        addLine(lines, "GeoIP", cfg.xrayRoutingGeoipUrl);
        addLine(lines, "Geosite", cfg.xrayRoutingGeositeUrl);
        if (lines.size() <= 1) {
            return null;
        }
        return TextUtils.join("\n", lines);
    }

    @Nullable
    private static String buildAppRoutingBlock(@NonNull ImportedConfig cfg) {
        if (!cfg.hasAppRouting) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Per-app routing");
        if (cfg.appRoutingBypass != null) {
            addLine(lines, "Режим", cfg.appRoutingBypass ? "Bypass" : "Только эти приложения");
        }
        if (cfg.appRoutingPackages != null && !cfg.appRoutingPackages.isEmpty()) {
            addLine(lines, "Пакетов", String.valueOf(cfg.appRoutingPackages.size()));
        }
        if (lines.size() <= 1) {
            return null;
        }
        return TextUtils.join("\n", lines);
    }

    private static void addLine(@NonNull List<String> lines, @NonNull String label, @Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        lines.add(label + ": " + value);
    }

    @NonNull
    private static String profileLabel(@Nullable XrayProfile profile) {
        if (profile == null) {
            return "";
        }
        String title = profile.title == null ? "" : profile.title.trim();
        String address = profile.address == null ? "" : profile.address.trim();
        if (profile.port > 0 && !address.isEmpty()) {
            address = address + ":" + profile.port;
        }
        if (!title.isEmpty() && !address.isEmpty() && !title.equals(address)) {
            return title + " · " + address;
        }
        return !title.isEmpty() ? title : address;
    }

    @NonNull
    private static String subscriptionLabel(@Nullable XraySubscription subscription) {
        if (subscription == null) {
            return "";
        }
        String title = subscription.title == null ? "" : subscription.title.trim();
        String url = subscription.url == null ? "" : subscription.url.trim();
        if (!title.isEmpty() && !url.isEmpty() && !title.equals(url)) {
            return title + " · " + url;
        }
        return !title.isEmpty() ? title : url;
    }

    @NonNull
    private static String backendLabel(@Nullable BackendType backend) {
        if (backend == null) {
            return "WINGS V";
        }
        switch (backend) {
            case VK_TURN_WIREGUARD:
                return "VK TURN + WireGuard";
            case XRAY:
                return "Xray";
            case AMNEZIAWG:
                return "AmneziaWG";
            case WIREGUARD:
                return "WireGuard";
            case AMNEZIAWG_PLAIN:
                return "AmneziaWG Plain";
            default:
                return "WINGS V";
        }
    }

    @NonNull
    private static String truncate(@Nullable String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }
}
