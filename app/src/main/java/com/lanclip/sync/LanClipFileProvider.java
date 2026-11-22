package com.lanclip.sync;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;

/**
 * 极简 FileProvider（不依赖 AndroidX）。
 * 用于 API 24+ 把下载的 APK 以 content:// 共享给系统安装器。
 *
 * 允许路径：外部存储目录 /Android/data/<包名>/files/ 下（downloads、files 子目录）。
 * authority = <applicationId>.fileprovider
 */
public class LanClipFileProvider extends ContentProvider {

    private static final String TAG = "LanClipFileProvider";
    private final Map<String, File> roots = new HashMap<>();

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) {
            return false;
        }
        // 只暴露应用专属目录，避免泄露其他文件
        addRoot("downloads", ctx.getExternalFilesDir("downloads"));
        addRoot("files", ctx.getExternalFilesDir(null));
        addRoot("internal", ctx.getFilesDir());
        return true;
    }

    private void addRoot(String name, File dir) {
        if (dir != null) {
            roots.put(name, dir);
        }
    }

    @Override
    public String getType(Uri uri) {
        if ("apk".equals(uri.getLastPathSegment())) {
            return "application/vnd.android.package-archive";
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        if (file == null) {
            throw new FileNotFoundException("无法解析 URI: " + uri);
        }
        int flags = ParcelFileDescriptor.MODE_READ_ONLY;
        if ("w".equals(mode) || "rw".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_WRITE_ONLY;
        }
        if ("rw".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_READ_WRITE;
        }
        return ParcelFileDescriptor.open(file, flags);
    }

    private File resolve(Uri uri) {
        java.util.List<String> segments = uri.getPathSegments();
        if (segments.size() < 2) {
            return null;
        }
        File root = roots.get(segments.get(0));
        if (root == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < segments.size(); i++) {
            if (i > 1) {
                sb.append('/');
            }
            sb.append(segments.get(i));
        }
        File file = new File(root, sb.toString());
        // 防止路径穿越
        if (!file.getAbsolutePath().startsWith(root.getAbsolutePath())) {
            return null;
        }
        return file;
    }

    /** 生成 content:// URI。 */
    public static Uri getUriForFile(Context context, File file) {
        String authority = context.getPackageName() + ".fileprovider";
        String root;
        String relative;
        File ext = context.getExternalFilesDir("downloads");
        if (ext != null && file.getAbsolutePath().startsWith(ext.getAbsolutePath())) {
            root = "downloads";
            relative = file.getAbsolutePath().substring(ext.getAbsolutePath().length() + 1);
        } else {
            File ext2 = context.getExternalFilesDir(null);
            if (ext2 != null && file.getAbsolutePath().startsWith(ext2.getAbsolutePath())) {
                root = "files";
                relative = file.getAbsolutePath().substring(ext2.getAbsolutePath().length() + 1);
            } else {
                root = "internal";
                relative = file.getAbsolutePath().substring(context.getFilesDir().getAbsolutePath().length() + 1);
            }
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .path(root + "/" + relative)
                .build();
    }

    // 以下方法未用到，留空实现
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        return null;
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        ParcelFileDescriptor pfd = openFile(uri, mode);
        return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }
}