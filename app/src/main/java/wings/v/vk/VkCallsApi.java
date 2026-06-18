package wings.v.vk;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;

// Tiny wrapper around POST https://api.vk.com/method/calls.start. Returns the
// fresh join_link for the caller to drop into the VK links list. All requests
// are synchronous because the autolink flow is always invoked from a UI
// callback that already runs off the main thread (Executors.newSingleThreadExecutor).
// Timeouts are tight on purpose: a VK API hiccup must not block the autolink
// dialog past a few seconds.
@SuppressWarnings({ "PMD.CommentRequired", "PMD.LawOfDemeter" })
public final class VkCallsApi {

    private static final String API_URL = "https://api.vk.com/method/calls.start";
    private static final String API_VERSION = "5.199";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build();

    private VkCallsApi() {}

    @WorkerThread
    @NonNull
    public static String generateJoinLink(@NonNull Context context) throws IOException {
        String accessToken = VkOAuthAuth.accessTokenOrThrow(context);
        FormBody body = new FormBody.Builder().add("access_token", accessToken).add("v", API_VERSION).build();
        Request request = new Request.Builder().url(API_URL).post(body).build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            String payload = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("VK Calls HTTP " + response.code());
            }
            return parseJoinLink(payload);
        }
    }

    @NonNull
    static String parseJoinLink(@NonNull String payload) throws IOException {
        try {
            JSONObject root = new JSONObject(payload);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                int code = error.optInt("error_code", -1);
                String message = error.optString("error_msg", "VK API error");
                throw new IOException("VK Calls: " + message + " (" + code + ")");
            }
            JSONObject result = root.optJSONObject("response");
            String joinLink = result == null ? "" : result.optString("join_link", "");
            if (joinLink == null || joinLink.trim().isEmpty()) {
                throw new IOException("VK Calls response missing join_link");
            }
            return joinLink.trim();
        } catch (JSONException error) {
            throw new IOException("VK Calls response not JSON", error);
        }
    }
}
