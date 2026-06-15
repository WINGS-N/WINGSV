package wings.v.vk;

import android.content.Context;
import java.io.IOException;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;

@SuppressWarnings({ "PMD.CommentRequired", "PMD.AtLeastOneConstructor", "PMD.AvoidThrowingRawExceptionTypes" })
public final class VkCallsApi {

    private static final String API_URL = "https://api.vk.com/method/calls.start";
    private static final String API_VERSION = "5.199";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();

    private VkCallsApi() {}

    public static String createAndStoreJoinLink(Context context) throws IOException {
        String accessToken = VkIdManager.accessTokenForApiBlocking(context);
        FormBody body = new FormBody.Builder()
            .add("access_token", accessToken)
            .add("v", API_VERSION)
            .build();
        Request request = new Request.Builder().url(API_URL).post(body).build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            String payload = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("VK Calls HTTP " + response.code());
            }
            return parseAndStore(context, payload);
        }
    }

    static String parseAndStore(Context context, String payload) throws IOException {
        try {
            JSONObject root = new JSONObject(payload);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                throw new IOException(
                    "VK Calls: " + error.optString("error_msg", "ошибка API") + " (" + error.optInt("error_code") + ")"
                );
            }
            JSONObject result = root.optJSONObject("response");
            String joinLink = result == null ? "" : result.optString("join_link", "").trim();
            String callId = result == null ? "" : result.optString("call_id", "").trim();
            if (joinLink.isEmpty()) {
                throw new IOException("VK Calls не вернул ссылку на звонок");
            }
            VkCallStore.saveCall(context, callId, joinLink);
            return joinLink;
        } catch (JSONException error) {
            throw new IOException("Некорректный ответ VK Calls", error);
        }
    }
}
