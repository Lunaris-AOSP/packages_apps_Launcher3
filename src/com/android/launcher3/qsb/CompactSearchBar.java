package com.android.launcher3.qsb;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.Utilities;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.Themes;

import android.graphics.drawable.PaintDrawable;

public class CompactSearchBar extends FrameLayout
        implements Reorderable, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "CompactSearchBar";
    private static final String ACTION_GOOGLE_SEARCH = "google_search";
    private static final String ACTION_LENS = "lens";
    private static final String ACTION_MIC = "mic";
    private static final String ACTION_GEMINI = "gemini";

    public static final FloatProperty<CompactSearchBar> REVEAL_AMOUNT =
            new FloatProperty<CompactSearchBar>("revealAmount") {
                @Override
                public void setValue(CompactSearchBar bar, float amount) {
                    bar.setRevealAmount(amount);
                }

                @Override
                public Float get(CompactSearchBar bar) {
                    return bar.mRevealAmount;
                }
            };

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    private final Context mContext;
    private float mScaleForReorderBounce = 1f;

    private ImageView mActionIcon;
    private TextView mActionText;
    private View mInner;
    private ThemeManager.ThemeChangeListener mThemeChangeListener;

    private float mRevealAmount = 1f;

    public CompactSearchBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    public CompactSearchBar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mActionIcon = findViewById(R.id.gemini_icon);
        mActionText = findViewById(R.id.compact_search_bar_text);
        mInner = findViewById(R.id.compact_search_bar_inner);

        setIcon();
        setUpBackground();
        setUpClick();

        mThemeChangeListener = () -> {
            setIcon();
            setUpBackground();
        };
        ThemeManager.INSTANCE.get(mContext).addChangeListener(mThemeChangeListener);
        LauncherPrefs.getPrefs(mContext).registerOnSharedPreferenceChangeListener(this);
        
        setRevealAmount(mRevealAmount);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mThemeChangeListener != null) {
            ThemeManager.INSTANCE.get(mContext).removeChangeListener(mThemeChangeListener);
            mThemeChangeListener = null;
        }
        LauncherPrefs.getPrefs(mContext).unregisterOnSharedPreferenceChangeListener(this);
        setOnClickListener(null);
        if (mInner != null) {
            mInner.setOnClickListener(null);
            mInner.setBackground(null);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (LauncherPrefs.DOCK_THEME.getSharedPrefKey().equals(key)
                || LauncherPrefs.DOCK_MUSIC_SEARCH.getSharedPrefKey().equals(key)
                || LauncherPrefs.COMPACT_SEARCH_BAR_ACTION.getSharedPrefKey().equals(key)
                || LauncherPrefs.HOTSEAT_QSB_OPACITY.getSharedPrefKey().equals(key)
                || LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.getSharedPrefKey().equals(key)
                || LauncherPrefs.SEARCH_RADIUS_SIZE.getSharedPrefKey().equals(key)) {
            setIcon();
            setUpBackground();
        }
    }

    private void setIcon() {
        if (mActionIcon == null) return;
        boolean isThemed = LauncherPrefs.DOCK_THEME.get(mContext);
        switch (getAction()) {
            case ACTION_LENS:
                mActionIcon.setImageResource(isThemed
                        ? R.drawable.ic_lens_themed
                        : R.drawable.ic_lens_color);
                break;
            case ACTION_MIC:
                if (Utilities.isMusicSearchEnabled(mContext)) {
                    mActionIcon.setImageResource(isThemed
                            ? R.drawable.ic_music_themed
                            : R.drawable.ic_music_color);
                } else {
                    mActionIcon.setImageResource(isThemed
                            ? R.drawable.ic_mic_themed
                            : R.drawable.ic_mic_color);
                }
                break;
            case ACTION_GEMINI:
                mActionIcon.setImageResource(isThemed
                        ? R.drawable.ic_gemini_themed
                        : R.drawable.ic_gemini_color);
                break;
            case ACTION_GOOGLE_SEARCH:
            default:
                mActionIcon.setImageResource(isThemed
                        ? R.drawable.ic_super_g_themed
                        : R.drawable.ic_super_g_color);
                break;
        }
    }

    private String getAction() {
        return LauncherPrefs.COMPACT_SEARCH_BAR_ACTION.get(mContext);
    }

    private void setUpBackground() {
        if (mInner == null) return;

        float cornerRadius = getCornerRadius();
        int alphaValue = (LauncherPrefs.HOTSEAT_QSB_OPACITY.get(mContext) * 255) / 100;
        int baseColor = LauncherPrefs.DOCK_THEME.get(mContext)
                ? Themes.getAttrColor(mContext, R.attr.qsbFillColorThemed)
                : Themes.getAttrColor(mContext, R.attr.qsbFillColor);
        int color = Color.argb(alphaValue, Color.red(baseColor), Color.green(baseColor),
                Color.blue(baseColor));

        Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(color);
        backgroundPaint.setStyle(Paint.Style.FILL);

        float strokeWidth = LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.get(mContext);
        Paint strokePaint = null;
        if (strokeWidth != 0f) {
            strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setColor(Themes.getColorAccent(mContext));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(strokeWidth);
        }

        RevealDrawable revealDrawable = new RevealDrawable(backgroundPaint, strokePaint);
        mInner.setBackground(revealDrawable);
        mInner.setClipToOutline(cornerRadius > 0);
        revealDrawable.setRevealAmount(mRevealAmount);
    }

    public void setRevealAmount(float amount) {
        mRevealAmount = amount;
        if (mInner != null && mInner.getBackground() instanceof RevealDrawable) {
            ((RevealDrawable) mInner.getBackground()).setRevealAmount(amount);
        }

        if (mActionText != null) {
            mActionText.setAlpha(Utilities.boundToRange((amount - 0.5f) * 2f, 0f, 1f));
        }

        updateIconPosition();
    }

    private void updateIconPosition() {
        if (mActionIcon == null || mInner == null) return;

        float innerWidth = mInner.getWidth();
        if (innerWidth == 0) {
            mInner.post(this::updateIconPosition);
            return;
        }

        float iconWidth = mActionIcon.getWidth();
        float iconLeft = mActionIcon.getLeft();
        float iconCenterX = iconLeft + iconWidth / 2f;
        float barCenterX = innerWidth / 2f;

        float targetTranslationX = (barCenterX - iconCenterX) * (1f - mRevealAmount);
        mActionIcon.setTranslationX(targetTranslationX);
        if (mActionText != null) {
            mActionText.setTranslationX(targetTranslationX);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateIconPosition();
    }

    private class RevealDrawable extends Drawable {
        private final Paint mBackgroundPaint;
        private final Paint mStrokePaint;
        private float mAmount = 1f;

        public RevealDrawable(Paint backgroundPaint, Paint strokePaint) {
            mBackgroundPaint = backgroundPaint;
            mStrokePaint = strokePaint;
        }

        public void setRevealAmount(float amount) {
            mAmount = amount;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float sw = mStrokePaint != null ? mStrokePaint.getStrokeWidth() : 0;
            float inset = sw / 2f;

            float width = bounds.width();
            float height = bounds.height();

            float targetWidth = height + (width - height) * mAmount;
            float left = (width - targetWidth) / 2f;
            float right = left + targetWidth;

            float cr = getCornerRadius();
            // concentric corners: inner radius = outer radius - distance
            float drawCr = Math.max(0, cr - inset);
            
            RectF rectF = new RectF(left + inset, inset, right - inset, height - inset);
            
            canvas.drawRoundRect(rectF, drawCr, drawCr, mBackgroundPaint);

            if (mStrokePaint != null) {
                canvas.drawRoundRect(rectF, drawCr, drawCr, mStrokePaint);
            }
        }

        @Override
        public void getOutline(Outline outline) {
            Rect bounds = getBounds();
            float width = bounds.width();
            float height = bounds.height();

            float targetWidth = height + (width - height) * mAmount;
            float left = (width - targetWidth) / 2f;
            float right = left + targetWidth;
            
            outline.setRoundRect(Math.round(left), 0, Math.round(right), Math.round(height), getCornerRadius());
        }

        @Override
        public void setAlpha(int alpha) {
            mBackgroundPaint.setAlpha(alpha);
            if (mStrokePaint != null) mStrokePaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            mBackgroundPaint.setColorFilter(colorFilter);
            if (mStrokePaint != null) mStrokePaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return mBackgroundPaint.getAlpha() < 255 ? android.graphics.PixelFormat.TRANSLUCENT : android.graphics.PixelFormat.OPAQUE;
        }
    }

    private float getCornerRadius() {
        Resources res = mContext.getResources();
        return (res.getDimension(R.dimen.compact_search_bar_height) / 2f)
                * ((float) LauncherPrefs.SEARCH_RADIUS_SIZE.get(mContext) / 100f);
    }

    private void setUpClick() {
        View.OnClickListener listener = view -> launchAction();
        setOnClickListener(listener);
        if (mInner != null) {
            mInner.setOnClickListener(listener);
        }
    }

    private void launchAction() {
        switch (getAction()) {
            case ACTION_LENS:
                launchLens();
                break;
            case ACTION_MIC:
                launchMicOrMusicSearch();
                break;
            case ACTION_GEMINI:
                launchGemini();
                break;
            case ACTION_GOOGLE_SEARCH:
            default:
                launchSearchActivity();
                break;
        }
    }

    private void launchSearchActivity() {
        String searchPackage = QsbContainerView.getSearchWidgetPackageName(mContext);
        if (searchPackage == null) {
            Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            mContext.startActivity(new Intent("android.search.action.GLOBAL_SEARCH")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .setPackage(searchPackage));
            return;
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "GLOBAL_SEARCH not found for " + searchPackage);
        }

        try {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(searchPackage);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                mContext.startActivity(intent);
                return;
            }
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "Launch intent not found for " + searchPackage);
        }

        Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
    }

    private void launchLens() {
        try {
            mContext.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setComponent(new ComponentName(Utilities.GSA_PACKAGE, Utilities.LENS_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .setData(Uri.parse(Utilities.LENS_URI))
                    .putExtra("LensHomescreenShortcut", true));
        } catch (Exception e) {
            Log.e(TAG, "Lens launch failed", e);
            Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchMicOrMusicSearch() {
        try {
            Intent intent = new Intent();
            if (Utilities.isMusicSearchEnabled(mContext)) {
                intent.setAction("com.google.android.googlequicksearchbox.MUSIC_SEARCH");
                intent.setPackage(QsbContainerView.getSearchWidgetPackageName(mContext));
            } else {
                intent.setAction("android.intent.action.VOICE_COMMAND");
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mContext.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Mic launch failed", e);
            Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void launchGemini() {
        try {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(
                    Utilities.GEMINI_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                mContext.startActivity(intent);
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Gemini launch failed", e);
        }
        Toast.makeText(mContext, R.string.activity_not_found, Toast.LENGTH_SHORT).show();
    }

    @Override
    public MultiTranslateDelegate getTranslateDelegate() {
        return mTranslateDelegate;
    }

    @Override
    public void setReorderBounceScale(float scale) {
        mScaleForReorderBounce = scale;
        super.setScaleX(scale);
        super.setScaleY(scale);
    }

    @Override
    public float getReorderBounceScale() {
        return mScaleForReorderBounce;
    }
}
