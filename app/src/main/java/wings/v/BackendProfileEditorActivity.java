package wings.v;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
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
import wings.v.core.AmneziaProfile;
import wings.v.core.AmneziaProfileEditorCodec;
import wings.v.core.AmneziaProfileStore;
import wings.v.core.BackendType;
import wings.v.core.Haptics;
import wings.v.core.WgQuickEditorText;
import wings.v.core.WireGuardProfile;
import wings.v.core.WireGuardProfileEditorCodec;
import wings.v.core.WireGuardProfileStore;
import wings.v.core.XrayStore;
import wings.v.databinding.ActivityBackendProfileEditorBinding;
import wings.v.service.ProxyTunnelService;

/**
 * wg-quick / awg-quick TEXT editor for a WireGuard or AmneziaWG profile,
 * mirroring XrayProfileEditorActivity. Loads the profile's raw quick-config,
 * lets the user edit it with INI highlighting, validates on save via the
 * matching codec (WireGuardProfileEditorCodec / AmneziaProfileEditorCodec) and
 * writes back through the store's replaceProfile keeping the id (and therefore
 * stats and favorite). An "open UI editor" button makes the profile active,
 * projects it onto the flat keys, opens the per-backend settings screen and on
 * return folds the flat-key edits back into the active profile.
 */
@SuppressWarnings(
    { "PMD.CognitiveComplexity", "PMD.AvoidCatchingGenericException", "PMD.GodClass", "PMD.TooManyMethods" }
)
public class BackendProfileEditorActivity extends AppCompatActivity {

    private static final String EXTRA_PROFILE_ID = "profile_id";
    private static final String EXTRA_KIND = "kind";
    private static final String KIND_WG = "wg";
    private static final String KIND_AWG = "awg";

    private ActivityBackendProfileEditorBinding binding;
    private String kind = KIND_WG;
    private String profileId = "";
    private boolean internalEditorUpdate;
    private boolean wrapLines = true;
    private boolean returningFromUiEditor;
    private float lastTouchX;
    private float lastTouchY;

