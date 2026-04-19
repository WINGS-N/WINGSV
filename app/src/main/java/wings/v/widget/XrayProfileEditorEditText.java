package wings.v.widget;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatEditText;

public class XrayProfileEditorEditText extends AppCompatEditText {

    public XrayProfileEditorEditText(Context context) {
        super(context);
    }

    public XrayProfileEditorEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public XrayProfileEditorEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean requestRectangleOnScreen(Rect rectangle) {
        return false;
    }
}
