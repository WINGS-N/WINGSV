package wings.v;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import wings.v.core.Haptics;
import wings.v.databinding.FragmentFirstLaunchDoneBinding;

@SuppressWarnings("PMD.NullAssignment")
public class FirstLaunchDoneFragment extends Fragment {

    @FunctionalInterface
    public interface Host {
        void onFirstLaunchDone();
    }

    @Nullable
    private FragmentFirstLaunchDoneBinding binding;

    public static FirstLaunchDoneFragment create() {
        return new FirstLaunchDoneFragment();
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFirstLaunchDoneBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.buttonFirstLaunchDone.setOnClickListener(button -> {
            Haptics.softConfirm(button);
            if (getActivity() instanceof Host) {
                ((Host) getActivity()).onFirstLaunchDone();
            }
        });
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
