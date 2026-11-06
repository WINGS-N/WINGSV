package wings.v.core;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import androidx.annotation.NonNull;

/** Телик от телефона отличает не размер, а то, что пальцем там хуй ткнёшь */
@SuppressWarnings({ "PMD.CommentRequired", "PMD.MethodArgumentCouldBeFinal", "PMD.OnlyOneReturn" })
public final class TvMode {

    private TvMode() {}

    /** Ниже этого железо давится украшениями, и кадры сыпятся нахуй */
    private static final long WEAK_RAM_BYTES = 2L * 1024L * 1024L * 1024L;

    /**
     * Железо - говно и красоту не вывозит. Не всякий телик дохлый: мощный пусть
     * переливается, а дешёвая коробка на анимациях давится насмерть
     */
    public static boolean isWeakDevice(@NonNull Context context) {
        ActivityManager manager = context.getApplicationContext().getSystemService(ActivityManager.class);
        if (manager == null) {
            return false;
        }
        // Производитель сам метит такие прошивки, пиздеть ему тут незачем
        if (manager.isLowRamDevice()) {
            return true;
        }
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        if (memory.totalMem > 0 && memory.totalMem < WEAK_RAM_BYTES) {
            return true;
        }
        return Runtime.getRuntime().availableProcessors() <= 2;
    }

    private static Boolean cameraPresent;

    /**
     * Телик заявляет camera.any и показывает устройство, но объектив там
     * EXTERNAL, а сканер берёт только фронт или тыл и падает нахуй. Так что
     * спрашиваем не фичу, а чем снимать
     */
    public static boolean hasCamera(@NonNull Context context) {
        if (cameraPresent != null) {
            return cameraPresent;
        }
        cameraPresent = Boolean.FALSE;
        CameraManager manager = context.getApplicationContext().getSystemService(CameraManager.class);
        if (manager == null) {
            return false;
        }
        try {
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing != CameraMetadata.LENS_FACING_EXTERNAL) {
                    cameraPresent = Boolean.TRUE;
                    break;
                }
            }
        } catch (CameraAccessException | RuntimeException ignored) {
            cameraPresent = Boolean.FALSE;
        }
        return cameraPresent;
    }

    /** Устройство управляется пультом, а не пальцем */
    public static boolean isTelevision(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        UiModeManager uiMode = appContext.getSystemService(UiModeManager.class);
        if (uiMode != null && uiMode.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        // Режим ставят не всегда, а эти признаки система не пиздит
        PackageManager packages = appContext.getPackageManager();
        if (packages == null) {
            return false;
        }
        if (packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return true;
        }
        return !packages.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }
}
