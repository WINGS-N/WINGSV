package wings.v;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import wings.v.core.Haptics;
import wings.v.databinding.FragmentFirstLaunchIntroBinding;

@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
    }
)
public class FirstLaunchIntroFragment extends Fragment {

    private static final String ARG_TITLE_RES = "title_res";
    private static final String ARG_SUBTITLE_RES = "subtitle_res";
    private static final String ARG_BUTTON_RES = "button_res";

    @Nullable
    private FragmentFirstLaunchIntroBinding binding;

    public interface Host {
        void onAdvanceIntroPage();
    }

    public static FirstLaunchIntroFragment create(int titleRes, int subtitleRes, int buttonRes) {
        Bundle args = new Bundle();
        args.putInt(ARG_TITLE_RES, titleRes);
        args.putInt(ARG_SUBTITLE_RES, subtitleRes);
        args.putInt(ARG_BUTTON_RES, buttonRes);

        FirstLaunchIntroFragment fragment = new FirstLaunchIntroFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentFirstLaunchIntroBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = requireArguments();
        binding.textFirstLaunchTitle.setText(args.getInt(ARG_TITLE_RES));
        CharSequence title = binding.textFirstLaunchTitle.getText();
        if (title != null && title.length() > 48) {
            binding.textFirstLaunchTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
            binding.textFirstLaunchTitle.setLineSpacing(0f, 1.16f);
            binding.textFirstLaunchTitle.setMaxLines(8);
        } else {
            binding.textFirstLaunchTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 42f);
            binding.textFirstLaunchTitle.setLineSpacing(0f, 1.0f);
            binding.textFirstLaunchTitle.setMaxLines(3);
        }
        int subtitleRes = args.getInt(ARG_SUBTITLE_RES);
        if (subtitleRes != 0) {
            binding.textFirstLaunchSubtitle.setVisibility(View.VISIBLE);
            binding.textFirstLaunchSubtitle.setText(subtitleRes);
        } else {
            binding.textFirstLaunchSubtitle.setVisibility(View.GONE);
        }
        binding.buttonFirstLaunchAction.setText(args.getInt(ARG_BUTTON_RES));
        binding.buttonFirstLaunchAction.setOnClickListener(v -> {
            Haptics.softConfirm(v);
            if (getActivity() instanceof Host) {
                ((Host) getActivity()).onAdvanceIntroPage();
            }
        });
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
