package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import wings.v.core.DisplayDensityUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.XrayProfile;
import wings.v.core.XrayProfileEditorCodec;
import wings.v.core.XrayProfileEditorText;
import wings.v.core.XrayStore;
import wings.v.databinding.ActivityXrayProfileEditorBinding;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings({ "PMD.CognitiveComplexity", "PMD.AvoidCatchingGenericException" })
public class XrayProfileEditorActivity extends AppCompatActivity {

    private static final String EXTRA_PROFILE_ID = "profile_id";
    private static final String EXTRA_MODE = "mode";
    private static final String MODE_JSON = "json";
    private static final String MODE_VLESS = "vless";

    private ActivityXrayProfileEditorBinding binding;
    private XrayProfile profile;
    private String currentMode = MODE_JSON;
    private boolean internalEditorUpdate;
    private boolean wrapLines = true;
    private int lastEditStart = -1;
    private int lastEditBefore;
    private int lastEditCount;
    private String lastInsertedText = "";
    private float lastTouchX;
    private float lastTouchY;

    public static Intent createIntent(Context context, String profileId, boolean startWithVless) {
        return new Intent(context, XrayProfileEditorActivity.class)
            .putExtra(EXTRA_PROFILE_ID, profileId)
            .putExtra(EXTRA_MODE, startWithVless ? MODE_VLESS : MODE_JSON);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityXrayProfileEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyWindowInsets();
        configureLineNumbersView();
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        profile = findProfile(getIntent().getStringExtra(EXTRA_PROFILE_ID));
        if (profile == null) {
            finish();
            return;
        }
        currentMode = MODE_VLESS.equals(getIntent().getStringExtra(EXTRA_MODE)) ? MODE_VLESS : MODE_JSON;
        binding.buttonEditorJson.setOnClickListener(v -> {
            Haptics.softSelection(v);
            switchMode(MODE_JSON);
        });
        binding.buttonEditorVless.setOnClickListener(v -> {
            Haptics.softSelection(v);
            switchMode(MODE_VLESS);
        });
        binding.buttonSaveProfileEditor.setOnClickListener(v -> {
            Haptics.softSelection(v);
            saveProfile();
        });
        binding.switchEditorWrapLines.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (wrapLines == isChecked) {
                return;
            }
            Haptics.softSliderStep(buttonView);
            wrapLines = isChecked;
            applyWrapMode();
        });
        binding.editorInput.addTextChangedListener(
            new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (internalEditorUpdate) {
                        return;
                    }
                    lastEditStart = start;
                    lastEditBefore = before;
                    lastEditCount = count;
                    lastInsertedText = count > 0 ? s.subSequence(start, start + count).toString() : "";
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (internalEditorUpdate) {
                        return;
                    }
                    applyStructuredEditorBehaviors(s);
                    updateLineNumbers();
                    syncEditorWidthForMode();
                    updateEditorState();
                }
            }
        );
        binding.editorInput.addOnLayoutChangeListener(
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                updateLineNumbers();
                syncEditorWidthForMode();
            }
        );
        binding.editorInput.setOnTouchListener(this::handleEditorTouch);
        binding.switchEditorWrapLines.setChecked(true);
        applyWrapMode();
        loadMode(currentMode);
    }

    private XrayProfile findProfile(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return null;
        }
        for (XrayProfile candidate : XrayStore.getProfiles(this)) {
            if (candidate != null && TextUtils.equals(profileId, candidate.id)) {
                return candidate;
            }
        }
        return null;
    }

    private void switchMode(String targetMode) {
        if (TextUtils.equals(currentMode, targetMode)) {
            return;
        }
        String currentText = textValue();
        try {
            if (MODE_JSON.equals(targetMode)) {
                String nextJson = MODE_VLESS.equals(currentMode)
                    ? XrayProfileEditorCodec.parseVlessProfile(profile, currentText).rawLink
                    : currentText;
                setEditorText(
                    XrayProfileEditorCodec.toEditableJson(
                        new XrayProfile(
                            profile.id,
                            profile.title,
                            nextJson,
                            profile.subscriptionId,
                            profile.subscriptionTitle,
                            profile.address,
                            profile.port
                        )
                    )
                );
            } else {
                String nextVless = MODE_JSON.equals(currentMode)
                    ? XrayProfileEditorCodec.toEditableVless(
                          XrayProfileEditorCodec.parseJsonProfile(profile, currentText)
                      )
                    : currentText.trim();
                setEditorText(nextVless);
            }
            currentMode = targetMode;
            updateModeButtons();
            updateEditorState();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadMode(String mode) {
        try {
            if (MODE_VLESS.equals(mode)) {
                setEditorText(XrayProfileEditorCodec.toEditableVless(profile));
                currentMode = MODE_VLESS;
            } else {
                setEditorText(XrayProfileEditorCodec.toEditableJson(profile));
                currentMode = MODE_JSON;
            }
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
            if (!MODE_JSON.equals(mode)) {
                try {
                    setEditorText(XrayProfileEditorCodec.toEditableJson(profile));
                    currentMode = MODE_JSON;
                } catch (Exception fallbackError) {
                    finish();
                    return;
                }
            } else {
                finish();
                return;
            }
        }
        updateModeButtons();
        updateLineNumbers();
        updateEditorState();
    }

    private void setEditorText(String value) {
        internalEditorUpdate = true;
        binding.editorInput.setText(value);
        binding.editorInput.setSelection(binding.editorInput.length());
        internalEditorUpdate = false;
        clearLastEditSnapshot();
        binding.textEditorLineNumbers.syncFromEditor();
        binding.editorInput.post(this::updateLineNumbers);
        updateEditorState();
    }

    private void updateModeButtons() {
        boolean jsonSelected = MODE_JSON.equals(currentMode);
        binding.buttonEditorJson.setSelected(jsonSelected);
        binding.buttonEditorVless.setSelected(!jsonSelected);
    }

    private void updateLineNumbers() {
        binding.textEditorLineNumbers.setWrapLines(wrapLines);
        binding.textEditorLineNumbers.syncFromEditor();
    }

    private void updateEditorState() {
        String value = textValue();
        boolean valid = false;
        String summary;
        if (MODE_JSON.equals(currentMode)) {
            valid = XrayProfileEditorText.isValidJson(value);
            summary = valid
                ? getString(R.string.xray_routing_badge_ready)
                : getString(R.string.xray_routing_badge_invalid);
            applyJsonHighlightingWithCursorRestore();
        } else {
            valid = isValidVless(value);
            summary = valid
                ? getString(R.string.xray_routing_badge_ready)
                : getString(R.string.xray_routing_badge_invalid);
            applyVlessHighlightingWithCursorRestore();
        }
        binding.textEditorStatus.setText(summary);
        binding.textEditorStatus.setBackgroundResource(
            valid ? R.drawable.bg_profile_ping_good : R.drawable.bg_profile_ping_bad
        );
        binding.buttonSaveProfileEditor.setEnabled(valid);
    }

    private void applyJsonHighlightingWithCursorRestore() {
        Editable editable = binding.editorInput.getText();
        if (editable == null) {
            return;
        }
        int selectionStart = binding.editorInput.getSelectionStart();
        int selectionEnd = binding.editorInput.getSelectionEnd();
        XrayProfileEditorText.applyJsonHighlighting(editable);
        restoreSelection(selectionStart, selectionEnd);
    }

    private void applyVlessHighlightingWithCursorRestore() {
        Editable editable = binding.editorInput.getText();
        if (editable == null) {
            return;
        }
        int selectionStart = binding.editorInput.getSelectionStart();
        int selectionEnd = binding.editorInput.getSelectionEnd();
        XrayProfileEditorText.applyVlessHighlighting(editable);
        restoreSelection(selectionStart, selectionEnd);
    }

    private void restoreSelection(int selectionStart, int selectionEnd) {
        int length = binding.editorInput.length();
        binding.editorInput.setSelection(
            Math.max(0, Math.min(selectionStart, length)),
            Math.max(0, Math.min(selectionEnd, length))
        );
    }

    private boolean isValidVless(String value) {
        try {
            return XrayProfileEditorCodec.parseVlessProfile(profile, value) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String textValue() {
        Editable editable = binding.editorInput.getText();
        return editable == null ? "" : editable.toString();
    }

    private void saveProfile() {
        try {
            XrayProfile updatedProfile = MODE_JSON.equals(currentMode)
                ? XrayProfileEditorCodec.parseJsonProfile(profile, textValue())
                : XrayProfileEditorCodec.parseVlessProfile(profile, textValue());
            if (!XrayStore.replaceProfile(this, updatedProfile)) {
                throw new IllegalStateException("Профиль не найден");
            }
            profile = updatedProfile;
            if (TextUtils.equals(XrayStore.getActiveProfileId(this), updatedProfile.id)) {
                BackendType backendType = XrayStore.getBackendType(this);
                if (backendType != null && backendType.usesXrayCore() && ProxyTunnelService.isActive()) {
                    ProxyTunnelService.requestReconnect(getApplicationContext(), "Xray profile edited");
                }
            }
            Toast.makeText(this, R.string.xray_profile_editor_saved, Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception error) {
            Toast.makeText(
                this,
                getString(
                    R.string.xray_profile_editor_save_failed,
                    firstNonEmpty(error.getMessage(), "Invalid profile")
                ),
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    private String firstNonEmpty(String primary, String fallback) {
        return TextUtils.isEmpty(primary) ? fallback : primary;
    }

    private void applyWrapMode() {
        LinearLayout.LayoutParams editorParams = (LinearLayout.LayoutParams) binding.editorInput.getLayoutParams();
        ViewGroup.LayoutParams rowParams = binding.editorContentRow.getLayoutParams();
        binding.editorHorizontalScroll.setHorizontalScrollBarEnabled(!wrapLines);
        binding.textEditorLineNumbers.setWrapLines(wrapLines);
        if (wrapLines) {
            editorParams.width = 0;
            editorParams.weight = 1f;
            binding.editorInput.setMinWidth(0);
        } else {
            editorParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            editorParams.weight = 0f;
        }
        binding.editorContentRow.setLayoutParams(rowParams);
        binding.editorInput.setLayoutParams(editorParams);
        binding.editorInput.setHorizontallyScrolling(!wrapLines);
        binding.editorInput.setHorizontalScrollBarEnabled(!wrapLines);
        binding.editorInput.setVerticalScrollBarEnabled(false);
        binding.editorInput.setSingleLine(false);
        binding.editorInput.setMaxLines(Integer.MAX_VALUE);
        binding.editorHorizontalScroll.scrollTo(0, 0);
        binding.editorInput.scrollTo(0, 0);
        binding.editorInput.requestLayout();
        syncEditorWidthForMode();
        binding.editorInput.post(this::updateLineNumbers);
    }

    private void ensureEditorMinWidthForOverflow() {
        int viewportWidth = binding.editorHorizontalScroll.getWidth();
        int rowPadding = binding.editorContentRow.getPaddingLeft() + binding.editorContentRow.getPaddingRight();
        int minWidth = Math.max(0, viewportWidth - rowPadding);
        binding.editorInput.setMinWidth(minWidth);
    }

    private void syncEditorWidthForMode() {
        binding.editorHorizontalScroll.post(() -> {
            int viewportWidth = binding.editorHorizontalScroll.getWidth();
            int availableEditorWidth = calculateAvailableEditorWidth(viewportWidth);
            if (availableEditorWidth <= 0) {
                return;
            }
            ensureEditorMinWidthForOverflow();
            ViewGroup.LayoutParams rowParams = binding.editorContentRow.getLayoutParams();
            LinearLayout.LayoutParams editorParams = (LinearLayout.LayoutParams) binding.editorInput.getLayoutParams();
            if (wrapLines) {
                rowParams.width = calculateWrappedRowWidth(viewportWidth);
                editorParams.width = availableEditorWidth;
                editorParams.weight = 0f;
            } else {
                rowParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                editorParams.width = Math.max(binding.editorInput.getMinWidth(), measureLongestLineWidth());
                editorParams.weight = 0f;
            }
            binding.editorContentRow.setLayoutParams(rowParams);
            binding.editorInput.setLayoutParams(editorParams);
            binding.editorInput.requestLayout();
        });
    }

    private int measureLongestLineWidth() {
        String[] lines = textValue().split("\n", -1);
        TextPaint paint = binding.editorInput.getPaint();
        float maxWidth = 0f;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, paint.measureText(line));
        }
        float cursorPadding =
            binding.editorInput.getCompoundPaddingLeft() + binding.editorInput.getCompoundPaddingRight() + 24f;
        return (int) Math.ceil(maxWidth + cursorPadding);
    }

    private int calculateWrappedRowWidth(int viewportWidth) {
        int availableEditorWidth = calculateAvailableEditorWidth(viewportWidth);
        int rowPadding = binding.editorContentRow.getPaddingLeft() + binding.editorContentRow.getPaddingRight();
        return Math.max(0, availableEditorWidth + rowPadding);
    }

    private int calculateAvailableEditorWidth(int viewportWidth) {
        int resolvedViewportWidth = viewportWidth > 0 ? viewportWidth : getScreenWidth();
        int rowPadding = binding.editorContentRow.getPaddingLeft() + binding.editorContentRow.getPaddingRight();
        return Math.max(0, resolvedViewportWidth - rowPadding);
    }

    private int getScreenWidth() {
        return DisplayDensityUtils.getScreenWidth(this);
    }

    private void configureLineNumbersView() {
        binding.textEditorLineNumbers.attachEditor(binding.editorInput);
        binding.textEditorLineNumbers.setWrapLines(wrapLines);
    }

    private boolean handleEditorTouch(View view, MotionEvent event) {
        if (event == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                binding.editorScroll.requestDisallowInterceptTouchEvent(!wrapLines);
                break;
            case MotionEvent.ACTION_MOVE:
                if (wrapLines) {
                    binding.editorScroll.requestDisallowInterceptTouchEvent(false);
                    break;
                }
                float deltaX = Math.abs(event.getX() - lastTouchX);
                float deltaY = Math.abs(event.getY() - lastTouchY);
                binding.editorScroll.requestDisallowInterceptTouchEvent(deltaX > deltaY);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                binding.editorScroll.requestDisallowInterceptTouchEvent(false);
                break;
            default:
                break;
        }
        return false;
    }

    private void applyStructuredEditorBehaviors(Editable editable) {
        if (!MODE_JSON.equals(currentMode) || editable == null) {
            clearLastEditSnapshot();
            return;
        }
        if (lastEditStart < 0 || lastEditBefore != 0 || lastEditCount != 1 || lastInsertedText.length() != 1) {
            clearLastEditSnapshot();
            return;
        }
        char insertedChar = lastInsertedText.charAt(0);
        boolean handled = false;
        if (insertedChar == '\n') {
            handled = handleNewLineIndentation(editable);
        } else if (insertedChar == '"') {
            handled = handleClosingSkip(editable, insertedChar);
            if (!handled) {
                handled = handleAutoPair(editable, insertedChar);
            }
        } else if (insertedChar == '{' || insertedChar == '[' || insertedChar == '(') {
            handled = handleAutoPair(editable, insertedChar);
        } else if (insertedChar == '}' || insertedChar == ']' || insertedChar == ')') {
            handled = handleClosingSkip(editable, insertedChar);
        }
        if (!handled) {
            clearLastEditSnapshot();
        }
    }

    private boolean handleAutoPair(Editable editable, char insertedChar) {
        int cursor = binding.editorInput.getSelectionStart();
        if (cursor <= 0) {
            return false;
        }
        char closingChar = matchingClosingChar(insertedChar);
        if (closingChar == 0) {
            return false;
        }
        if (insertedChar == '"' && shouldSkipQuoteAutoPair(editable, cursor)) {
            clearLastEditSnapshot();
            return false;
        }
        internalEditorUpdate = true;
        editable.insert(cursor, String.valueOf(closingChar));
        binding.editorInput.setSelection(cursor);
        internalEditorUpdate = false;
        clearLastEditSnapshot();
        return true;
    }

    private boolean handleClosingSkip(Editable editable, char insertedChar) {
        int cursor = binding.editorInput.getSelectionStart();
        if (cursor <= 0 || cursor >= editable.length()) {
            return false;
        }
        if (editable.charAt(cursor) != insertedChar) {
            return false;
        }
        internalEditorUpdate = true;
        editable.delete(cursor - 1, cursor);
        binding.editorInput.setSelection(cursor);
        internalEditorUpdate = false;
        clearLastEditSnapshot();
        return true;
    }

    private boolean handleNewLineIndentation(Editable editable) {
        int cursor = binding.editorInput.getSelectionStart();
        if (cursor <= 0) {
            return false;
        }
        int previousLineStart = findLineStart(editable, cursor - 2);
        String previousIndent = extractLeadingIndent(editable, previousLineStart);
        char previousSignificant = findPreviousSignificantChar(editable, cursor - 2);
        char nextSignificant = findNextSignificantChar(editable, cursor);
        boolean shouldIncreaseIndent =
            previousSignificant == '{' || previousSignificant == '[' || previousSignificant == '(';
        boolean nextIsClosing = nextSignificant == '}' || nextSignificant == ']' || nextSignificant == ')';
        String indent = previousIndent + (shouldIncreaseIndent ? "  " : "");
        internalEditorUpdate = true;
        editable.insert(cursor, indent);
        int targetCursor = cursor + indent.length();
        if (shouldIncreaseIndent && nextIsClosing) {
            editable.insert(cursor + indent.length(), "\n" + previousIndent);
            binding.editorInput.setSelection(targetCursor);
        } else {
            binding.editorInput.setSelection(targetCursor);
        }
        internalEditorUpdate = false;
        clearLastEditSnapshot();
        return true;
    }

    private boolean shouldSkipQuoteAutoPair(Editable editable, int cursor) {
        int previousIndex = cursor - 2;
        if (previousIndex >= 0 && editable.charAt(previousIndex) == '\\') {
            return true;
        }
        return cursor < editable.length() && editable.charAt(cursor) == '"';
    }

    private char matchingClosingChar(char openingChar) {
        if (openingChar == '{') {
            return '}';
        }
        if (openingChar == '[') {
            return ']';
        }
        if (openingChar == '(') {
            return ')';
        }
        if (openingChar == '"') {
            return '"';
        }
        return 0;
    }

    private int findLineStart(CharSequence text, int index) {
        int cursor = Math.max(0, index);
        while (cursor > 0 && text.charAt(cursor - 1) != '\n') {
            cursor--;
        }
        return cursor;
    }

    private String extractLeadingIndent(CharSequence text, int lineStart) {
        StringBuilder builder = new StringBuilder();
        int index = Math.max(0, lineStart);
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current != ' ' && current != '\t') {
                break;
            }
            builder.append(current);
            index++;
        }
        return builder.toString();
    }

    private char findPreviousSignificantChar(CharSequence text, int index) {
        int cursor = Math.min(index, text.length() - 1);
        while (cursor >= 0) {
            char current = text.charAt(cursor);
            if (!Character.isWhitespace(current)) {
                return current;
            }
            cursor--;
        }
        return 0;
    }

    private char findNextSignificantChar(CharSequence text, int index) {
        int cursor = Math.max(0, index);
        while (cursor < text.length()) {
            char current = text.charAt(cursor);
            if (!Character.isWhitespace(current)) {
                return current;
            }
            cursor++;
        }
        return 0;
    }

    private void clearLastEditSnapshot() {
        lastEditStart = -1;
        lastEditBefore = 0;
        lastEditCount = 0;
        lastInsertedText = "";
    }

    private void applyWindowInsets() {
        final int baseScrollLeft = binding.editorScroll.getPaddingLeft();
        final int baseScrollTop = binding.editorScroll.getPaddingTop();
        final int baseScrollRight = binding.editorScroll.getPaddingRight();
        final int baseScrollBottom = binding.editorScroll.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottomInset = Math.max(bars.bottom, ime.bottom);
            binding.editorScroll.setPadding(
                baseScrollLeft,
                baseScrollTop,
                baseScrollRight,
                baseScrollBottom + bottomInset
            );
            return insets;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());
    }
}
