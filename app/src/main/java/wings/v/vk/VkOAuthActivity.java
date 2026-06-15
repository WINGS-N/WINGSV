package wings.v.vk;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import wings.v.databinding.ActivityVkOauthBinding;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AtLeastOneConstructor", "PMD.LawOfDemeter" })
public final class VkOAuthActivity extends AppCompatActivity {

    private static final String EXTRA_URL = "wings.v.extra.VK_OAUTH_URL";
    private static final String BLANK_REDIRECT_PREFIX = "https://oauth.vk.com/blank.html";
    private ActivityVkOauthBinding binding;
    private WebView webView;

    public static Intent createIntent(Context context, String url) {
        return new Intent(context, VkOAuthActivity.class).putExtra(EXTRA_URL, url);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVkOauthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        applyWindowInsets();
        webView = new WebView(this);
        webView.setLayoutParams(
            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        );
        binding.webviewOauthContainer.addView(webView);
        configureWebView();
        webView.loadUrl(getIntent().getStringExtra(EXTRA_URL));
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

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(
            new WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int newProgress) {
                    if (binding == null) {
                        return;
                    }
                    binding.progressOauth.setIndeterminate(newProgress <= 5);
                    binding.progressOauth.setProgress(newProgress);
                    binding.progressOauth.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
            }
        );
        webView.setWebViewClient(
            new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return handleUrl(request.getUrl());
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return handleUrl(Uri.parse(url));
                }

                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    handleUrl(Uri.parse(url));
                }
            }
        );
    }

    private void applyWindowInsets() {
        final int toolbarBaseLeft = binding.toolbarLayout.getPaddingLeft();
        final int toolbarBaseTop = binding.toolbarLayout.getPaddingTop();
        final int toolbarBaseRight = binding.toolbarLayout.getPaddingRight();
        final int toolbarBaseBottom = binding.toolbarLayout.getPaddingBottom();
        final int baseLeft = binding.oauthContent.getPaddingLeft();
        final int baseTop = binding.oauthContent.getPaddingTop();
        final int baseRight = binding.oauthContent.getPaddingRight();
        final int baseBottom = binding.oauthContent.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.toolbarLayout.setPadding(
                toolbarBaseLeft,
                toolbarBaseTop + bars.top,
                toolbarBaseRight,
                toolbarBaseBottom
            );
            binding.oauthContent.setPadding(
                baseLeft + bars.left,
                baseTop,
                baseRight + bars.right,
                baseBottom + bars.bottom
            );
            return insets;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());
    }

    private boolean handleUrl(Uri uri) {
        if (!uri.toString().startsWith(BLANK_REDIRECT_PREFIX)) {
            return false;
        }
        VkIdManager.handleRedirect(getApplicationContext(), uri);
        finish();
        return true;
    }
}
