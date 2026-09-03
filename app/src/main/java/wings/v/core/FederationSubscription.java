package wings.v.core;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Подписка федерации в списке подписок приложения.
 *
 * <p>Заводится и снимается вместе с аккаунтом: она выдана ему, и держать её
 * после выхода незачем - обновляться она всё равно перестанет.
 */
public final class FederationSubscription {

    /** Идентификатор один на всё приложение: подписка федерации ровно одна */
    public static final String ID = "wingsvpn-federation";

    private FederationSubscription() {}

    /** Заводит подписку или обновляет её адрес, ничего не дублируя */
    public static void ensure(@NonNull Context context, @NonNull String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        List<XraySubscription> current = new ArrayList<>(XrayStore.getSubscriptions(context));
        for (XraySubscription existing : current) {
            if (existing != null && ID.equals(existing.id) && url.equals(existing.url)) {
                return;
            }
        }
        current.removeIf(existing -> existing != null && ID.equals(existing.id));
        current.add(
            new XraySubscription(
                ID,
                context.getString(wings.v.R.string.wings_account_title),
                url,
                "auto",
                0,
                true,
                0L,
                0L,
                0L,
                0L,
                0L
            )
        );
        XrayStore.setSubscriptions(context, current);
        try {
            XraySubscriptionUpdater.refreshAll(context);
        } catch (Exception ignored) {
            // Сеть подведёт - обновление подхватит следующий цикл
        }
    }

    /** Снимает подписку вместе с профилями, которые она принесла */
    public static void remove(@NonNull Context context) {
        List<XraySubscription> current = new ArrayList<>(XrayStore.getSubscriptions(context));
        if (!current.removeIf(existing -> existing != null && ID.equals(existing.id))) {
            return;
        }
        XrayStore.setSubscriptions(context, current);
        List<XrayProfile> profiles = new ArrayList<>(XrayStore.getProfiles(context));
        if (profiles.removeIf(profile -> profile != null && ID.equals(profile.subscriptionId))) {
            XrayStore.setProfiles(context, profiles);
        }
        VkTurnProfileStore.syncSubscriptionProfiles(context, ID, new ArrayList<>());
        WireGuardProfileStore.syncSubscriptionProfiles(context, ID, new ArrayList<>());
        AmneziaProfileStore.syncSubscriptionProfiles(context, ID, new ArrayList<>());
    }

    /** Заведена ли подписка сейчас */
    public static boolean exists(@NonNull Context context) {
        for (XraySubscription existing : XrayStore.getSubscriptions(context)) {
            if (existing != null && ID.equals(existing.id)) {
                return true;
            }
        }
        return false;
    }

    /** Адрес заведённой подписки или пусто */
    @NonNull
    public static String url(@NonNull Context context) {
        for (XraySubscription existing : XrayStore.getSubscriptions(context)) {
            if (existing != null && ID.equals(existing.id)) {
                return existing.url == null ? "" : existing.url;
            }
        }
        return "";
    }
}
