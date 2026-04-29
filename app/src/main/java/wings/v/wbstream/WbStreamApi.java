package wings.v.wbstream;

import androidx.annotation.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Minimal HTTP client for stream.wb.ru — used to pre-create a LiveKit room and
 * obtain a stable room_id before spawning the wb-stream proxy. The proxy
 * itself does the same calls in Go (wbstream.AcquireRoomToken), but if we let
 * it pick the room_id then there is nothing to send through the
 * CLIENT_HELLO_TYPE_ROOM_EXCHANGE handshake to the server — the room_id only
 * exists after the proxy has already connected.
 */
@SuppressWarnings(
    { "PMD.AvoidUsingHardCodedIP", "PMD.CommentRequired", "PMD.CloseResource", "PMD.AssignmentInOperand" }
)
public final class WbStreamApi {

    private static final String API_BASE = "https://stream.wb.ru";
    private static final String USER_AGENT = "WINGSV";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private WbStreamApi() {}

    @NonNull
    public static String registerGuest(@NonNull String displayName) throws IOException {
        try {
            JSONObject device = new JSONObject();
            device.put("deviceName", "Linux");
            device.put("deviceType", "PARTICIPANT_DEVICE_TYPE_WEB_DESKTOP");
            JSONObject body = new JSONObject();
            body.put("displayName", displayName);
            body.put("device", device);
            JSONObject response = exchangeJson(API_BASE + "/auth/api/v1/auth/user/guest-register", "POST", null, body);
            String token = response.optString("accessToken", "");
            if (token.isEmpty()) {
                throw new IOException("guest-register: empty accessToken");
            }
            return token;
        } catch (JSONException error) {
            throw new IOException("guest-register: " + error.getMessage(), error);
        }
    }

    @NonNull
    public static String createRoom(@NonNull String accessToken) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("roomType", "ROOM_TYPE_ALL_ON_SCREEN");
            body.put("roomPrivacy", "ROOM_PRIVACY_FREE");
            JSONObject response = exchangeJson(API_BASE + "/api-room/api/v2/room", "POST", accessToken, body);
            String roomId = response.optString("roomId", "");
            if (roomId.isEmpty()) {
                throw new IOException("create-room: empty roomId");
            }
            return roomId;
        } catch (JSONException error) {
            throw new IOException("create-room: " + error.getMessage(), error);
        }
    }

    @NonNull
    private static JSONObject exchangeJson(
        @NonNull String urlValue,
        @NonNull String method,
        String bearerToken,
        JSONObject body
    ) throws IOException, JSONException {
        URL url = new URL(urlValue);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        if (bearerToken != null && !bearerToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        if (body != null) {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
        }
        int status = connection.getResponseCode();
        StringBuilder responseBuilder = new StringBuilder();
        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream(),
                    StandardCharsets.UTF_8
                )
            )
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                responseBuilder.append(line);
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException("wb.ru " + method + " " + urlValue + " → " + status + ": " + responseBuilder);
        }
        if (responseBuilder.length() == 0) {
            return new JSONObject();
        }
        return new JSONObject(responseBuilder.toString());
    }
}
