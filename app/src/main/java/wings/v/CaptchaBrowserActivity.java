package wings.v;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import wings.v.databinding.ActivityCaptchaBrowserBinding;
import wings.v.core.AppPrefs;
import wings.v.core.CaptchaPromptSource;
import wings.v.service.ProxyTunnelService;

public class CaptchaBrowserActivity extends AppCompatActivity {
    private static final String EXTRA_URL = "wings.v.extra.CAPTCHA_URL";
    private static final String EXTRA_TRANSIENT_EXTERNAL_FLOW = "wings.v.extra.TRANSIENT_EXTERNAL_FLOW";
    private static final String EXTRA_CAPTCHA_SOURCE = "wings.v.extra.CAPTCHA_SOURCE";
    private static final String EXTRA_STOP_CONNECTION_ON_CANCEL =
            "wings.v.extra.STOP_CONNECTION_ON_CANCEL";
    private static final String STATE_SUBTITLE_EXPANDED = "wings.v.state.CAPTCHA_SUBTITLE_EXPANDED";

    private ActivityCaptchaBrowserBinding binding;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private String captchaUrl;
    private String browserBaseUrl;
    private boolean completed;
    private boolean transientExternalFlow;
    private CaptchaPromptSource captchaSource = CaptchaPromptSource.PRIMARY;
    private boolean stopConnectionOnCancel = true;
    private boolean subtitleExpanded;

    public static Intent createIntent(Context context, String url) {
        return new Intent(context, CaptchaBrowserActivity.class)
                .putExtra(EXTRA_URL, url);
    }

    public static Intent createIntent(Context context, String url, boolean transientExternalFlow) {
        return createIntent(context, url)
                .putExtra(EXTRA_TRANSIENT_EXTERNAL_FLOW, transientExternalFlow);
    }

    public static Intent createIntent(Context context,
                                      String url,
                                      boolean transientExternalFlow,
                                      CaptchaPromptSource captchaSource) {
        return createIntent(context, url, transientExternalFlow)
                .putExtra(EXTRA_CAPTCHA_SOURCE, captchaSource.wireValue);
    }

