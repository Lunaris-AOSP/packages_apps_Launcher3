package com.android.launcher3.qsb;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.PaintDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Reorderable;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.util.MultiTranslateDelegate;
import com.android.launcher3.util.Themes;

public class CompactSearchBar extends FrameLayout
        implements Reorderable, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "CompactSearchBar";

    private final MultiTranslateDelegate mTranslateDelegate = new MultiTranslateDelegate(this);
    private final Context mContext;
    private float mScaleForReorderBounce = 1f;

    private ImageView mGeminiIcon;
    private View mInner;
    private ThemeManager.ThemeChangeListener mThemeChangeListener;

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

        mGeminiIcon = findViewById(R.id.gemini_icon);
        mInner = findViewById(R.id.compact_search_bar_inner);

        setIcons();
        setUpBackground();
        setUpSearchClick();

        mThemeChangeListener = () -> {
            setIcons();
            setUpBackground();
        };
        ThemeManager.INSTANCE.get(mContext).addChangeListener(mThemeChangeListener);
        LauncherPrefs.getPrefs(mContext).registerOnSharedPreferenceChangeListener(this);
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
                || LauncherPrefs.HOTSEAT_QSB_OPACITY.getSharedPrefKey().equals(key)
                || LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.getSharedPrefKey().equals(key)
                || LauncherPrefs.SEARCH_RADIUS_SIZE.getSharedPrefKey().equals(key)) {
            setIcons();
            setUpBackground();
        }
    }

    private void setIcons() {
        if (mGeminiIcon == null) return;
        boolean isThemed = LauncherPrefs.DOCK_THEME.get(mContext);
        mGeminiIcon.setImageResource(isThemed
                ? R.drawable.ic_gemini_themed
                : R.drawable.ic_gemini_color);
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

        PaintDrawable backgroundDrawable = new PaintDrawable(color);
        backgroundDrawable.setCornerRadius(cornerRadius);

        float strokeWidth = LauncherPrefs.HOTSEAT_QSB_STROKE_WIDTH.get(mContext);
        if (strokeWidth != 0f) {
            PaintDrawable strokeDrawable = new PaintDrawable(Themes.getColorAccent(mContext));
            strokeDrawable.getPaint().setStyle(Paint.Style.STROKE);
            strokeDrawable.getPaint().setStrokeWidth(strokeWidth);
            strokeDrawable.setCornerRadius(cornerRadius);
            mInner.setBackground(new LayerDrawable(new Drawable[]{backgroundDrawable, strokeDrawable}));
        } else {
            mInner.setBackground(backgroundDrawable);
        }
        mInner.setClipToOutline(cornerRadius > 0);
    }

    private float getCornerRadius() {
        Resources res = mContext.getResources();
        return (res.getDimension(R.dimen.compact_search_bar_height) / 2f)
                * ((float) LauncherPrefs.SEARCH_RADIUS_SIZE.get(mContext) / 100f);
    }

    private void setUpSearchClick() {
        View.OnClickListener listener = view -> launchSearchActivity();
        setOnClickListener(listener);
        if (mInner != null) {
            mInner.setOnClickListener(listener);
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
