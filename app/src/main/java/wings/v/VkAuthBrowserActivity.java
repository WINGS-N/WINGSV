package wings.v;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
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
import wings.v.core.AppPrefs;
import wings.v.core.Haptics;
import wings.v.databinding.ActivityVkAuthBrowserBinding;
import wings.v.service.ProxyTunnelService;

/**
 * Browser for the VK account-auth TURN flow. It loads vk.com DIRECTLY in a
 * WebView inside a OneUI ToolbarLayout with a loading status, a progress
 * indicator and onReceivedError handling. Once the VK web session is signed in,
 * it hands the live session cookies + WebView User-Agent to ProxyTunnelService
 * (in process :tunnel) via ACTION_VK_COOKIES; the service forwards them to the
 * relay process stdin as one vk_cookies JSON line, and the relay mints the
 * privileged web token and runs the OK-infra TURN chain itself.
 *
 * The VK login session is persisted in the WebView CookieManager so the user
 * does not have to re-login across runs. If the user is not signed in, the same
 * WebView shows VK's login page; after login the cookies persist.
 *
 * Direct connectivity: the relay/tunnel may not be connected yet while auth is
 * pending, so the WebView must not route through the half-built VPN tunnel. We
 * bind THIS process (the main process, where the WebView lives) to the
 * underlying non-VPN network for the duration of the auth; the :tunnel process
 * is unaffected.
 *
 * The relay drives completion and failure over the JSONL PROXY_EVENT channel
 * (vk_account_auth_complete / vk_account_auth_failed); ProxyTunnelService reacts
 * to those by re-launching this activity with a FINISH action so the browser
 * closes across the process boundary.
 */
@SuppressWarnings(
    {
        "PMD.DoNotUseThreads",
        "PMD.NullAssignment",
        "PMD.CommentRequired",
        "PMD.LawOfDemeter",
        "PMD.MethodArgumentCouldBeFinal",
        "PMD.LocalVariableCouldBeFinal",
        "PMD.LongVariable",
        "PMD.OnlyOneReturn",
        "PMD.GodClass",
        "PMD.CyclomaticComplexity",
    }
)
public class VkAuthBrowserActivity extends AppCompatActivity {

    public static final String ACTION_FINISH = "wings.v.action.VK_AUTH_BROWSER_FINISH";

    private static final String LOG_TAG = "VkAuthBrowser";
    private static final int MAX_WEBVIEW_INIT_ATTEMPTS = 3;
    private static final long WEBVIEW_RETRY_DELAY_MS = 250L;
    private static final String EXTRA_URL = "wings.v.extra.VK_AUTH_URL";
    private static final String STATE_SUBTITLE_EXPANDED = "wings.v.state.VK_AUTH_SUBTITLE_EXPANDED";

    private ActivityVkAuthBrowserBinding binding;
    private String authUrl;
    private boolean completed;
    private boolean loadFailed;
    private boolean subtitleExpanded;
    private boolean detailsCollapsed;
    private WebView authWebView;
    private Intent pendingIntent;
    // Network this process was bound to for direct (non-VPN) connectivity during
    // auth; restored to null in onDestroy. Null means we never bound.
    private Network boundNetwork;

    public static Intent createIntent(Context context, String url) {
        return new Intent(context, VkAuthBrowserActivity.class).putExtra(EXTRA_URL, url);
    }

