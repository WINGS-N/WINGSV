package wings.v;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import wings.v.core.AppPrefs;
import wings.v.core.CaptchaPromptSource;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityCaptchaBrowserBinding;
import wings.v.service.ProxyTunnelService;

@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.NullAssignment",
        "PMD.AvoidUsingHardCodedIP",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
    }
)
public class CaptchaBrowserActivity extends AppCompatActivity {

    private static final String LOG_TAG = "CaptchaBrowser";
    private static final int MAX_WEBVIEW_INIT_ATTEMPTS = 3;
    private static final long WEBVIEW_RETRY_DELAY_MS = 250L;
    private static final String EXTRA_URL = "wings.v.extra.CAPTCHA_URL";
    private static final String EXTRA_TRANSIENT_EXTERNAL_FLOW = "wings.v.extra.TRANSIENT_EXTERNAL_FLOW";
    private static final String EXTRA_CAPTCHA_SOURCE = "wings.v.extra.CAPTCHA_SOURCE";
    private static final String EXTRA_STOP_CONNECTION_ON_CANCEL = "wings.v.extra.STOP_CONNECTION_ON_CANCEL";
    private static final String EXTRA_USER_AGENT = "wings.v.extra.CAPTCHA_USER_AGENT";
    private static final String STATE_SUBTITLE_EXPANDED = "wings.v.state.CAPTCHA_SUBTITLE_EXPANDED";

    private ActivityCaptchaBrowserBinding binding;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private String captchaUrl;
    private String captchaUserAgent;
    private String defaultUserAgent;
    private String browserBaseUrl;
    private boolean completed;
    private boolean transientExternalFlow;
    private CaptchaPromptSource captchaSource = CaptchaPromptSource.PRIMARY;
    private boolean stopConnectionOnCancel = true;
    private boolean subtitleExpanded;
    private boolean detailsCollapsed;
    private WebView captchaWebView;
    private Intent pendingIntent;

    public static Intent createIntent(Context context, String url) {
        return new Intent(context, CaptchaBrowserActivity.class).putExtra(EXTRA_URL, url);
    }

    public static Intent createIntent(Context context, String url, boolean transientExternalFlow) {
        return createIntent(context, url).putExtra(EXTRA_TRANSIENT_EXTERNAL_FLOW, transientExternalFlow);
    }

    public static Intent createIntent(
        Context context,
        String url,
        boolean transientExternalFlow,
        CaptchaPromptSource captchaSource
    ) {
        return createIntent(context, url, transientExternalFlow).putExtra(
            EXTRA_CAPTCHA_SOURCE,
            captchaSource.wireValue
        );
    }

    public static Intent createIntent(
        Context context,
        String url,
        boolean transientExternalFlow,
        CaptchaPromptSource captchaSource,
        boolean stopConnectionOnCancel
    ) {
        return createIntent(context, url, transientExternalFlow, captchaSource, stopConnectionOnCancel, null);
    }

