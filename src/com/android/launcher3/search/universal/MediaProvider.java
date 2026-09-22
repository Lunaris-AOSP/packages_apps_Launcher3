package com.android.launcher3.search.universal;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Process;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Size;

import com.android.launcher3.LauncherPrefs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MediaProvider implements SearchProvider {

    private static final String[] PROJECTION = {
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
    };

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_FILE;
    }

    public static final String[] PERMISSIONS = {
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
    };

    private static Drawable loadThumbnail(Context context, Uri uri) {
        try {
            Bitmap bitmap = context.getContentResolver()
                    .loadThumbnail(uri, new Size(256, 256), null);
            return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static boolean hasPermission(Context context) {
        for (String permission : PERMISSIONS) {
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_FILES.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.length() < 2) {
            return out;
        }
        if (!hasPermission(context)) {
            return SearchProvider.permissionRequest(context, getSource(),
                    com.android.launcher3.R.string.search_permission_files);
        }
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String selection = MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = {"%" + query + "%"};
        try (Cursor c = context.getContentResolver().query(collection, PROJECTION, selection,
                args, MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) {
                return out;
            }
            int idIndex = c.getColumnIndex(MediaStore.Files.FileColumns._ID);
            int nameIndex = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
            int mimeIndex = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE);
            int pathIndex = c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH);
            while (c.moveToNext() && out.size() < max) {
                String name = nameIndex < 0 ? null : c.getString(nameIndex);
                if (TextUtils.isEmpty(name)) {
                    continue;
                }
                Uri uri = ContentUris.withAppendedId(collection, c.getLong(idIndex));
                String mime = mimeIndex < 0 ? null : c.getString(mimeIndex);
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, TextUtils.isEmpty(mime) ? "*/*" : mime)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                UniversalSearchResult result = new UniversalSearchResult(
                        UniversalSearchResult.SOURCE_FILE, uri.toString(), name,
                        pathIndex < 0 ? null : c.getString(pathIndex),
                        intent, Process.myUserHandle(),
                        ShortcutProvider.score(query.toLowerCase(), name));
                if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/")
                        || mime.startsWith("audio/"))) {
                    result.icon = loadThumbnail(context, uri);
                    result.thumbnail = result.icon != null;
                }
                out.add(result);
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }
}