    public static Intent createFinishIntent(Context context) {
        return new Intent(context, VkAuthBrowserActivity.class).setAction(ACTION_FINISH);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVkAuthBrowserBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        subtitleExpanded = savedInstanceState != null && savedInstanceState.getBoolean(STATE_SUBTITLE_EXPANDED, false);
        pendingIntent = getIntent();
        bindToUnderlyingNetwork();
        binding.toolbarLayout.setShowNavigationButtonAsBack(true);
        binding.buttonVkAuthCancel.setOnClickListener(v -> {
            Haptics.softSelection(v);
            cancelAuthAndFinish();
        });
        binding.buttonVkAuthRetry.setOnClickListener(v -> {
            Haptics.softSelection(v);
            retryLoad();
        });
        binding.textVkAuthExpand.setOnClickListener(v -> {
            Haptics.softSelection(v);
            toggleSubtitleExpanded();
        });
        binding.textVkAuthCollapse.setOnClickListener(v -> {
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
        if (intent != null && ACTION_FINISH.equals(intent.getAction())) {
            // Relay signalled vk_account_auth_complete / _failed across the
            // process boundary; close without re-cancelling (the relay is done).
            completed = true;
            finishSelf();
            return;
        }
        setIntent(intent);
        pendingIntent = intent;
        if (authWebView != null) {
            loadIntent(intent, false);
        }
    }

    @Override
    protected void onDestroy() {
        if (authWebView != null) {
            authWebView.stopLoading();
            authWebView.destroy();
            authWebView = null;
        }
        flushCookies();
        // Only release the process-wide network binding when this is the real
        // teardown (the single instance is actually finishing), never on a
        // transient destroy such as a configuration change, so the WebView keeps
        // reaching vk.com directly instead of falling back to the half-built VPN
        // tunnel.
        if (isFinishing()) {
            unbindFromUnderlyingNetwork();
        }
        binding = null;
        super.onDestroy();
    }

    // Bind the main process (where the WebView lives) to the underlying non-VPN
    // network so the WebView reaches vk.com directly. While auth is pending the
    // VPN tunnel is not yet connected; routing the WebView through it would fail
    // DNS (NAME_NOT_RESOLVED). The :tunnel process is a separate process and is
    // not affected by this per-process binding.
    private void bindToUnderlyingNetwork() {
        // Already bound from this single instance: do not re-bind, so a transient
        // onCreate/onNewIntent reuse does not thrash the process-wide binding.
        if (boundNetwork != null) {
            return;
        }
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return;
        }
        Network network = findNonVpnNetwork(connectivityManager);
        if (network == null) {
            Log.w(LOG_TAG, "No non-VPN network found; WebView may route through the tunnel");
            return;
        }
        try {
            if (connectivityManager.bindProcessToNetwork(network)) {
                boundNetwork = network;
                Log.i(LOG_TAG, "Bound process to underlying non-VPN network for VK auth");
            }
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "Failed to bind process to underlying network", error);
        }
    }

