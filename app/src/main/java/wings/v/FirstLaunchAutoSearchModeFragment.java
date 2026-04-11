package wings.v;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import wings.v.core.AutoSearchManager;
import wings.v.core.Haptics;
import wings.v.databinding.FragmentFirstLaunchAutoSearchModeBinding;

@SuppressWarnings("PMD.NullAssignment")
public class FirstLaunchAutoSearchModeFragment extends Fragment {

    @FunctionalInterface
    public interface Host {
        void onFirstLaunchAutoSearchModeSelected(@NonNull AutoSearchManager.Mode mode);
    }

    @Nullable
    private FragmentFirstLaunchAutoSearchModeBinding binding;

    public static FirstLaunchAutoSearchModeFragment create() {
        return new FirstLaunchAutoSearchModeFragment();
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFirstLaunchAutoSearchModeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.buttonAutoSearchStandard.setOnClickListener(button ->
            dispatch(button, AutoSearchManager.Mode.STANDARD)
        );
        binding.buttonAutoSearchWhitelist.setOnClickListener(button ->
            dispatch(button, AutoSearchManager.Mode.WHITELIST)
        );
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            binding.imageAutoSearchMode
                .animate()
                .scaleX(1.06f)
                .scaleY(1.06f)
                .alpha(0.82f)
                .setDuration(1200L)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction(() -> {
                    if (binding != null) {
                        binding.imageAutoSearchMode
                            .animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(0.94f)
                            .setDuration(1200L)
                            .start();
                    }
                })
                .start();
        }
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private void dispatch(View view, AutoSearchManager.Mode mode) {
        Haptics.softConfirm(view);
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).onFirstLaunchAutoSearchModeSelected(mode);
        }
    }
}
