package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import dev.oneuiproject.oneui.layout.ToolbarLayout;
import wings.v.ui.AccountFragment;

/**
 * Оболочка вокруг аккаунта для тех, кто приходит сюда ссылкой.
 *
 * <p>Сам экран живёт фрагментом и стоит своим табом в главном окне. Активити
 * нужна ради возврата из браузера: одноразовый код после входа через Matrix
 * прилетает интентом, а у фрагмента интента нет
 */
public class FederationAccountActivity extends AppCompatActivity {

    private static final String TAG_ACCOUNT = "wings-account";

    /** Required empty constructor. */
    public FederationAccountActivity() {
        super();
    }

    public static Intent createIntent(@NonNull Context context) {
        return new Intent(context, FederationAccountActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wings_account);
        ToolbarLayout toolbar = findViewById(R.id.toolbar_layout);
        toolbar.setShowNavigationButtonAsBack(true);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.wings_account_container, new AccountFragment(), TAG_ACCOUNT)
                .commit();
        }
        deliverCode(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        deliverCode(intent);
    }

    /** Отдаёт код фрагменту, когда тот уже приделан к экрану */
    private void deliverCode(@Nullable Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        getSupportFragmentManager()
            .beginTransaction()
            .runOnCommit(() -> {
                AccountFragment fragment = (AccountFragment) getSupportFragmentManager().findFragmentByTag(TAG_ACCOUNT);
                if (fragment != null) {
                    fragment.acceptCode(intent);
                }
            })
            .commit();
    }
}