    private void unbindFromUnderlyingNetwork() {
        if (boundNetwork == null) {
            return;
        }
        boundNetwork = null;
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return;
        }
        try {
            connectivityManager.bindProcessToNetwork(null);
        } catch (RuntimeException ignored) {
            // Best effort; the process is going away anyway.
        }
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private static Network findNonVpnNetwork(ConnectivityManager connectivityManager) {
        try {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (isNonVpnNetwork(connectivityManager, activeNetwork)) {
                return activeNetwork;
            }
            Network[] networks = connectivityManager.getAllNetworks();
            if (networks == null) {
                return null;
            }
            for (Network network : networks) {
                if (isNonVpnNetwork(connectivityManager, network)) {
                    return network;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static boolean isNonVpnNetwork(ConnectivityManager connectivityManager, @Nullable Network network) {
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return false;
        }
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
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
                binding.webviewVkAuthContainer.postDelayed(
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
        if (authWebView != null) {
            binding.webviewVkAuthContainer.removeView(authWebView);
            authWebView.destroy();
            authWebView = null;
        }
        WebView webView = new WebView(this);
        webView.setLayoutParams(
            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        );
        binding.webviewVkAuthContainer.removeAllViews();
        binding.webviewVkAuthContainer.addView(webView);
        authWebView = webView;
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
                    cancelAuthAndFinish();
                }
            }
        );
    }

    private void configureWebView() {
        if (authWebView == null) {
            throw new IllegalStateException("WebView is not initialized");
        }
        // Persistent cookies so the VK login session survives across runs.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(authWebView, true);
        // Seed the WebView jar with the latest session the relay rotated into the
        // cross-process store, so reopening the browser reflects the current login
        // instead of a stale CookieManager copy.
        syncCookiesFromStore(cookieManager);

        WebSettings settings = authWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        authWebView.addJavascriptInterface(new VkAuthUiBridge(), "wingsvAndroid");
        authWebView.setWebChromeClient(new WebChromeClient());
        authWebView.setWebViewClient(
            new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    // Load vk.com directly; let the WebView follow all navigations.
                    return false;
                }

                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    clearErrorState();
                    setLoadingState(true);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    flushCookies();
                    maybeDeliverCookies();
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
                        // Genuine main-frame failure: show the error state with a
                        // Retry button and DO NOT auto-close. Auto-closing here
                        // would make the relay re-emit vk_account_auth_required and
                        // re-open the browser in a tight loop. Only the user's
                        // Cancel / back closes the activity.
                        showErrorState();
                    }
                }
            }
        );
    }

    // Loads the cross-process VK session (which the relay keeps current by mirroring
    // every rotated Set-Cookie jar back to the app) into the WebView CookieManager
    // for *.vk.com, so reopening the browser starts from the latest session.
    private void syncCookiesFromStore(CookieManager cookieManager) {
        String cookies = AppPrefs.getVkSessionCookies(this);
        if (TextUtils.isEmpty(cookies)) {
            return;
        }
        for (String pair : cookies.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty() || trimmed.indexOf('=') <= 0) {
                continue;
            }
            cookieManager.setCookie("https://vk.com", trimmed + "; Domain=.vk.com; Path=/");
        }
        cookieManager.flush();
    }

    // Once the VK web session is signed in (remixsid present), hand the live
    // session cookies + WebView User-Agent to the tunnel service, which forwards
    // them to the relay over stdin. The relay mints the privileged web token and
    // runs the OK-infra TURN chain itself.
    private void maybeDeliverCookies() {
        if (completed) {
            return;
        }
        CookieManager cookieManager = CookieManager.getInstance();
        String cookies = cookieManager.getCookie("https://login.vk.com/");
        if (TextUtils.isEmpty(cookies)) {
            cookies = cookieManager.getCookie("https://vk.com/");
        }
        if (TextUtils.isEmpty(cookies) || !cookies.contains("remixsid")) {
            // Not signed in yet; the VK login page is showing. Wait for the user.
            return;
        }
        completed = true;
        if (binding != null) {
            binding.progressVkAuthStatus.setVisibility(View.GONE);
        }
        deliverCookies(cookies, WebSettings.getDefaultUserAgent(this));
        finishSelf();
    }

    private void deliverCookies(String cookies, String userAgent) {
        try {
            startService(
                new Intent(this, ProxyTunnelService.class)
                    .setAction(ProxyTunnelService.ACTION_VK_COOKIES)
                    .putExtra(ProxyTunnelService.EXTRA_VK_COOKIES, cookies)
                    .putExtra(ProxyTunnelService.EXTRA_VK_UA, userAgent)
            );
            Log.i(LOG_TAG, "Delivered VK session cookies to tunnel service");
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "Failed to deliver VK session cookies to tunnel service", error);
        }
    }

    private void flushCookies() {
        try {
            CookieManager.getInstance().flush();
        } catch (RuntimeException ignored) {
            // CookieManager may be unavailable if WebView failed to initialize.
        }
    }

    private void loadIntent(Intent intent, boolean initial) {
        String url = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        if (TextUtils.isEmpty(url)) {
            finishSelf();
            return;
        }
        if (authWebView == null) {
            pendingIntent = intent;
            return;
        }
        if (!initial && TextUtils.equals(url, authUrl)) {
            return;
        }
        authUrl = url;
        completed = false;
        detailsCollapsed = false;
        clearErrorState();
        binding.textVkAuthTitle.setText(R.string.vk_auth_browser_headline);
        binding.textVkAuthSubtitle.setText(R.string.vk_auth_browser_subtitle);
        binding.textVkAuthStatus.setText(R.string.vk_auth_browser_status_loading);
        syncDetailsCollapseUi();
        authWebView.loadUrl(url);
    }

    private void setLoadingState(boolean loading) {
        if (binding == null || loadFailed) {
            return;
        }
        binding.progressVkAuthStatus.setVisibility(completed ? View.GONE : View.VISIBLE);
        if (completed) {
            return;
        }
        binding.textVkAuthStatus.setText(
            loading ? R.string.vk_auth_browser_status_loading : R.string.vk_auth_browser_status_waiting
        );
    }

    // Genuine main-frame load failure: show the failed status and a Retry
    // button, hide the spinner, and keep the activity open. The relay loop is
    // broken because we never auto-close on a failed load; the user retries or
    // cancels.
    private void showErrorState() {
        loadFailed = true;
        if (binding == null) {
            return;
        }
        binding.textVkAuthStatus.setText(R.string.vk_auth_browser_status_failed);
        binding.progressVkAuthStatus.setVisibility(View.GONE);
        binding.buttonVkAuthRetry.setVisibility(View.VISIBLE);
    }

    private void clearErrorState() {
        loadFailed = false;
        if (binding != null) {
            binding.buttonVkAuthRetry.setVisibility(View.GONE);
        }
    }

    // User-initiated retry of the current auth URL. Reloads the same vk.com link
    // in place; nothing re-opens the activity automatically.
    private void retryLoad() {
        if (completed || authWebView == null || TextUtils.isEmpty(authUrl)) {
            return;
        }
        clearErrorState();
        setLoadingState(true);
        authWebView.loadUrl(authUrl);
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
        binding.textVkAuthSubtitle.setMaxLines(subtitleExpanded ? Integer.MAX_VALUE : 2);
        binding.textVkAuthSubtitle.setEllipsize(subtitleExpanded ? null : TextUtils.TruncateAt.END);
        binding.textVkAuthExpand.setText(
            subtitleExpanded ? R.string.vk_auth_browser_less : R.string.vk_auth_browser_more
        );
    }

    private void syncDetailsCollapseUi() {
        if (binding == null) {
            return;
        }
        binding.layoutVkAuthDetails.setVisibility(detailsCollapsed ? View.GONE : View.VISIBLE);
        binding.textVkAuthCollapse.setImageResource(
            detailsCollapsed ? R.drawable.ic_sesl_arrow_enabled_down : R.drawable.ic_sesl_arrow_enabled_up
        );
        binding.textVkAuthCollapse.setContentDescription(
            getString(detailsCollapsed ? R.string.vk_auth_browser_expand : R.string.vk_auth_browser_collapse)
        );
    }

    // Cancel before completion: tell the relay to abort the account-auth wait
    // (cancel=true over stdin) and stop the tunnel, since cancelling auth means
    // there will be no connection.
    private void cancelAuthAndFinish() {
        if (completed) {
            finishSelf();
            return;
        }
        completed = true;
        if (binding != null) {
            binding.progressVkAuthStatus.setVisibility(View.GONE);
        }
        try {
            startService(
                new Intent(this, ProxyTunnelService.class)
                    .setAction(ProxyTunnelService.ACTION_VK_ACCOUNT_CREDS)
                    .putExtra(ProxyTunnelService.EXTRA_VK_LINK, authUrl)
                    .putExtra(ProxyTunnelService.EXTRA_VK_CANCEL, true)
            );
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "Failed to deliver VK auth cancel to tunnel service", error);
        }
        ProxyTunnelService.requestStop(getApplicationContext());
        finishSelf();
    }

    private void finishSelf() {
        finish();
    }

    private void handleWebViewUnavailable(@Nullable Intent intent, RuntimeException exception) {
        String url = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        Log.e(LOG_TAG, "Failed to initialize VK-auth WebView", exception);
        if (TextUtils.isEmpty(url)) {
            finishSelf();
            return;
        }
        Toast.makeText(this, R.string.vk_auth_browser_webview_unavailable, Toast.LENGTH_LONG).show();
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (browserIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(browserIntent);
        }
        finishSelf();
    }

    @SuppressWarnings("PMD.PublicMemberInNonPublicType")
    private final class VkAuthUiBridge {

        @JavascriptInterface
        public void performActionHaptic() {
            runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                View target = binding.layoutVkAuthStatus != null ? binding.layoutVkAuthStatus : binding.getRoot();
                target.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            });
        }
    }
}
