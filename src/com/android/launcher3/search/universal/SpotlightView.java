/*
 * SPDX-FileCopyrightText: The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.search.universal;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.PowerManager;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.Patterns;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.CrossWindowBlurListeners;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.BaseAllAppsAdapter;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.views.BaseDragLayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Spotlight, opened from the compact search bar: a glass field and result card over home,
 * with suggested apps, a link on the clipboard and what's
 * playing before anything is typed, and universal search results once something is.
 */
public class SpotlightView extends AbstractFloatingView
        implements SearchCallback<AdapterItem>, TextWatcher {

    private static final int APPS_PER_ROW = 4;
    private static final int APP_CELL_WIDTH_DP = 104;
    private static final PathInterpolator EASE = new PathInterpolator(0.3f, 0f, 0.1f, 1f);

    private Launcher mHost;
    private UniversalSearchAlgorithm mSearch;
    private EditText mInput;
    private View mField;
    private View mScrim;
    private RecyclerView mResults;
    private View mCard;
    private View mPanel;
    private SpotlightGlass mPanelGlass;
    private SpotlightGlass mFieldGlass;
    private SpotlightGlass mCardGlass;
    private final Adapter mAdapter = new Adapter();
    // Compact windows show the field and the results as two glass shapes, as iOS does.
    private int mSplitState = -1;
    private android.graphics.Insets mBarInsets = android.graphics.Insets.NONE;
    private int mImeBottom;
    private boolean mHadWindowFocus;
    private int mHandledKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private ValueAnimator mRevealAnimator;
    private float mRevealProgress;
    private boolean mScreenReceiverRegistered;
    private final Consumer<Boolean> mBlurListener = enabled -> {
        mPanel.invalidate();
        mField.invalidate();
        mCard.invalidate();
    };
    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && mIsOpen) {
                close(false);
            }
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().registerReceiver(mScreenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED);
        mScreenReceiverRegistered = true;
        CrossWindowBlurListeners.getInstance().addListener(
                getContext().getMainExecutor(), mBlurListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mIsOpen && mSearch != null) mSearch.cancel(true);
        mIsOpen = false;
        CrossWindowBlurListeners.getInstance().removeListener(mBlurListener);
        mPanelGlass.release();
        mFieldGlass.release();
        mCardGlass.release();
        if (mScreenReceiverRegistered) {
            getContext().unregisterReceiver(mScreenReceiver);
            mScreenReceiverRegistered = false;
        }
        if (mRevealAnimator != null) mRevealAnimator.cancel();
        super.onDetachedFromWindow();
    }

    /** A single reversible clock keeps the field and unfolding results together. */
    private void revealPanel(boolean open) {
        if (mRevealAnimator != null) mRevealAnimator.cancel();
        ValueAnimator animator = ValueAnimator.ofFloat(mRevealProgress, open ? 1f : 0f);
        mRevealAnimator = animator;
        animator.setDuration(Math.round((open ? 420 : 280)
                * Math.abs((open ? 1f : 0f) - mRevealProgress)));
        animator.setInterpolator(EASE);
        animator.addUpdateListener(a -> {
            mRevealProgress = (float) a.getAnimatedValue();
            applyRevealProgress();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override
            public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mRevealAnimator == animation) mRevealAnimator = null;
                if (!cancelled) {
                    if (open) announceAccessibilityChanges();
                    else removeFromHost();
                }
            }
        });
        animator.start();
    }

    private void applyRevealProgress() {
        float t = mRevealProgress;
        // Compositor glass does not follow an offscreen View-alpha layer's rectangular crop.
        // Move intact rounded surfaces and fade their drawables directly instead.
        mPanel.setScaleX(1f);
        mPanel.setScaleY(1f);
        mPanel.setAlpha(1f);
        mPanel.setTranslationY(-Math.min(dp(24), Math.max(0, mPanel.getTop())) * (1f - t));
        mField.setAlpha(1f);
        mCard.setAlpha(1f);
        mCard.setTranslationY(0f);
        mCard.setClipBounds(null);
        int alpha = Math.round(255 * t);
        setGlassAlpha(mPanel, alpha);
        setGlassAlpha(mField, alpha);
        setGlassAlpha(mCard, alpha);
        ViewGroup field = (ViewGroup) mField;
        for (int i = 0; i < field.getChildCount(); i++) field.getChildAt(i).setAlpha(t);
        mResults.setAlpha(t);
        ViewGroup content = findViewById(R.id.spotlight_content);
        content.getChildAt(1).setAlpha(t);
        mScrim.setAlpha(t);
    }
    private static void setGlassAlpha(View view, int alpha) {
        if (view.getBackground() != null) view.getBackground().setAlpha(alpha);
    }

    /** Request code for dictating into the field; below Launcher's REQUEST_LAST. */
    public static final int REQUEST_DICTATION = 17;
    private boolean mCanDictate;
    // The recognizer's own window is up over Spotlight, which stays open for its words.
    private boolean mAwaitingDictation;

    public SpotlightView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Opens Spotlight above the home screen from the compact search bar. */
    public static void show(Launcher launcher) {
        BaseDragLayer<?> layer = launcher.getDragLayer();
        for (int i = layer.getChildCount() - 1; i >= 0; i--) {
            if (layer.getChildAt(i) instanceof SpotlightView old) {
                if (old.isOpen()) return;
                old.removeFromHost();
            }
        }
        SpotlightView view = (SpotlightView) LayoutInflater.from(launcher)
                .inflate(R.layout.spotlight_view, layer, false);
        view.setup(launcher);
        layer.addView(view);
        view.mIsOpen = true;
        view.requestApplyInsets();
        view.post(view::animateOpen);
    }

    private static Intent dictationIntent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    }

    /**
     * Puts the dictation into the open Spotlight field. Returns whether [requestCode] was its own.
     */
    public static boolean onActivityResult(Launcher host, int requestCode, int resultCode,
            Intent data) {
        if (requestCode != REQUEST_DICTATION) return false;
        BaseDragLayer<?> layer = host.getDragLayer();
        for (int i = layer.getChildCount() - 1; i >= 0; i--) {
            if (layer.getChildAt(i) instanceof SpotlightView view) {
                view.mAwaitingDictation = false;
                ArrayList<String> words = data == null ? null
                        : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (resultCode == Activity.RESULT_OK && view.isOpen() && words != null
                        && !words.isEmpty()) {
                    view.mInput.setText(words.get(0));
                    view.mInput.setSelection(view.mInput.length());
                }
                if (view.isOpen()) view.showKeyboardWhenReady();
                break;
            }
        }
        return true;
    }

    /**
     * Esc closes an open Spotlight whichever view has focus. Consume both edges of the press.
     */
    public static boolean handleEscape(Launcher host, KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_ESCAPE) return false;
        BaseDragLayer<?> layer = host.getDragLayer();
        for (int i = layer.getChildCount() - 1; i >= 0; i--) {
            if (layer.getChildAt(i) instanceof SpotlightView view && view.isOpen()) {
                if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                    view.close(true);
                }
                return true;
            }
        }
        return false;
    }

    private void setup(Launcher launcher) {
        mHost = launcher;
        mHadWindowFocus = launcher.hasWindowFocus();
        mScrim = findViewById(R.id.spotlight_scrim);
        mField = findViewById(R.id.spotlight_field);
        mInput = findViewById(R.id.spotlight_input);
        mResults = findViewById(R.id.spotlight_results);
        mCard = findViewById(R.id.spotlight_card);
        mPanel = findViewById(R.id.spotlight_panel);
        mPanelGlass = new SpotlightGlass(mPanel);
        mFieldGlass = new SpotlightGlass(mField);
        mCardGlass = new SpotlightGlass(mCard);
        findViewById(R.id.spotlight_clear).setOnClickListener(v -> mInput.setText(""));
        mCanDictate = launcher.getPackageManager().resolveActivity(dictationIntent(), 0) != null;
        View mic = findViewById(R.id.spotlight_mic);
        mic.setVisibility(mCanDictate ? VISIBLE : GONE);
        mic.setOnClickListener(v -> {
            try {
                mAwaitingDictation = true;
                mHost.startActivityForResult(dictationIntent(), REQUEST_DICTATION);
            } catch (ActivityNotFoundException | SecurityException e) {
                mAwaitingDictation = false;
            }
        });
        mResults.setLayoutManager(new LinearLayoutManager(launcher));
        mResults.setAdapter(mAdapter);
        mScrim.setBackgroundColor(0x08000000);

        mScrim.setOnClickListener(v -> close(true));
        mPanel.setOnClickListener(v -> { });
        mInput.addTextChangedListener(this);
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                return launchFirst();
            }
            return false;
        });

        mSearch = new UniversalSearchAlgorithm(launcher);
        mSearch.setSpotlight(true);
        mSearch.setQueryHandler(query -> {
            mInput.setText(query);
            mInput.setSelection(query.length());
        });
        mSearch.doSearch("", this);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mBarInsets = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        mImeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
        mPanel.requestLayout();
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mPanel == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        FrameLayout.LayoutParams panel = (FrameLayout.LayoutParams) mPanel.getLayoutParams();
        int width = MeasureSpec.getSize(widthMeasureSpec);
        panel.leftMargin = mBarInsets.left + dp(12);
        panel.rightMargin = mBarInsets.right + dp(12);
        int availableWidth = Math.max(1, width - panel.leftMargin - panel.rightMargin);
        int availableHeight = Math.max(0, MeasureSpec.getSize(heightMeasureSpec)
                - mBarInsets.top - mBarInsets.bottom - dp(24));
        boolean spacious = Math.min(availableWidth, availableHeight) >= dp(600);
        // Search sits at the top in every window, with the results below it and above the
        // keyboard. Compact windows span the width with a separate field and results card.
        setSplit(!spacious);
        panel.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        panel.topMargin = mBarInsets.top + dp(spacious ? 40 : 12);
        panel.bottomMargin = Math.max(mImeBottom,
                mBarInsets.bottom + dp(spacious ? 80 : 12)) + dp(12);
        panel.width = spacious ? Math.min(availableWidth, Math.min(dp(650),
                Math.max(dp(340), Math.round(width * 0.51f)))) : availableWidth;

        ViewGroup.MarginLayoutParams card = (ViewGroup.MarginLayoutParams) mCard.getLayoutParams();
        card.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        forcePanelMeasure();
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int available = Math.max(0, getMeasuredHeight() - panel.topMargin
                - panel.bottomMargin - mField.getMeasuredHeight()
                - (mSplitState == 1 ? card.topMargin : dp(1)));
        if (mCard.getMeasuredHeight() > available) {
            card.height = available;
            forcePanelMeasure();
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private void setSplit(boolean split) {
        int state = split ? 1 : 0;
        if (mSplitState == state) return;
        mSplitState = state;
        LinearLayout content = findViewById(R.id.spotlight_content);
        // The divider sits between the field and the card in a single panel.
        View divider = content.getChildAt(content.indexOfChild(mField) + 1);
        if (divider != null && divider != mCard) {
            divider.setVisibility(split ? GONE : VISIBLE);
        }
        mPanel.setBackground(split ? null : mPanelGlass);
        // A detached background can otherwise leave its compositor region visible.
        if (split) mPanelGlass.release();
        else {
            mFieldGlass.release();
            mCardGlass.release();
        }
        mPanel.setClipToOutline(!split);
        mPanel.setElevation(split ? 0 : dp(12));
        content.setPaddingRelative(split ? 0 : dp(16), 0, split ? 0 : dp(16), 0);
        mField.setBackground(split ? mFieldGlass : null);
        mField.setPaddingRelative(split ? dp(16) : 0, 0, split ? dp(4) : 0, 0);
        mCard.setBackground(split ? mCardGlass : null);
        mCard.setClipToOutline(split);
        mCard.setPaddingRelative(split ? dp(8) : 0, split ? dp(4) : 0, split ? dp(8) : 0, 0);
        ((ViewGroup.MarginLayoutParams) mCard.getLayoutParams()).topMargin = split ? dp(10) : 0;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (mPanel != null) applyRevealProgress();
    }

    private void forcePanelMeasure() {
        // The panel sits inside another FrameLayout. Its cached measurement must not
        // hide changes to our margins, width or the result card's height limit.
        for (View view = mCard; view != null && view != this;
                view = view.getParent() instanceof View parent ? parent : null) {
            view.forceLayout();
        }
    }

    private void showKeyboardWhenReady() {
        mInput.post(() -> {
            if (mIsOpen && isAttachedToWindow() && mInput.hasFocus() && hasWindowFocus()) {
                InputMethodManager input = getContext().getSystemService(InputMethodManager.class);
                if (input != null) input.showSoftInput(mInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void animateOpen() {
        if (!mIsOpen || !isAttachedToWindow()) return;
        applyRevealProgress();
        revealPanel(true);
        mInput.requestFocus();
        showKeyboardWhenReady();
    }

    @Override
    protected void handleClose(boolean animate) {
        mIsOpen = false;
        mSearch.cancel(true);
        InputMethodManager input = getContext().getSystemService(InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(getWindowToken(), 0);
        if (animate) revealPanel(false);
        else removeFromHost();
    }

    private void removeFromHost() {
        if (mRevealAnimator != null) mRevealAnimator.cancel();
        animate().cancel();
        mField.animate().cancel();
        mCard.animate().cancel();
        mInput.clearFocus();
        BaseDragLayer<?> layer = mHost.getDragLayer();
        announceAccessibilityChanges();
        layer.removeView(this);
        if (mHost.hasWindowFocus()) {
            layer.setFocusableInTouchMode(true);
            layer.requestFocus();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        PowerManager power = getContext().getSystemService(PowerManager.class);
        android.app.KeyguardManager keyguard =
                getContext().getSystemService(android.app.KeyguardManager.class);
        if (mIsOpen && ((power != null && !power.isInteractive())
                || (keyguard != null && keyguard.isKeyguardLocked()))) {
            post(() -> { if (mIsOpen) close(false); });
            return;
        }
        // Something opened from here took over the screen.
        if (hasWindowFocus) {
            mHadWindowFocus = true;
            mAwaitingDictation = false;
            if (mIsOpen && mInput != null && mInput.hasFocus()) {
                showKeyboardWhenReady();
                if (mInput.length() == 0) mSearch.doSearch("", this);
            }
        }
        if (!hasWindowFocus && mHadWindowFocus && mIsOpen && !mAwaitingDictation) {
            // Not while the window is still telling its views about the focus change.
            post(() -> {
                if (mIsOpen && !hasWindowFocus()) close(false);
            });
        }
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_SPOTLIGHT) != 0;
    }

    @Override
    protected Pair<View, String> getAccessibilityTarget() {
        return Pair.create(this, getContext().getString(
                mIsOpen ? R.string.universal_search_title : R.string.spotlight_closed));
    }

    @Override
    protected View getAccessibilityInitialFocusView() {
        return mInput;
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP && code == mHandledKeyCode) {
            mHandledKeyCode = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)
                && event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
            close(true);
            return true;
        }
        // Up and Down move a single highlight through the results. The text cursor stays in
        // the field, and Left and Right still move it unless an app row is being browsed.
        if (event.getAction() == KeyEvent.ACTION_DOWN && mInput.hasFocus()
                && mAdapter.handleKey(event)) {
            mHandledKeyCode = code;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean launchFirst() {
        List<Integer> rows = mAdapter.selectable();
        return !rows.isEmpty() && mAdapter.launchSelected(rows);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) { }

    @Override
    public void afterTextChanged(Editable s) {
        findViewById(R.id.spotlight_clear).setVisibility(mInput.length() == 0 ? GONE : VISIBLE);
        findViewById(R.id.spotlight_mic).setVisibility(
                mInput.length() == 0 && mCanDictate ? VISIBLE : GONE);
        mSearch.cancel(false);
        mSearch.doSearch(s.toString(), this);
    }

    @Override
    public void onSearchResult(String query, ArrayList<AdapterItem> items) {
        if (!query.equals(mInput.getText().toString()) || !mIsOpen) {
            return;
        }
        mAdapter.setRows(query, query.trim().isEmpty() ? suggestions(items) : rows(items));
    }

    @Override
    public void clearSearchResult() {
        mAdapter.setRows("", new ArrayList<>());
    }

    /**
     * Before anything is typed: four suggested apps, then a link on the clipboard and what's
     * playing. Spotlight does not display search-history rows.
     */
    private List<Row> suggestions(List<AdapterItem> items) {
        List<AppInfo> apps = new ArrayList<>();
        List<AdapterItem> rest = new ArrayList<>();
        for (AdapterItem item : items) {
            if (item.itemInfo != null && BaseAllAppsAdapter.isIconViewType(item.viewType)) {
                if (apps.size() < APPS_PER_ROW) apps.add(item.itemInfo);
            } else {
                rest.add(item);
            }
        }
        List<Row> rows = new ArrayList<>();
        if (!LauncherPrefs.SPOTLIGHT_SUGGESTIONS.get(getContext())) {
            // Suggestions off: keep only results supplied for this surface.
            rows.addAll(rows(rest));
            return rows;
        }
        if (!apps.isEmpty()) {
            rows.add(Row.header(getContext().getString(R.string.spotlight_suggestions)));
            rows.add(Row.apps(apps));
        }
        UniversalSearchResult link = clipboardLink();
        if (link != null) {
            rows.add(Row.header(getContext().getString(R.string.spotlight_clipboard_link)));
            rows.add(Row.result(link));
        }
        UniversalSearchResult playing = nowPlaying();
        if (playing != null) {
            rows.add(Row.header(getContext().getString(R.string.spotlight_now_playing)));
            rows.add(Row.result(playing));
        }
        rows.addAll(rows(rest));
        return rows;
    }

    private List<Row> rows(List<AdapterItem> items) {
        List<Row> rows = new ArrayList<>();
        List<AppInfo> apps = null;
        for (AdapterItem item : items) {
            if (item.itemInfo != null && BaseAllAppsAdapter.isIconViewType(item.viewType)) {
                if (apps == null || apps.size() == APPS_PER_ROW) {
                    apps = new ArrayList<>();
                    if (rows.isEmpty()) rows.add(Row.header(
                            getContext().getString(R.string.spotlight_top_hit)));
                    rows.add(Row.apps(apps));
                }
                apps.add(item.itemInfo);
                continue;
            }
            apps = null;
            if (item.viewType == BaseAllAppsAdapter.VIEW_TYPE_SEARCH_SECTION_HEADER) {
                rows.add(Row.header(UniversalSearchResults.getSectionTitle(
                        getContext(), item.searchSection)));
            } else if (item.searchResult != null) {
                rows.add(Row.result(item.searchResult));
            } else if (item.viewType == BaseAllAppsAdapter.VIEW_TYPE_SEARCH_FILTERS
                    || item.viewType == BaseAllAppsAdapter.VIEW_TYPE_SEARCH_THUMBNAILS) {
                rows.add(Row.item(item));
            } else if (item.viewType == BaseAllAppsAdapter.VIEW_TYPE_EMPTY_SEARCH) {
                rows.add(Row.empty(getContext().getString(R.string.spotlight_no_results,
                        mInput.getText())));
            }
        }
        return rows;
    }

    /** A web link the user copied, offered as iOS does, opening in the default browser. */
    private UniversalSearchResult clipboardLink() {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) {
            return null;
        }
        Matcher matcher = Patterns.WEB_URL.matcher(clip.getItemAt(0).getText());
        if (!matcher.find()) {
            return null;
        }
        String url = matcher.group();
        Uri uri = Uri.parse(url.contains("://") ? url : "https://" + url);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        UniversalSearchResult result = new UniversalSearchResult(UniversalSearchResult.SOURCE_WEB,
                "clipboard", getContext().getString(R.string.spotlight_open_link),
                uri.getHost() + uri.getPath(), intent, null, 0);
        PackageManager pm = getContext().getPackageManager();
        ResolveInfo browser = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (browser != null) {
            result.icon = browser.loadIcon(pm);
        }
        return result;
    }

    /** What's playing, or paused most recently, to go back to in one tap. */
    private UniversalSearchResult nowPlaying() {
        MediaSessionManager sessions = getContext().getSystemService(MediaSessionManager.class);
        if (sessions == null) {
            return null;
        }
        List<MediaController> controllers;
        try {
            controllers = sessions.getActiveSessions(null);
        } catch (SecurityException e) {
            return null;
        }
        for (MediaController controller : controllers) {
            if (!SearchProvider.isPackageSearchable(getContext(), controller.getPackageName())) {
                continue;
            }
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            if (metadata == null || state == null
                    || metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == null) {
                continue;
            }
            boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
            UniversalSearchResult result = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_QUICK_ACTION, "now_playing",
                    playing ? metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                            : getContext().getString(R.string.spotlight_play,
                                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE)),
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST), null, null, 0);
            result.packageName = controller.getPackageName();
            try {
                result.icon = getContext().getPackageManager()
                        .getApplicationIcon(controller.getPackageName());
            } catch (PackageManager.NameNotFoundException ignored) {
                // No icon; the row still works.
            }
            MediaController.TransportControls controls = controller.getTransportControls();
            result.onTap = () -> {
                if (playing) {
                    Intent launch = getContext().getPackageManager()
                            .getLaunchIntentForPackage(controller.getPackageName());
                    if (launch != null) mHost.startActivitySafely(mInput, launch, null);
                } else {
                    controls.play();
                    close(true);
                }
            };
            return result;
        }
        return null;
    }

    /** One line of the card: a row of apps, a section title, a result, or a drawer item. */
    private static final class Row {
        static final int APPS = 0;
        static final int HEADER = 1;
        static final int RESULT = 2;
        static final int FILTERS = 3;
        static final int THUMBNAILS = 4;
        static final int EMPTY = 5;

        int type;
        List<AppInfo> apps;
        CharSequence title;
        UniversalSearchResult result;
        AdapterItem item;

        static Row apps(List<AppInfo> apps) {
            Row row = new Row();
            row.type = APPS;
            row.apps = apps;
            return row;
        }

        static Row header(CharSequence title) {
            Row row = new Row();
            row.type = HEADER;
            row.title = title;
            return row;
        }

        static Row result(UniversalSearchResult result) {
            Row row = new Row();
            row.type = RESULT;
            row.result = result;
            return row;
        }

        static Row item(AdapterItem item) {
            Row row = new Row();
            row.type = item.viewType == BaseAllAppsAdapter.VIEW_TYPE_SEARCH_FILTERS
                    ? FILTERS : THUMBNAILS;
            row.item = item;
            return row;
        }

        static Row empty(CharSequence title) {
            Row row = new Row();
            row.type = EMPTY;
            row.title = title;
            return row;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** A section title as iOS sets it: bold, with a hairline under it. */
    private View headerView(ViewGroup parent) {
        LinearLayout header = new LinearLayout(parent.getContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setPadding(dp(16), dp(14), dp(16), dp(6));
        TextView title = new TextView(parent.getContext());
        title.setTag("title");
        title.setTextColor(0xE6FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title);
        View divider = new View(parent.getContext());
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.topMargin = dp(10);
        header.addView(divider, lp);
        return header;
    }

    private void applySpotlightTextColors(View view) {
        if (view instanceof TextView text) {
            text.setTextColor(view.getId() == R.id.search_result_subtitle
                    ? 0xD9FFFFFF : android.graphics.Color.WHITE);
            text.setHintTextColor(0xBBFFFFFF);
        }
        if (view instanceof ImageView icon && (view.getId() == R.id.search_result_call
                || view.getId() == R.id.search_result_message
                || view.getId() == R.id.search_result_remove)) {
            icon.setImageTintList(ColorStateList.valueOf(android.graphics.Color.WHITE));
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                applySpotlightTextColors(group.getChildAt(i));
            }
        }
    }

    private Drawable selectionOutline(View target) {
        return new Drawable() {
            private final android.graphics.Paint paint =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path shapePath = new android.graphics.Path();
            @Override public void draw(@NonNull Canvas canvas) {
                float density = getResources().getDisplayMetrics().density;
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * density);
                paint.setColor(0x99FFFFFF);
                if (target instanceof BubbleTextView icon && icon.getIcon() != null) {
                    android.graphics.Rect drawable = icon.getIcon().getBounds();
                    float size = drawable.width();
                    // The same shape mask as the icon renderer, with a one-dp breathing space.
                    com.android.launcher3.icons.IconShape shape =
                            com.android.launcher3.graphics.ThemeManager.INSTANCE.get(getContext())
                                    .getIconShapeData().getValue();
                    shapePath.set(shape.path);
                    android.graphics.Matrix matrix = new android.graphics.Matrix();
                    float edge = size + 2 * density;
                    matrix.setScale(edge / shape.pathSize, edge / shape.pathSize);
                    matrix.postTranslate((target.getWidth() - size) / 2f - density,
                            target.getPaddingTop() - density);
                    shapePath.transform(matrix);
                    canvas.drawPath(shapePath, paint);
                } else {
                    RectF bounds = new RectF(getBounds());
                    bounds.inset(density, density);
                    float radius = Math.min(16 * density, bounds.height() / 2f);
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    paint.setColor(0x24FFFFFF);
                    canvas.drawRoundRect(bounds, radius, radius, paint);
                    paint.setStyle(android.graphics.Paint.Style.STROKE);
                    paint.setColor(0x66FFFFFF);
                    canvas.drawRoundRect(bounds, radius, radius, paint);
                }
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(@Nullable android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private List<Row> mRows = new ArrayList<>();
        private String mQuery = "";
        /** Index into {@link #selectable()}, not into {@link #mRows}. */
        private int mSelected = 0;
        private int mSelectedApp = 0;
        private boolean mBrowsing;

        void setRows(String query, List<Row> rows) {
            String selectedKey = selectionKey();
            String selectedApp = selectionAppKey();
            boolean sameQuery = query.equals(mQuery);
            boolean browsing = mBrowsing;
            mQuery = query;
            mRows = rows;
            if (!sameQuery || !restoreSelection(selectedKey, selectedApp)) {
                mSelected = 0;
                mSelectedApp = 0;
                mBrowsing = false;
            } else {
                mBrowsing = browsing;
            }
            notifyDataSetChanged();
            mCard.setVisibility(rows.isEmpty() ? GONE : VISIBLE);
            if (mResults != null && !sameQuery) {
                mResults.scrollToPosition(0);
            } else if (mResults != null && mBrowsing) {
                List<Integer> selectable = selectable();
                if (!selectable.isEmpty()) {
                    mResults.scrollToPosition(
                            selectable.get(Math.min(mSelected, selectable.size() - 1)));
                }
            }
        }

        /** Keeps the highlight on the same result when a later batch arrives for this query. */
        private boolean restoreSelection(String key, String appKey) {
            if (key == null) {
                return false;
            }
            List<Integer> rows = selectable();
            for (int i = 0; i < rows.size(); i++) {
                Row row = mRows.get(rows.get(i));
                if (row.type == Row.RESULT && row.result != null
                        && key.equals(resultKey(row.result))) {
                    mSelected = i;
                    mSelectedApp = 0;
                    return true;
                }
                if (row.type == Row.APPS && "apps".equals(key) && row.apps != null) {
                    if (appKey == null) {
                        mSelected = i;
                        mSelectedApp = 0;
                        return true;
                    }
                    for (int a = 0; a < row.apps.size(); a++) {
                        if (appKey.equals(SearchHistory.appKey(
                                row.apps.get(a).getTargetComponent(), row.apps.get(a).user))) {
                            mSelected = i;
                            mSelectedApp = a;
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private String selectionKey() {
            List<Integer> rows = selectable();
            if (rows.isEmpty()) {
                return null;
            }
            Row row = mRows.get(rows.get(Math.min(mSelected, rows.size() - 1)));
            if (row.type == Row.RESULT && row.result != null) {
                return resultKey(row.result);
            }
            if (row.type == Row.APPS) {
                return "apps";
            }
            return null;
        }

        private String selectionAppKey() {
            List<Integer> rows = selectable();
            if (rows.isEmpty()) {
                return null;
            }
            Row row = mRows.get(rows.get(Math.min(mSelected, rows.size() - 1)));
            if (row.type != Row.APPS || row.apps == null || row.apps.isEmpty()) {
                return null;
            }
            AppInfo app = row.apps.get(Math.min(mSelectedApp, row.apps.size() - 1));
            return SearchHistory.appKey(app.getTargetComponent(), app.user);
        }

        private static String resultKey(UniversalSearchResult result) {
            return result.source + ":" + result.id + "#" + result.user;
        }

        /**
         * Desktop movement: Up and Down walk results, Left and Right walk the suggested apps
         * once that row is reached, and Enter opens the highlighted one.
         */
        boolean handleKey(KeyEvent event) {
            int code = event.getKeyCode();
            List<Integer> rows = selectable();
            if (rows.isEmpty()) {
                return false;
            }
            boolean ctrl = event.isCtrlPressed();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN
                    || (code == KeyEvent.KEYCODE_TAB && !event.isShiftPressed())
                    || (ctrl && (code == KeyEvent.KEYCODE_N || code == KeyEvent.KEYCODE_J))) {
                move(1, rows);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP
                    || (code == KeyEvent.KEYCODE_TAB && event.isShiftPressed())
                    || (ctrl && (code == KeyEvent.KEYCODE_P || code == KeyEvent.KEYCODE_K))) {
                move(-1, rows);
                return true;
            }
            if (code == KeyEvent.KEYCODE_PAGE_DOWN) {
                move(5, rows);
                return true;
            }
            if (code == KeyEvent.KEYCODE_PAGE_UP) {
                move(-5, rows);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return moveApp(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, rows);
            }
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                // A held Enter must not open the result again and again.
                return event.getRepeatCount() > 0 || launchSelected(rows);
            }
            return false;
        }

        private void move(int delta, List<Integer> rows) {
            int next = Math.max(0, Math.min(rows.size() - 1, mSelected + delta));
            if (delta < 0 && mSelected == 0 && mBrowsing) {
                // Up from the first row hands the keys back to the text field.
                mBrowsing = false;
                notifyDataSetChanged();
                return;
            }
            mBrowsing = true;
            if (next != mSelected) {
                // Keep the column when stepping between rows of suggested apps.
                Row row = mRows.get(rows.get(next));
                mSelectedApp = row.type == Row.APPS && row.apps != null && !row.apps.isEmpty()
                        ? Math.min(mSelectedApp, row.apps.size() - 1) : 0;
            }
            mSelected = next;
            notifyDataSetChanged();
            mResults.scrollToPosition(rows.get(mSelected));
        }

        /** @return true when this key should not also move the text cursor */
        private boolean moveApp(int delta, List<Integer> rows) {
            Row row = mRows.get(rows.get(Math.min(mSelected, rows.size() - 1)));
            if (row.type != Row.APPS || row.apps == null || row.apps.isEmpty()) {
                return false;
            }
            // Once browsing, or with nothing typed, the arrows walk the apps. While typing,
            // Right at the end of the text also steps into them so a query need not be left.
            int len = mInput.length();
            boolean atEnd = mInput.getSelectionStart() == len && mInput.getSelectionEnd() == len;
            if (!mBrowsing && len > 0 && !(delta > 0 && atEnd)) {
                return false;
            }
            int next = Math.max(0, Math.min(row.apps.size() - 1, mSelectedApp + delta));
            mBrowsing = true;
            mSelectedApp = next;
            notifyDataSetChanged();
            return true;
        }

        private boolean launchSelected(List<Integer> rows) {
            if (!mQuery.equals(mInput.getText().toString())) return false;
            Row row = mRows.get(rows.get(Math.min(mSelected, rows.size() - 1)));
            if (row.type == Row.APPS && row.apps != null && !row.apps.isEmpty()) {
                AppInfo app = row.apps.get(Math.min(mSelectedApp, row.apps.size() - 1));
                SearchHistory.recordLaunch(getContext(), SearchHistory.appKey(
                        app.getTargetComponent(), app.user));
                return mHost.startActivitySafely(mInput, app.getIntent(), app) != null;
            }
            if (row.result != null) {
                return UniversalSearchResults.launch(mHost, mInput, row.result);
            }
            return false;
        }

        private List<Integer> selectable() {
            List<Integer> rows = new ArrayList<>();
            for (int i = 0; i < mRows.size(); i++) {
                Row row = mRows.get(i);
                if (row.type == Row.APPS || row.type == Row.RESULT) {
                    rows.add(i);
                }
            }
            return rows;
        }

        private boolean isHighlighted(int position) {
            List<Integer> rows = selectable();
            return !rows.isEmpty() && rows.get(Math.min(mSelected, rows.size() - 1)) == position;
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            // The card is dark glass whatever the home screen's theme, so its rows are too.
            LayoutInflater inflater = LayoutInflater.from(new ContextThemeWrapper(
                    parent.getContext(), android.R.style.Theme_DeviceDefault));
            View view = switch (type) {
                case Row.APPS -> {
                    int cellWidth = dp(APP_CELL_WIDTH_DP);
                    LinearLayout row = new LinearLayout(parent.getContext()) {
                        @Override
                        protected void onMeasure(int widthSpec, int heightSpec) {
                            int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft()
                                    - getPaddingRight();
                            int width = Math.max(1, Math.min(cellWidth, available / APPS_PER_ROW));
                            for (int i = 0; i < getChildCount(); i++) {
                                getChildAt(i).getLayoutParams().width = width;
                            }
                            super.onMeasure(widthSpec, heightSpec);
                        }
                    };
                    row.setLayoutParams(new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    // A compact, left-aligned row of fixed-width cells lined up with the
                    // section titles, instead of four icons stretched across the whole card.
                    row.setGravity(android.view.Gravity.START);
                    row.setPadding(dp(4), dp(4), dp(4), dp(4));
                    // Icon + label + a little air: the drawer's cell height leaves a big gap
                    // above the icons here, since they centre themselves vertically.
                    var allApps = mHost.getDeviceProfile().getAllAppsProfile();
                    int cellHeight = Math.min(allApps.getCellHeightPx(),
                            allApps.getIconSizePx() + allApps.getIconDrawablePaddingPx()
                                    + Utilities.calculateTextHeight(allApps.getIconTextSizePx())
                                    + dp(20));
                    for (int i = 0; i < APPS_PER_ROW; i++) {
                        BubbleTextView icon = (BubbleTextView) inflater.inflate(
                                R.layout.all_apps_icon, row, false);
                        row.addView(icon, new LinearLayout.LayoutParams(cellWidth, cellHeight));
                    }
                    yield row;
                }
                case Row.HEADER -> headerView(parent);
                case Row.EMPTY -> {
                    TextView empty = new TextView(parent.getContext());
                    empty.setLayoutParams(new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    empty.setPadding(dp(16), dp(20), dp(16), dp(20));
                    empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    yield empty;
                }
                case Row.FILTERS -> inflater.inflate(R.layout.search_filters, parent, false);
                case Row.THUMBNAILS -> inflater.inflate(R.layout.search_thumbnails, parent, false);
                default -> {
                    View result = inflater.inflate(R.layout.search_result_row, parent, false);
                    android.graphics.drawable.GradientDrawable mask =
                            new android.graphics.drawable.GradientDrawable();
                    mask.setColor(android.graphics.Color.WHITE);
                    mask.setCornerRadius(dp(16));
                    result.setBackground(new android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(0x26FFFFFF), null, mask));
                    yield result;
                }
            };
            return new RecyclerView.ViewHolder(view) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = mRows.get(position);
            switch (row.type) {
                case Row.APPS -> {
                    ViewGroup group = (ViewGroup) holder.itemView;
                    boolean rowHighlighted = isHighlighted(position);
                    for (int i = 0; i < group.getChildCount(); i++) {
                        BubbleTextView icon = (BubbleTextView) group.getChildAt(i);
                        icon.reset();
                        icon.setFocusable(false);
                        if (i >= row.apps.size()) {
                            icon.setVisibility(INVISIBLE);
                            icon.setForeground(null);
                            continue;
                        }
                        AppInfo app = row.apps.get(i);
                        icon.setVisibility(VISIBLE);
                        icon.applyFromApplicationInfo(app);
                        icon.setTextColor(0xFFFFFFFF);
                        icon.setForeground(rowHighlighted && i == mSelectedApp
                                ? selectionOutline(icon) : null);
                        icon.setOnClickListener(v -> {
                            SearchHistory.recordLaunch(v.getContext(), SearchHistory.appKey(
                                    app.getTargetComponent(), app.user));
                            mHost.startActivitySafely(v, app.getIntent(), app);
                        });
                    }
                }
                case Row.HEADER -> ((TextView) holder.itemView.findViewWithTag("title"))
                        .setText(row.title);
                case Row.EMPTY -> ((TextView) holder.itemView).setText(row.title);
                case Row.RESULT -> {
                    UniversalSearchResults.bind(mHost, holder.itemView, row.result);
                    holder.itemView.setFocusable(false);
                    holder.itemView.setForeground(isHighlighted(position)
                            ? selectionOutline(holder.itemView) : null);
                    // A touch larger than the drawer's rows, at the system's body size.
                    TextView title = holder.itemView.findViewById(R.id.search_result_title);
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    View icon = holder.itemView.findViewById(R.id.search_result_icon);
                    int size = dp(40);
                    icon.getLayoutParams().width = size;
                    icon.getLayoutParams().height = size;
                }
                case Row.FILTERS -> UniversalSearchResults.bindFilters(
                        holder.itemView, row.item.searchFilters);
                case Row.THUMBNAILS -> UniversalSearchResults.bindThumbnails(
                        mHost, holder.itemView, row.item.searchThumbnails);
            }
            applySpotlightTextColors(holder.itemView);
        }
    }
}
