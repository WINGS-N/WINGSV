package wings.v.core;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import wings.v.R;

public final class BrowserLauncher {

    private BrowserLauncher() {}

    public static void open(Context context, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(context, R.string.about_browser_missing, Toast.LENGTH_SHORT).show();
        }
    }
}