    public static Intent createIntent(Context context,
                                      String url,
                                      boolean transientExternalFlow,
                                      CaptchaPromptSource captchaSource,
                                      boolean stopConnectionOnCancel) {
        return createIntent(context, url, transientExternalFlow, captchaSource)
                .putExtra(EXTRA_STOP_CONNECTION_ON_CANCEL, stopConnectionOnCancel);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCaptchaBrowserBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        subtitleExpanded = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_SUBTITLE_EXPANDED, false);
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        binding.buttonCaptchaCancel.setOnClickListener(v -> cancelCaptchaAndFinish());
        binding.textCaptchaExpand.setOnClickListener(v -> toggleSubtitleExpanded());
        configureBackHandling();
        configureWebView();
        syncSubtitleExpansionUi();
        loadIntent(getIntent(), true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_SUBTITLE_EXPANDED, subtitleExpanded);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadIntent(intent, false);
    }

    @Override
    protected void onDestroy() {
        if (binding != null) {
            binding.webviewCaptcha.stopLoading();
            binding.webviewCaptcha.destroy();
            binding = null;
        }
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private void configureBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (completed) {
                    finishSelf();
                    return;
                }
                cancelCaptchaAndFinish();
            }
        });
    }

    private void configureWebView() {
        WebSettings settings = binding.webviewCaptcha.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        binding.webviewCaptcha.addJavascriptInterface(new CaptchaUiBridge(), "wingsvAndroid");
        binding.webviewCaptcha.setWebChromeClient(new WebChromeClient());
        binding.webviewCaptcha.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request != null ? request.getUrl() : null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
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
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    binding.textCaptchaStatus.setText(R.string.captcha_browser_status_failed);
                    binding.progressCaptchaStatus.setVisibility(View.GONE);
                    if (error != null && error.getDescription() != null) {
                        Toast.makeText(
                                CaptchaBrowserActivity.this,
                                error.getDescription(),
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                    setLoadingState(false);
                }
            }
        });
    }

    private void loadIntent(Intent intent, boolean initial) {
        String url = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        transientExternalFlow = intent != null
                && intent.getBooleanExtra(EXTRA_TRANSIENT_EXTERNAL_FLOW, false);
        captchaSource = intent == null
                ? CaptchaPromptSource.PRIMARY
                : CaptchaPromptSource.fromWireValue(intent.getStringExtra(EXTRA_CAPTCHA_SOURCE));
        stopConnectionOnCancel = intent == null
                ? captchaSource.stopsConnectionOnCancel()
                : intent.getBooleanExtra(
                        EXTRA_STOP_CONNECTION_ON_CANCEL,
                        captchaSource.stopsConnectionOnCancel()
                );
        if (TextUtils.isEmpty(url)) {
            finishSelf();
            return;
        }
        if (!initial && TextUtils.equals(url, captchaUrl)) {
            return;
        }
        captchaUrl = url;
        browserBaseUrl = buildBrowserBaseUrl(url);
        completed = false;
        binding.textCaptchaTitle.setText(captchaSource == CaptchaPromptSource.POOL
                ? R.string.captcha_browser_headline_pool
                : R.string.captcha_browser_headline);
        binding.textCaptchaSubtitle.setText(captchaSource == CaptchaPromptSource.POOL
                ? R.string.captcha_browser_subtitle_pool
                : R.string.captcha_browser_subtitle);
        binding.textCaptchaStatus.setText(R.string.captcha_browser_status_loading);
        binding.webviewCaptcha.loadUrl(url);
    }

    private boolean handleNavigation(@Nullable Uri uri) {
        if (uri == null) {
            return false;
        }
        if (isCompletionUri(uri)) {
            completed = true;
            binding.textCaptchaStatus.setText(R.string.captcha_browser_status_complete);
            binding.progressCaptchaStatus.setVisibility(View.GONE);
            if (!stopConnectionOnCancel) {
                ProxyTunnelService.clearPendingCaptchaPrompt(getApplicationContext());
            }
            finishSelf();
            return true;
        }
        if (isCancelUri(uri)) {
            completed = true;
            binding.progressCaptchaStatus.setVisibility(View.GONE);
            if (!stopConnectionOnCancel) {
                ProxyTunnelService.clearPendingCaptchaPrompt(getApplicationContext());
            }
            finishSelf();
            return true;
        }
        String redirected = maybeRedirectToLocalProxy(uri);
        if (!TextUtils.isEmpty(redirected)) {
            binding.webviewCaptcha.loadUrl(redirected);
            return true;
        }
        return false;
    }

    private void setLoadingState(boolean loading) {
        if (binding == null) {
            return;
        }
        binding.progressCaptchaStatus.setVisibility(completed ? View.GONE : View.VISIBLE);
        if (completed) {
            return;
        }
        binding.textCaptchaStatus.setText(
                loading ? R.string.captcha_browser_status_loading : R.string.captcha_browser_status_waiting
        );
    }

    private void toggleSubtitleExpanded() {
        subtitleExpanded = !subtitleExpanded;
        syncSubtitleExpansionUi();
    }

    private void syncSubtitleExpansionUi() {
        if (binding == null) {
            return;
        }
        binding.textCaptchaSubtitle.setMaxLines(subtitleExpanded ? Integer.MAX_VALUE : 2);
        binding.textCaptchaSubtitle.setEllipsize(subtitleExpanded ? null : TextUtils.TruncateAt.END);
        binding.textCaptchaExpand.setText(
                subtitleExpanded ? R.string.captcha_browser_less : R.string.captcha_browser_more
        );
    }

    private void cancelCaptchaAndFinish() {
        completed = true;
        if (binding != null) {
            binding.progressCaptchaStatus.setVisibility(View.GONE);
        }
        final String cancelUrl = browserBaseUrl + "/_wingsv/captcha-cancel";
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(cancelUrl).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setDoOutput(true);
                connection.getResponseCode();
            } catch (IOException ignored) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                AppPrefs.setExternalActionTransientLaunchPending(getApplicationContext(), false);
                ProxyTunnelService.clearPendingCaptchaPrompt(getApplicationContext());
                if (stopConnectionOnCancel) {
                    ProxyTunnelService.requestStop(getApplicationContext());
                }
                runOnUiThread(this::finishSelf);
            }
        });
    }

    private void finishSelf() {
        AppPrefs.setExternalActionTransientLaunchPending(getApplicationContext(), false);
        if (!stopConnectionOnCancel) {
            ProxyTunnelService.clearPendingCaptchaPrompt(getApplicationContext());
        }
        if (transientExternalFlow) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }

    private boolean isCompletionUri(Uri uri) {
        return isLocalCaptchaPath(uri, "/_wingsv/captcha-complete");
    }

    private boolean isCancelUri(Uri uri) {
        return isLocalCaptchaPath(uri, "/_wingsv/captcha-cancel");
    }

    private boolean isLocalCaptchaPath(Uri uri, String path) {
        String host = uri.getHost();
        return isLocalHost(host) && TextUtils.equals(path, uri.getPath());
    }

    private final class CaptchaUiBridge {
        @JavascriptInterface
        public void performActionHaptic() {
            runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                View target = binding.layoutCaptchaStatus != null
                        ? binding.layoutCaptchaStatus
                        : binding.getRoot();
                target.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            });
        }
    }

    @Nullable
    private String maybeRedirectToLocalProxy(Uri uri) {
        String scheme = uri.getScheme();
        if (!TextUtils.equals("http", scheme) && !TextUtils.equals("https", scheme)) {
            return null;
        }
        if (isLocalHost(uri.getHost()) || TextUtils.isEmpty(browserBaseUrl)) {
            return null;
        }
        return browserBaseUrl + "/_wingsv/generic-proxy?proxy_url=" + Uri.encode(uri.toString());
    }

    private static boolean isLocalHost(@Nullable String host) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }

    private static String buildBrowserBaseUrl(String url) {
        Uri uri = Uri.parse(url);
        String scheme = TextUtils.isEmpty(uri.getScheme()) ? "http" : uri.getScheme();
        String host = TextUtils.isEmpty(uri.getHost()) ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort();
        if (port > 0) {
            return scheme + "://" + host + ":" + port;
        }
        return scheme + "://" + host;
    }
}
