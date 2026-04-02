package wings.v;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import dev.oneuiproject.oneui.widget.CardItemView;
import wings.v.core.AvatarDrawableFactory;
import wings.v.core.BrowserLauncher;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityOpenSourceLicensesBinding;

public class OpenSourceLicensesActivity extends AppCompatActivity {
    private ActivityOpenSourceLicensesBinding binding;

    public static Intent createIntent(Context context) {
        return new Intent(context, OpenSourceLicensesActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityOpenSourceLicensesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        bindCards();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    private void bindCards() {
        configureComponentCard(
                binding.cardLicenseOneuiDesign,
                "OD",
                Color.parseColor("#177C66"),
                "https://github.com/tribalfs/oneui-design"
        );
        configureComponentCard(
                binding.cardLicenseSeslAndroidx,
                "SX",
                Color.parseColor("#2A73D9"),
                "https://github.com/tribalfs/sesl-androidx"
        );
        configureComponentCard(
                binding.cardLicenseSeslMaterial,
                "SM",
                Color.parseColor("#C66A1D"),
                "https://github.com/tribalfs/sesl-material-components-android"
        );
        configureComponentCard(
                binding.cardLicenseWireguard,
                "WG",
                Color.parseColor("#596574"),
                "https://git.zx2c4.com/wireguard-android/"
        );
        configureComponentCard(
                binding.cardLicenseVkTurnProxy,
                "VK",
                Color.parseColor("#5C61D3"),
                "https://github.com/WINGS-N/vk-turn-proxy"
        );
        configureComponentCard(
                binding.cardLicenseVpnHotspot,
                "VH",
                Color.parseColor("#3E8F6A"),
                "https://github.com/WINGS-N/VPNHotspot"
        );
        configureComponentCard(
                binding.cardLicenseLibrootkotlinx,
                "LR",
                Color.parseColor("#6B5FD1"),
                "https://github.com/Mygod/librootkotlinx"
        );
        configureComponentCard(
                binding.cardLicenseHiddenApiBypass,
                "HA",
                Color.parseColor("#A76425"),
                "https://github.com/LSPosed/AndroidHiddenApiBypass"
        );
        configureComponentCard(
                binding.cardLicenseDnsjava,
                "DJ",
                Color.parseColor("#4F748D"),
                "https://github.com/dnsjava/dnsjava"
        );
        configureComponentCard(
                binding.cardLicenseKtorNetwork,
                "KN",
                Color.parseColor("#1E8D8B"),
                "https://github.com/ktorio/ktor"
        );
        configureComponentCard(
                binding.cardLicenseTimber,
                "TM",
                Color.parseColor("#7A6B31"),
                "https://github.com/JakeWharton/timber"
        );
    }

    private void configureComponentCard(CardItemView cardItemView,
                                        String initials,
                                        int backgroundColor,
                                        String url) {
        cardItemView.setIcon(AvatarDrawableFactory.create(this, initials, backgroundColor));
        cardItemView.setOnClickListener(view -> {
            Haptics.softSelection(view);
            BrowserLauncher.open(this, url);
        });
    }
}
