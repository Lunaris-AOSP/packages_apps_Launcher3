package com.android.launcher3.util;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Log;

public final class FlashlightUtils {
    private FlashlightUtils() {}

    public static String getRearCameraId(CameraManager manager) throws CameraAccessException {
        if (manager == null) return null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            if (Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
                    && Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(
                            characteristics.get(CameraCharacteristics.LENS_FACING))) {
                return id;
            }
        }
        return null;
    }

    public static boolean setEnabled(Context context, boolean enabled) {
        CameraManager manager = context.getSystemService(CameraManager.class);
        try {
            String id = getRearCameraId(manager);
            if (id == null) return false;
            manager.setTorchMode(id, enabled);
            return true;
        } catch (CameraAccessException | RuntimeException e) {
            Log.w("LauncherFlashlight", "Cannot change flashlight state", e);
            return false;
        }
    }
}
