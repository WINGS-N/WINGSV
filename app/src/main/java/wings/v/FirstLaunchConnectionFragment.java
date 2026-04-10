package wings.v;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import wings.v.core.Haptics;
import wings.v.databinding.FragmentFirstLaunchConnectionBinding;

@SuppressWarnings("PMD.NullAssignment")
public class FirstLaunchConnectionFragment extends Fragment {

    public static final String CHOICE_VK_TURN = "vk_turn";
    public static final String CHOICE_XRAY = "xray";
    public static final String CHOICE_AUTO_SEARCH = "auto_search";

    @Nullable
    private FragmentFirstLaunchConnectionBinding binding;

    public interface Host {
        void onConnectionChoiceSelected(@NonNull String choice);

        void onConnectionChoiceSkipped();
    }

    public static FirstLaunchConnectionFragment create() {
        return new FirstLaunchConnectionFragment();
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFirstLaunchConnectionBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.buttonFirstLaunchVkTurn.setOnClickListener(v -> dispatchChoice(v, CHOICE_VK_TURN));
        binding.buttonFirstLaunchXray.setOnClickListener(v -> dispatchChoice(v, CHOICE_XRAY));
        binding.buttonFirstLaunchAutoSearch.setOnClickListener(v -> dispatchChoice(v, CHOICE_AUTO_SEARCH));
        binding.textFirstLaunchSkip.setOnClickListener(v -> dispatchSkip(v));
    }

    private void dispatchChoice(View view, String choice) {
        Haptics.softConfirm(view);
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).onConnectionChoiceSelected(choice);
        }
    }

    private void dispatchSkip(View view) {
        Haptics.softSelection(view);
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).onConnectionChoiceSkipped();
        }
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
