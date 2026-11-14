package wings.v.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.R;
import wings.v.core.AvatarFetcher;
import wings.v.core.FederationAccount;

/**
 * Карточка аккаунта первой строкой в настройках.
 *
 * <p>Имя, состояние и аватар - то же, что на экране аккаунта, чтобы не лазить
 * туда ради проверки
 */
public final class AccountCardPreference extends Preference {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public AccountCardPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_account_card);
    }

    /** Перерисовка после возврата: имя и аватар могли смениться */
    public void refresh() {
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Context context = getContext();
        TextView name = (TextView) holder.findViewById(R.id.account_card_name);
        TextView summary = (TextView) holder.findViewById(R.id.account_card_summary);
        ImageView avatar = (ImageView) holder.findViewById(R.id.account_card_avatar);

        boolean signedIn = FederationAccount.isSignedIn(context);
        String username = FederationAccount.username(context);
        name.setText(
            signedIn && !TextUtils.isEmpty(username) ? username : context.getString(R.string.wings_account_title)
        );
        summary.setText(
            signedIn
                ? context.getString(R.string.wings_account_title)
                : context.getString(R.string.wings_account_summary)
        );

        String url = AvatarFetcher.sized(FederationAccount.avatarUrl(context), AvatarFetcher.SIZE_CARD);
        if (TextUtils.isEmpty(url)) {
            avatar.setImageResource(R.drawable.ic_account_avatar);
            return;
        }
        Bitmap ready = AvatarFetcher.fromMemory(url);
        if (ready != null) {
            avatar.setImageDrawable(AvatarFetcher.circular(context.getResources(), ready));
            return;
        }
        avatar.setImageResource(R.drawable.ic_account_avatar);
        io.execute(() -> {
            Bitmap bitmap = AvatarFetcher.cached(context, url);
            if (bitmap == null) {
                return;
            }
            avatar.post(() -> avatar.setImageDrawable(AvatarFetcher.circular(context.getResources(), bitmap)));
        });
    }
}
