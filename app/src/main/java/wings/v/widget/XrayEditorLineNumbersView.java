package wings.v.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;
import wings.v.core.DisplayDensityUtils;

public class XrayEditorLineNumbersView extends View {

    private static final int DEFAULT_MIN_WIDTH_DP = 32;
    private static final int DEFAULT_GAP_DP = 10;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private AppCompatEditText editor;
    private boolean wrapLines = true;
    private int minWidthPx;
    private int gapPx;

    public XrayEditorLineNumbersView(Context context) {
        super(context);
        init();
    }

    public XrayEditorLineNumbersView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public XrayEditorLineNumbersView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        minWidthPx = DisplayDensityUtils.dpToPx(getContext(), DEFAULT_MIN_WIDTH_DP);
        gapPx = DisplayDensityUtils.dpToPx(getContext(), DEFAULT_GAP_DP);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        setWillNotDraw(false);
    }

    public void attachEditor(AppCompatEditText value) {
        editor = value;
        syncFromEditor();
    }

    public void setWrapLines(boolean value) {
        wrapLines = value;
        invalidate();
    }

    public void syncFromEditor() {
        if (editor == null) {
            return;
        }
        textPaint.setColor(
            editor.getCurrentHintTextColor() != 0 ? editor.getCurrentHintTextColor() : editor.getCurrentTextColor()
        );
        textPaint.setTextSize(editor.getTextSize());
        textPaint.setTypeface(editor.getTypeface());
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = computeDesiredWidth();
        int resolvedWidth = resolveSize(desiredWidth, widthMeasureSpec);
        int desiredHeight = editor != null ? editor.getMeasuredHeight() : getSuggestedMinimumHeight();
        int resolvedHeight = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(resolvedWidth, resolvedHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editor == null) {
            return;
        }
        Layout layout = editor.getLayout();
        if (layout == null) {
            return;
        }
        CharSequence text = editor.getText();
        int previousSourceLine = -1;
        float x = getWidth() - gapPx;
        int baselineOffset = editor.getExtendedPaddingTop();
        int visualLineCount = Math.max(1, layout.getLineCount());
        for (int visualLine = 0; visualLine < visualLineCount; visualLine++) {
            int lineStart = layout.getLineStart(visualLine);
            int sourceLine = 1 + countNewlines(text, lineStart);
            if (wrapLines && sourceLine == previousSourceLine) {
                previousSourceLine = sourceLine;
                continue;
            }
            float baseline = baselineOffset + layout.getLineBaseline(visualLine);
            canvas.drawText(String.valueOf(sourceLine), x, baseline, textPaint);
            previousSourceLine = sourceLine;
        }
    }

    private int computeDesiredWidth() {
        int maxLineNumber = 1;
        if (editor != null) {
            CharSequence text = editor.getText();
            maxLineNumber = Math.max(1, 1 + countNewlines(text, text != null ? text.length() : 0));
        }
        return Math.max(minWidthPx, (int) Math.ceil(textPaint.measureText(String.valueOf(maxLineNumber))) + gapPx);
    }

    private int countNewlines(CharSequence text, int endExclusive) {
        if (TextUtils.isEmpty(text) || endExclusive <= 0) {
            return 0;
        }
        int safeEnd = Math.min(endExclusive, text.length());
        int count = 0;
        for (int index = 0; index < safeEnd; index++) {
            if (text.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }
}
