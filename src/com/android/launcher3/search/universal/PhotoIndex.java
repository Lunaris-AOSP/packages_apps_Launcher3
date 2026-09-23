package com.android.launcher3.search.universal;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class PhotoIndex {

    private static final String TAG = "PhotoIndex";
    private static final int JOB_ID = 0x5EA4C8;
    private static final int OPPORTUNISTIC_BATCH = 60;
    private static final long OPPORTUNISTIC_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final float RELATIVE_CUTOFF = 0.5f;

    private static final ExecutorService INDEXER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "photo-index");
        thread.setDaemon(true);
        return thread;
    });

    private static PhotoIndex sInstance;
    private static long sLastOpportunistic = -OPPORTUNISTIC_INTERVAL_MS;

    private final Context mContext;
    private final Helper mHelper;

    private PhotoIndex(Context context) {
        mContext = context.getApplicationContext();
        mHelper = new Helper(mContext);
    }

    static synchronized PhotoIndex get(Context context) {
        if (sInstance == null) {
            sInstance = new PhotoIndex(context);
        }
        return sInstance;
    }

    static void indexSoon(Context context) {
        synchronized (PhotoIndex.class) {
            long now = SystemClock.elapsedRealtime();
            if (now - sLastOpportunistic < OPPORTUNISTIC_INTERVAL_MS) {
                return;
            }
            sLastOpportunistic = now;
        }
        scheduleBulk(context);
        INDEXER.execute(() -> get(context).index(OPPORTUNISTIC_BATCH, () -> false));
    }

    private static void scheduleBulk(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) {
            return;
        }
        scheduler.schedule(new JobInfo.Builder(JOB_ID,
                new ComponentName(context, PhotoIndexJob.class))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .build());
    }

    static void runBulk(Context context, BooleanSupplier stopped, Runnable done) {
        INDEXER.execute(() -> {
            get(context).index(Integer.MAX_VALUE, stopped);
            done.run();
        });
    }

    List<Long> search(String query, int max) {
        Map<Long, Float> scores = null;
        for (String word : query.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (word.length() < 3) {
                continue;
            }
            String singular = word.endsWith("s") && word.length() > 3
                    ? word.substring(0, word.length() - 1) : word;
            Map<Long, Float> matches = new HashMap<>();
            try (Cursor c = mHelper.getReadableDatabase().rawQuery(
                    "SELECT photo_id, MAX(score) FROM terms WHERE term IN (?, ?) "
                            + "GROUP BY photo_id", new String[]{word, singular})) {
                while (c.moveToNext()) {
                    matches.put(c.getLong(0), c.getFloat(1));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Photo search failed", e);
                return new ArrayList<>();
            }
            if (scores == null) {
                scores = matches;
            } else {
                scores.keySet().retainAll(matches.keySet());
                scores.replaceAll((id, score) -> score + matches.get(id));
            }
        }
        if (scores == null) {
            return new ArrayList<>();
        }
        Map<Long, Float> ranked = scores;
        List<Long> ids = new ArrayList<>(ranked.keySet());
        ids.sort((a, b) -> Float.compare(ranked.get(b), ranked.get(a)));
        // Next to a clear match, the labeller's unsure guesses are mostly wrong; hide them.
        if (!ids.isEmpty()) {
            float cutoff = ranked.get(ids.get(0)) * RELATIVE_CUTOFF;
            ids.removeIf(id -> ranked.get(id) < cutoff);
        }
        return ids.size() > max ? new ArrayList<>(ids.subList(0, max)) : ids;
    }

    private void index(int budget, BooleanSupplier stopped) {
        if (!MediaProvider.hasPermission(mContext)) {
            return;
        }
        SQLiteDatabase db;
        Set<Long> done = new HashSet<>();
        try {
            db = mHelper.getWritableDatabase();
            try (Cursor c = db.query("photos", new String[]{"_id"}, null, null, null, null,
                    null)) {
                while (c.moveToNext()) {
                    done.add(c.getLong(0));
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Photo index unavailable", e);
            return;
        }
        Uri images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED};
        int labelled = 0;
        try (PhotoLabeler labeler = new PhotoLabeler(mContext);
                Cursor c = mContext.getContentResolver().query(images, projection, null, null,
                        MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c == null) {
                return;
            }
            while (c.moveToNext() && labelled < budget && !stopped.getAsBoolean()) {
                long id = c.getLong(0);
                if (done.contains(id)) {
                    continue;
                }
                long taken = c.isNull(1) ? c.getLong(2) * 1000 : c.getLong(1);
                Map<String, Float> terms;
                try {
                    Bitmap thumb = mContext.getContentResolver().loadThumbnail(
                            ContentUris.withAppendedId(images, id),
                            new Size(PhotoLabeler.SIZE, PhotoLabeler.SIZE), null);
                    terms = labeler.describe(thumb);
                } catch (IOException | RuntimeException e) {
                    // Unreadable or vanished; record it so it is not retried every pass.
                    terms = Map.of();
                }
                db.beginTransaction();
                try {
                    ContentValues photo = new ContentValues();
                    photo.put("_id", id);
                    photo.put("taken", taken);
                    db.insertWithOnConflict("photos", null, photo,
                            SQLiteDatabase.CONFLICT_REPLACE);
                    for (Map.Entry<String, Float> term : terms.entrySet()) {
                        ContentValues row = new ContentValues();
                        row.put("photo_id", id);
                        row.put("term", term.getKey());
                        row.put("score", term.getValue());
                        db.insert("terms", null, row);
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                labelled++;
            }
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            Log.w(TAG, "Photo labelling stopped", e);
        }
        Log.i(TAG, "Labelled " + labelled + " photos, " + done.size() + " already indexed");
    }

    private static class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            super(context, "search_photo_labels.db", null, 3);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE photos (_id INTEGER PRIMARY KEY, taken INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE terms (photo_id INTEGER NOT NULL, term TEXT NOT NULL, "
                    + "score REAL NOT NULL)");
            db.execSQL("CREATE INDEX terms_by_term ON terms (term)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Older versions scored tags wrongly; relabel everything rather than patch them.
            db.execSQL("DROP TABLE IF EXISTS photos");
            db.execSQL("DROP TABLE IF EXISTS terms");
            onCreate(db);
        }
    }
}
