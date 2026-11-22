package com.lanclip.sync;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.File;

/**
 * 本机 APK 安装助手（自更新用）。
 *
 * - Android 8.0+ (API 26+)：需要先申请「安装未知应用」权限（Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES）
 * - API 24+ 需用 FileProvider(content://) 打开安装器；API 19-23 可直接用 file://
 */
public final class InstallHelper {

    private static final String TAG = "LanClipInstall";

    /** 申请「安装未知应用」权限。返回 true 表示无需申请或已放行。 */
    public static boolean canInstall(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return activity.getPackageManager().canRequestPackageInstalls();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return activity.checkSelfPermission(android.Manifest.permission.REQUEST_INSTALL_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** 打开系统「安装未知应用」设置页（API 26+）。 */
    public static void openInstallSettings(Activity activity) {
        try {
            Uri packageUri = Uri.parse("package:" + activity.getPackageName());
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri);
            activity.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "open install settings failed", t);
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 发起系统安装流程。 */
    public static void promptInstall(Context context, File apkFile) {
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = LanClipFileProvider.getUriForFile(context, apkFile);
            } else {
                uri = Uri.fromFile(apkFile);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "prompt install failed", t);
        }
    }

    private InstallHelper() {
    }
}