    public static Intent createIntent(Context context, BackendType backendType, String profileId) {
        String resolvedKind = backendType != null && backendType.usesAmneziaSettings() ? KIND_AWG : KIND_WG;
        return new Intent(context, BackendProfileEditorActivity.class)
            .putExtra(EXTRA_PROFILE_ID, profileId)
            .putExtra(EXTRA_KIND, resolvedKind);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBackendProfileEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyWindowInsets();
        binding.textEditorLineNumbers.attachEditor(binding.editorInput);
        binding.textEditorLineNumbers.setWrapLines(wrapLines);
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        profileId = stringExtra(EXTRA_PROFILE_ID);
        kind = KIND_AWG.equals(stringExtra(EXTRA_KIND)) ? KIND_AWG : KIND_WG;
        if (TextUtils.isEmpty(profileId) || loadInitialText() == null) {
            finish();
            return;
        }
        binding.textEditorModeLabel.setText(
            KIND_AWG.equals(kind)
                ? R.string.backend_profile_editor_mode_awg_quick
                : R.string.backend_profile_editor_mode_quick
        );
        binding.buttonSaveProfileEditor.setOnClickListener(v -> {
            Haptics.softSelection(v);
            saveProfile();
        });
        binding.buttonOpenUiEditor.setOnClickListener(v -> {
            Haptics.softSelection(v);
            openUiEditor();
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
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    if (internalEditorUpdate) {
                        return;
                    }
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
        setEditorText(loadInitialText());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding == null || !returningFromUiEditor) {
            return;
        }
        returningFromUiEditor = false;
        // Returning from the UI editor: fold the active profile's flat-key edits
        // back into the stored profile, then reload the text so the editor
        // reflects those edits. The UI editor made this profile active before
        // opening, so this profile is the active one of its backend.
        if (KIND_AWG.equals(kind)) {
            if (TextUtils.equals(AmneziaProfileStore.getActiveProfileId(this), profileId)) {
                AmneziaProfileStore.updateActiveFromFlatPrefs(this);
            }
        } else if (TextUtils.equals(WireGuardProfileStore.getActiveProfileId(this), profileId)) {
            WireGuardProfileStore.updateActiveFromFlatPrefs(this);
        }
        setEditorText(loadInitialText());
    }

    @Nullable
    private String loadInitialText() {
        if (KIND_AWG.equals(kind)) {
            AmneziaProfile profile = AmneziaProfileStore.getProfileById(this, profileId);
            return profile == null ? null : AmneziaProfileEditorCodec.toEditableQuickConfig(profile);
        }
        WireGuardProfile profile = WireGuardProfileStore.getProfileById(this, profileId);
        return profile == null ? null : WireGuardProfileEditorCodec.toEditableQuickConfig(profile);
    }

    private void saveProfile() {
        try {
            String text = textValue();
            boolean isActive;
            BackendType backendType;
            if (KIND_AWG.equals(kind)) {
                AmneziaProfile base = AmneziaProfileStore.getProfileById(this, profileId);
                AmneziaProfile updated = AmneziaProfileEditorCodec.parseQuickConfig(base, text);
                if (!AmneziaProfileStore.replaceProfile(this, updated)) {
                    throw new IllegalStateException(getString(R.string.xray_profile_editor_profile_missing));
                }
                backendType = XrayStore.getBackendType(this);
                isActive = TextUtils.equals(AmneziaProfileStore.getActiveProfileId(this), updated.id);
                if (isActive) {
                    AmneziaProfileStore.applyActiveToPrefs(this);
                }
            } else {
                WireGuardProfile base = WireGuardProfileStore.getProfileById(this, profileId);
                WireGuardProfile updated = WireGuardProfileEditorCodec.parseQuickConfig(base, text);
                if (!WireGuardProfileStore.replaceProfile(this, updated)) {
                    throw new IllegalStateException(getString(R.string.xray_profile_editor_profile_missing));
                }
                backendType = XrayStore.getBackendType(this);
                isActive = TextUtils.equals(WireGuardProfileStore.getActiveProfileId(this), updated.id);
                if (isActive) {
                    WireGuardProfileStore.applyActiveToPrefs(this);
                }
            }
            maybeReconnect(isActive, backendType);
            Toast.makeText(this, R.string.xray_profile_editor_saved, Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception error) {
            Toast.makeText(
                this,
                getString(
                    R.string.xray_profile_editor_save_failed,
                    firstNonEmpty(error.getMessage(), "Invalid config")
                ),
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    // Folds the live text into the profile (best effort, ignoring parse errors),
    // makes it active and opens the per-backend settings screen so the user can
    // edit the flat fields. The settings screen edits the flat keys; onResume
    // reloads this editor from the (folded back) profile.
    private void openUiEditor() {
        String text = textValue();
        try {
            if (KIND_AWG.equals(kind)) {
                AmneziaProfile base = AmneziaProfileStore.getProfileById(this, profileId);
                AmneziaProfile updated = AmneziaProfileEditorCodec.parseQuickConfig(base, text);
                AmneziaProfileStore.replaceProfile(this, updated);
                AmneziaProfileStore.setActiveProfileId(this, updated.id);
                XrayStore.setBackendType(this, BackendType.AMNEZIAWG_PLAIN);
                AmneziaProfileStore.applyActiveToPrefs(this);
            } else {
                WireGuardProfile base = WireGuardProfileStore.getProfileById(this, profileId);
                WireGuardProfile updated = WireGuardProfileEditorCodec.parseQuickConfig(base, text);
                WireGuardProfileStore.replaceProfile(this, updated);
                WireGuardProfileStore.setActiveProfileId(this, updated.id);
                XrayStore.setBackendType(this, BackendType.WIREGUARD);
                WireGuardProfileStore.applyActiveToPrefs(this);
            }
        } catch (Exception ignored) {
            // Open the UI editor on the stored profile when the live text is not
            // yet valid; the user can fix it through the structured fields.
            if (KIND_AWG.equals(kind)) {
                AmneziaProfileStore.setActiveProfileId(this, profileId);
                XrayStore.setBackendType(this, BackendType.AMNEZIAWG_PLAIN);
                AmneziaProfileStore.applyActiveToPrefs(this);
            } else {
                WireGuardProfileStore.setActiveProfileId(this, profileId);
                XrayStore.setBackendType(this, BackendType.WIREGUARD);
                WireGuardProfileStore.applyActiveToPrefs(this);
            }
        }
        returningFromUiEditor = true;
        startActivity(VkTurnSettingsActivity.createIntent(this));
    }

    private void maybeReconnect(boolean isActive, BackendType backendType) {
        if (!isActive || !ProxyTunnelService.isActive()) {
            return;
        }
        boolean kindMatches = KIND_AWG.equals(kind)
            ? backendType != null && backendType.usesAmneziaSettings()
            : backendType != null && backendType.usesWireGuardSettings();
        if (kindMatches) {
            ProxyTunnelService.requestReconnect(getApplicationContext(), "Backend profile edited");
        }
    }

    private void updateEditorState() {
        String value = textValue();
        boolean valid = isValid(value);
        binding.textEditorStatus.setText(
            valid ? getString(R.string.xray_routing_badge_ready) : getString(R.string.xray_routing_badge_invalid)
        );
        binding.textEditorStatus.setBackgroundResource(
            valid ? R.drawable.bg_profile_ping_good : R.drawable.bg_profile_ping_bad
        );
        binding.buttonSaveProfileEditor.setEnabled(valid);
        applyHighlightingWithCursorRestore();
    }

    private boolean isValid(String value) {
        try {
            if (KIND_AWG.equals(kind)) {
                return AmneziaProfileEditorCodec.parseQuickConfig(null, value) != null;
            }
            return WireGuardProfileEditorCodec.parseQuickConfig(null, value) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void applyHighlightingWithCursorRestore() {
        Editable editable = binding.editorInput.getText();
        if (editable == null) {
            return;
        }
        int selectionStart = binding.editorInput.getSelectionStart();
        int selectionEnd = binding.editorInput.getSelectionEnd();
        WgQuickEditorText.applyHighlighting(editable);
        int length = binding.editorInput.length();
        binding.editorInput.setSelection(
            Math.max(0, Math.min(selectionStart, length)),
            Math.max(0, Math.min(selectionEnd, length))
        );
    }

    private String textValue() {
        Editable editable = binding.editorInput.getText();
        return editable == null ? "" : editable.toString();
    }

    private void setEditorText(String value) {
        internalEditorUpdate = true;
        binding.editorInput.setText(value == null ? "" : value);
        binding.editorInput.setSelection(binding.editorInput.length());
        internalEditorUpdate = false;
        binding.textEditorLineNumbers.syncFromEditor();
        binding.editorInput.post(this::updateLineNumbers);
        updateEditorState();
    }

    private String firstNonEmpty(String primary, String fallback) {
        return TextUtils.isEmpty(primary) ? fallback : primary;
    }

    private String stringExtra(String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value;
    }

    private void updateLineNumbers() {
        binding.textEditorLineNumbers.setWrapLines(wrapLines);
        binding.textEditorLineNumbers.syncFromEditor();
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
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        return displayMetrics != null ? displayMetrics.widthPixels : 0;
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
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
