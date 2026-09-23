package com.android.launcher3.search.universal;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Size;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PhotoProvider implements SearchProvider {

    private static final String GOOGLE_PHOTOS = "com.google.android.apps.photos";
    private static final int THUMB_PX = 256;

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_PHOTO;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_PHOTOS.get(context) && MediaProvider.hasPermission(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        String trimmed = query.trim();
        PhotoIndex.indexSoon(context);
        if (trimmed.length() < 3) {
            return out;
        }
        Uri images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        for (long id : PhotoIndex.get(context).search(trimmed, max)) {
            Uri uri = ContentUris.withAppendedId(images, id);
            Bitmap bitmap;
            try {
                bitmap = context.getContentResolver().loadThumbnail(
                        uri, new Size(THUMB_PX, THUMB_PX), null);
            } catch (IOException | RuntimeException e) {
                // Deleted since it was labelled.
                continue;
            }
            UniversalSearchResult result = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_PHOTO, uri.toString(), "", null,
                    new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    Process.myUserHandle(), 70);
            result.icon = new BitmapDrawable(context.getResources(), bitmap);
            result.thumbnail = true;
            out.add(result);
        }
        if (!out.isEmpty() || PhotoLabeler.isCategoryWord(trimmed)) {
            UniversalSearchResult cloud = googlePhotos(context, trimmed);
            if (cloud != null) {
                out.add(cloud);
            }
        }
        return out;
    }

    private static UniversalSearchResult googlePhotos(Context context, String query) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://photos.google.com/search/" + Uri.encode(query)))
                .setPackage(GOOGLE_PHOTOS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pm.resolveActivity(intent, 0) == null) {
            return null;
        }
        UniversalSearchResult result = new UniversalSearchResult(
                UniversalSearchResult.SOURCE_PHOTO, "google_photos",
                context.getString(R.string.search_action_in_google_photos, query), null,
                intent, Process.myUserHandle(), 0);
        result.packageName = GOOGLE_PHOTOS;
        try {
            result.icon = pm.getApplicationIcon(GOOGLE_PHOTOS);
        } catch (PackageManager.NameNotFoundException e) {
            result.icon = null;
        }
        return result;
    }
}