    public static Intent createIntent(
        Context context,
        String url,
        boolean transientExternalFlow,
        CaptchaPromptSource captchaSource,
        boolean stopConnectionOnCancel,
        @Nullable String userAgent
    ) {
        return createIntent(context, url, transientExternalFlow, captchaSource)
            .putExtra(EXTRA_STOP_CONNECTION_ON_CANCEL, stopConnectionOnCancel)
            .putExtra(EXTRA_USER_AGENT, userAgent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCaptchaBrowserBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        subtitleExpanded = savedInstanceState != null && savedInstanceState.getBoolean(STATE_SUBTITLE_EXPANDED, false);
        pendingIntent = getIntent();
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        binding.buttonCaptchaCancel.setOnClickListener(v -> {
            Haptics.softSelection(v);
            cancelCaptchaAndFinish();
        });
        binding.textCaptchaExpand.setOnClickListener(v -> {
            Haptics.softSelection(v);
            toggleSubtitleExpanded();
        });
        binding.textCaptchaCollapse.setOnClickListener(v -> {
            Haptics.softSelection(v);
            toggleDetailsCollapsed();
        });
        configureBackHandling();
        syncSubtitleExpansionUi();
        syncDetailsCollapseUi();
        initializeWebView(0);
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
        pendingIntent = intent;
        if (captchaWebView != null) {
            loadIntent(intent, false);
        }
    }

    @Override
    protected void onDestroy() {
        if (captchaWebView != null) {
            captchaWebView.stopLoading();
            captchaWebView.destroy();
            captchaWebView = null;
        }
        binding = null;
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private void initializeWebView(int attempt) {
        try {
            createAndAttachWebView();
            configureWebView();
            if (pendingIntent != null) {
                loadIntent(pendingIntent, true);
            }
        } catch (RuntimeException exception) {
            if (attempt + 1 < MAX_WEBVIEW_INIT_ATTEMPTS && binding != null) {
                Log.w(LOG_TAG, "WebView init failed, retry " + (attempt + 1), exception);
                binding.webviewCaptchaContainer.postDelayed(
                    () -> initializeWebView(attempt + 1),
                    WEBVIEW_RETRY_DELAY_MS * (attempt + 1)
                );
                return;
            }
            handleWebViewUnavailable(pendingIntent, exception);
        }
    }

    private void createAndAttachWebView() {
        if (binding == null) {
            throw new IllegalStateException("Binding is not ready");
        }
        if (captchaWebView != null) {
            binding.webviewCaptchaContainer.removeView(captchaWebView);
            captchaWebView.destroy();
            captchaWebView = null;
        }
        WebView webView = new WebView(this);
        webView.setLayoutParams(
            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        );
        binding.webviewCaptchaContainer.removeAllViews();
        binding.webviewCaptchaContainer.addView(webView);
        captchaWebView = webView;
    }

    private void configureBackHandling() {
        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (completed) {
                        finishSelf();
                        return;
                    }
                    cancelCaptchaAndFinish();
                }
            }
        );
    }

    private void configureWebView() {
        if (captchaWebView == null) {
            throw new IllegalStateException("WebView is not initialized");
        }
        WebSettings settings = captchaWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        defaultUserAgent = settings.getUserAgentString();

        captchaWebView.addJavascriptInterface(new CaptchaUiBridge(), "wingsvAndroid");
        captchaWebView.setWebChromeClient(new WebChromeClient());
        captchaWebView.setWebViewClient(
            new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return handleNavigation(request != null ? request.getUrl() : null);
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
                public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    android.webkit.WebResourceError error
                ) {
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
            }
        );
    }

    private void loadIntent(Intent intent, boolean initial) {
        String url = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        String nextUserAgent = intent != null ? intent.getStringExtra(EXTRA_USER_AGENT) : null;
        transientExternalFlow = intent != null && intent.getBooleanExtra(EXTRA_TRANSIENT_EXTERNAL_FLOW, false);
        captchaSource =
            intent == null
                ? CaptchaPromptSource.PRIMARY
                : CaptchaPromptSource.fromWireValue(intent.getStringExtra(EXTRA_CAPTCHA_SOURCE));
        stopConnectionOnCancel =
            intent == null
                ? captchaSource.stopsConnectionOnCancel()
                : intent.getBooleanExtra(EXTRA_STOP_CONNECTION_ON_CANCEL, captchaSource.stopsConnectionOnCancel());
        if (TextUtils.isEmpty(url)) {
            finishSelf();
            return;
        }
        if (captchaWebView == null) {
            pendingIntent = intent;
            return;
        }
        if (!initial && TextUtils.equals(url, captchaUrl) && TextUtils.equals(nextUserAgent, captchaUserAgent)) {
            return;
        }
        captchaUrl = url;
        captchaUserAgent = TextUtils.isEmpty(nextUserAgent) ? null : nextUserAgent;
        browserBaseUrl = buildBrowserBaseUrl(url);
        completed = false;
        detailsCollapsed = false;
        applyWebViewUserAgent(captchaUserAgent);
        binding.textCaptchaTitle.setText(
            captchaSource == CaptchaPromptSource.POOL
                ? R.string.captcha_browser_headline_pool
                : R.string.captcha_browser_headline
        );
        binding.textCaptchaSubtitle.setText(
            captchaSource == CaptchaPromptSource.POOL
                ? R.string.captcha_browser_subtitle_pool
                : R.string.captcha_browser_subtitle
        );
        binding.textCaptchaStatus.setText(R.string.captcha_browser_status_loading);
        syncDetailsCollapseUi();
        captchaWebView.loadUrl(url);
    }

    private void applyWebViewUserAgent(@Nullable String userAgent) {
        if (captchaWebView == null) {
            return;
        }
        WebSettings settings = captchaWebView.getSettings();
        settings.setUserAgentString(TextUtils.isEmpty(userAgent) ? defaultUserAgent : userAgent);
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
            if (captchaWebView != null) {
                captchaWebView.loadUrl(redirected);
            }
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

    private void toggleDetailsCollapsed() {
        detailsCollapsed = !detailsCollapsed;
        syncDetailsCollapseUi();
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

    private void syncDetailsCollapseUi() {
        if (binding == null) {
            return;
        }
        binding.layoutCaptchaDetails.setVisibility(detailsCollapsed ? View.GONE : View.VISIBLE);
        binding.textCaptchaCollapse.setImageResource(
            detailsCollapsed ? R.drawable.ic_sesl_arrow_enabled_down : R.drawable.ic_sesl_arrow_enabled_up
        );
        binding.textCaptchaCollapse.setContentDescription(
            getString(detailsCollapsed ? R.string.captcha_browser_expand : R.string.captcha_browser_collapse)
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

    private void handleWebViewUnavailable(@Nullable Intent intent, RuntimeException exception) {
        String url = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        Log.e(LOG_TAG, "Failed to initialize captcha WebView", exception);
        if (TextUtils.isEmpty(url)) {
            finishSelf();
            return;
        }
        Toast.makeText(this, "WebView недоступен, captcha будет открыта в внешнем браузере", Toast.LENGTH_LONG).show();
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (browserIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(browserIntent);
        }
        finishSelf();
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

    @SuppressWarnings("PMD.PublicMemberInNonPublicType")
    private final class CaptchaUiBridge {

        @JavascriptInterface
        public void performActionHaptic() {
            runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                View target = binding.layoutCaptchaStatus != null ? binding.layoutCaptchaStatus : binding.getRoot();
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
