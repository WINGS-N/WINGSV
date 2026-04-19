package wings.v.widget;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.widget.VideoView;
import androidx.annotation.Nullable;

public class AspectFillVideoView extends VideoView {

    private int videoWidth;
    private int videoHeight;

    public AspectFillVideoView(Context context) {
        super(context);
    }

    public AspectFillVideoView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectFillVideoView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setVideoSize(MediaPlayer player) {
        videoWidth = player.getVideoWidth();
        videoHeight = player.getVideoHeight();
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
        if (parentWidth <= 0 || parentHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            setMeasuredDimension(parentWidth, parentHeight);
            return;
        }

        float parentRatio = parentWidth / (float) parentHeight;
        float videoRatio = videoWidth / (float) videoHeight;
        int measuredWidth = parentWidth;
        int measuredHeight = parentHeight;
        if (videoRatio > parentRatio) {
            measuredWidth = (int) (parentHeight * videoRatio);
        } else {
            measuredHeight = (int) (parentWidth / videoRatio);
        }
        setMeasuredDimension(measuredWidth, measuredHeight);
    }
}
