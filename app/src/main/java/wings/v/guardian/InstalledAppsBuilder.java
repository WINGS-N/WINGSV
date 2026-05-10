package wings.v.guardian;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import wings.v.core.RuStoreRecommendedAppsAsset;
import wings.v.proto.GuardianProto;

public final class InstalledAppsBuilder {

    private static final int ICON_DIMEN_PX = 72;
    private static final int ICON_QUALITY = 100;

    private InstalledAppsBuilder() {}

    public static GuardianProto.InstalledApps build(Context context) {
        if (context == null) {
            return null;
        }
        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            return null;
        }
        List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        Set<String> recommendedNames = RuStoreRecommendedAppsAsset.getPackageNames(context);
        GuardianProto.InstalledApps.Builder out = GuardianProto.InstalledApps.newBuilder().setTsMs(
            System.currentTimeMillis()
        );
        for (ApplicationInfo info : all) {
            if (info == null || TextUtils.isEmpty(info.packageName)) {
                continue;
            }
            boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean wasUpdated = (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (isSystem && !wasUpdated) {
                continue;
            }
            CharSequence label = pm.getApplicationLabel(info);
            byte[] iconPng = renderIcon(pm, info);
            GuardianProto.InstalledApp.Builder app = GuardianProto.InstalledApp.newBuilder()
                .setPackageName(info.packageName)
                .setLabel(label == null ? info.packageName : label.toString())
                .setSystem(isSystem)
                .setRecommended(recommendedNames.contains(info.packageName));
            if (iconPng != null && iconPng.length > 0) {
                app.setIconPng(ByteString.copyFrom(iconPng));
            }
            out.addApps(app);
        }
        return out.build();
    }

    private static byte[] renderIcon(PackageManager pm, ApplicationInfo info) {
        try {
            Drawable drawable = pm.getApplicationIcon(info);
            if (drawable == null) {
                return null;
            }
            Bitmap bitmap = Bitmap.createBitmap(ICON_DIMEN_PX, ICON_DIMEN_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, ICON_DIMEN_PX, ICON_DIMEN_PX);
            drawable.draw(canvas);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, ICON_QUALITY, out);
            bitmap.recycle();
            return out.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }
}
