package wings.v;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityVkOauthBinding;
import wings.v.vk.VkOAuthAuth;

// WebView host for the VK OAuth implicit flow. Mirrors CaptchaBrowserActivity
// structure (ToolbarLayout + RoundedLinearLayout status card + WebView in a
// rounded surface) so the autolink flow visually slots in with every other
// browser-style screen in the app. We do not piggyback on CaptchaBrowserActivity
// directly because its lifecycle (cancel-by-HTTP to local proxy, captcha-source
// state, transient-external-flow handling) does not fit OAuth.
@SuppressWarnings(
    {
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class VkOAuthActivity extends AppCompatActivity {

    public static final int RESULT_AUTHORIZED = RESULT_FIRST_USER + 1;

    private static final String LOG_TAG = "VkOAuthActivity";
    private static final int MAX_WEBVIEW_INIT_ATTEMPTS = 3;
    private static final long WEBVIEW_RETRY_DELAY_MS = 250L;

    private ActivityVkOauthBinding binding;
    private WebView webView;
    private String authorizeUrl;
    private boolean finishedSuccessfully;

    public static Intent createIntent(Context context) {
        return new Intent(context, VkOAuthActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVkOauthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        binding.buttonVkOauthRetry.setOnClickListener(view -> {
            Haptics.softSelection(view);
            reloadCurrentUrl();
        });
        binding.buttonVkOauthCancel.setOnClickListener(view -> {
            Haptics.softSelection(view);
            finishCancelled();
        });
        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (finishedSuccessfully) {
                        finish();
                        return;
                    }
                    finishCancelled();
                }
            }
        );
        if (!VkOAuthAuth.isClientConfigured()) {
            showError(getString(R.string.vk_oauth_status_not_configured));
            binding.buttonVkOauthRetry.setEnabled(false);
            return;
        }
        try {
            authorizeUrl = VkOAuthAuth.beginAuthorization(getApplicationContext());
        } catch (RuntimeException error) {
            Log.e(LOG_TAG, "Failed to build authorize URL", error);
            showError(getString(R.string.vk_oauth_status_failed));
            return;
        }
        initializeWebView(0);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        binding = null;
        super.onDestroy();
    }

    private void initializeWebView(int attempt) {
        try {
            createAndAttachWebView();
            configureWebView();
            if (!TextUtils.isEmpty(authorizeUrl)) {
                webView.loadUrl(authorizeUrl);
            }
        } catch (RuntimeException error) {
            if (attempt + 1 < MAX_WEBVIEW_INIT_ATTEMPTS && binding != null) {
                Log.w(LOG_TAG, "WebView init failed, retry " + (attempt + 1), error);
                binding.webviewVkOauthContainer.postDelayed(
                    () -> initializeWebView(attempt + 1),
                    WEBVIEW_RETRY_DELAY_MS * (attempt + 1)
                );
                return;
            }
            Log.e(LOG_TAG, "WebView unavailable", error);
            showError(getString(R.string.vk_oauth_status_webview_unavailable));
        }
    }

    private void createAndAttachWebView() {
        if (binding == null) {
            throw new IllegalStateException("Binding is not ready");
        }
        if (webView != null) {
            binding.webviewVkOauthContainer.removeView(webView);
            webView.destroy();
            webView = null;
        }
        WebView fresh = new WebView(this);
        fresh.setLayoutParams(
            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        );
        binding.webviewVkOauthContainer.removeAllViews();
        binding.webviewVkOauthContainer.addView(fresh);
        webView = fresh;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(
            new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return interceptRedirect(request == null ? null : request.getUrl());
                }

                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    setLoadingState(true);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    setLoadingState(false);
                    // VK redirects implicit-flow callbacks via JS sometimes; the
                    // shouldOverrideUrlLoading callback misses those, so we
                    // double-check the final URL once the page has settled.
                    if (!TextUtils.isEmpty(url) && VkOAuthAuth.isRedirectUri(url)) {
                        interceptRedirect(Uri.parse(url));
                    }
                }

                @Override
                public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    android.webkit.WebResourceError error
                ) {
                    super.onReceivedError(view, request, error);
                    if (request == null || !request.isForMainFrame()) {
                        return;
                    }
                    CharSequence description = error != null ? error.getDescription() : null;
                    showError(
                        TextUtils.isEmpty(description)
                            ? getString(R.string.vk_oauth_status_failed)
                            : description.toString()
                    );
                }
            }
        );
    }

    private boolean interceptRedirect(@Nullable Uri uri) {
        if (uri == null) {
            return false;
        }
        String url = uri.toString();
        if (!VkOAuthAuth.isRedirectUri(url)) {
            return false;
        }
        String error = VkOAuthAuth.consumeRedirect(getApplicationContext(), uri);
        if (error != null) {
            showError(error);
            return true;
        }
        finishedSuccessfully = true;
        setResult(RESULT_AUTHORIZED);
        finish();
        return true;
    }

    private void reloadCurrentUrl() {
        if (binding == null) {
            return;
        }
        if (webView == null) {
            initializeWebView(0);
            return;
        }
        if (TextUtils.isEmpty(authorizeUrl)) {
            return;
        }
        clearErrorUi();
        webView.loadUrl(authorizeUrl);
    }

    private void setLoadingState(boolean loading) {
        if (binding == null) {
            return;
        }
        if (loading) {
            clearErrorUi();
        }
        binding.progressVkOauthStatus.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.textVkOauthStatus.setText(
            loading ? R.string.vk_oauth_status_loading : R.string.vk_oauth_status_waiting
        );
    }

    private void clearErrorUi() {
        if (binding == null) {
            return;
        }
        binding.layoutVkOauthError.setVisibility(View.GONE);
    }

    private void showError(@Nullable String detail) {
        if (binding == null) {
            return;
        }
        binding.progressVkOauthStatus.setVisibility(View.GONE);
        binding.textVkOauthStatus.setText(R.string.vk_oauth_status_failed);
        binding.layoutVkOauthError.setVisibility(View.VISIBLE);
        binding.textVkOauthError.setText(
            TextUtils.isEmpty(detail) ? getString(R.string.vk_oauth_status_failed) : detail
        );
        Haptics.softSelection(binding.layoutVkOauthError);
    }

    private void finishCancelled() {
        setResult(RESULT_CANCELED);
        finish();
    }
}
