package com.android.launcher3.smartspace;

import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.os.Handler;
import android.os.Looper;
import android.os.CancellationSignal;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.RemoteViews;

import com.android.launcher3.PagedView;
import com.android.launcher3.R;
import com.android.launcher3.pageindicators.PageIndicatorDots;
import com.android.launcher3.quickspace.receivers.QuickSpaceActionReceiver;
import com.android.launcher3.util.Themes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class SmartspaceView extends LinearLayout {
    private static final String HOME_PAGE = "date_weather";
    private final SmartspaceController mController;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mApplyCards = this::applyPendingCards;
    private final Runnable mTickClock = this::tickClock;
    private final SmartspacePager mPager;
    private final PageIndicatorDots mDots;
    private final View mHomePage;
    private final TextView mDate;
    private final TextView mWeather;
    private final Map<String, EventPage> mEventPages = new LinkedHashMap<>();
    private final List<String> mPageKeys = new ArrayList<>();
    private final Set<String> mKnownEvents = new HashSet<>();
    private final Set<String> mResumingEvents = new HashSet<>();
    private List<SmartspaceCard> mPendingCards;
    private SmartspaceCard mWeatherCard;
    private SmartspaceCard mDisplayed;
    private String mSelectedKey = HOME_PAGE;
    private boolean mActive;
    private boolean mUpdatingPages;
    private int mWeatherGeneration;

    SmartspaceView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int padding = dp(16);
        setPaddingRelative(padding, 0, padding, 0);
        mDots = new PageIndicatorDots(context);
        mDots.setPaintColor(Themes.getAttrColor(context, R.attr.workspaceTextColor));
        mDots.setShouldAutoHide(true);
        mDots.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mPager = new SmartspacePager(context, mDots);
        mPager.setSaveEnabled(false);
        addView(mPager, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        LayoutParams dotsParams = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(12));
        dotsParams.gravity = Gravity.START;
        addView(mDots, dotsParams);
        mHomePage = LayoutInflater.from(context).inflate(R.layout.smartspace_view, mPager, false);
        mDate = mHomePage.findViewById(R.id.smartspace_date);
        mWeather = mHomePage.findViewById(R.id.smartspace_weather);
        mDate.setOnClickListener(QuickSpaceActionReceiver.getCalendarAction());
        mPageKeys.add(HOME_PAGE);
        mPager.addView(mHomePage);
        mDots.setVisibility(GONE);
        mController = new SmartspaceController(context, this::render);
        mPager.addPageSwitchListener(() -> {
            if (mUpdatingPages || mPager.hasPendingPage()) return;
            int page = mPager.getCurrentPage();
            if (page >= 0 && page < mPageKeys.size()) mSelectedKey = mPageKeys.get(page);
            updateVisiblePage();
        });
        bindWeather(null);
    }

    void setActive(boolean active) {
        if (mActive == active) return;
        mActive = active;
        if (active) {
            mResumingEvents.clear();
            mResumingEvents.addAll(mKnownEvents);
        } else {
            if (mDisplayed != null) {
                mController.notifyEvent(mDisplayed, SmartspaceTargetEvent.EVENT_TARGET_HIDDEN);
            }
            mDisplayed = null;
            mPendingCards = null;
            mWeatherGeneration++;
            mPager.stopScrolling();
            mDots.skipAnimationsToEnd();
            mHandler.removeCallbacksAndMessages(null);
            for (EventPage page : mEventPages.values()) page.release();
        }
        mController.setActive(active);
    }

    void refreshSettings() {
        boolean active = mActive;
        setActive(false);
        mResumingEvents.clear();
        mKnownEvents.clear();
        mPendingCards = List.of();
        applyPendingCards();
        setActive(active);
    }

    @Override
    protected void onDetachedFromWindow() {
        setActive(false);
        super.onDetachedFromWindow();
    }

    private void render(List<SmartspaceCard> cards) {
        boolean scheduled = mPendingCards != null;
        mPendingCards = new ArrayList<>(cards);
        if (!scheduled) mHandler.postDelayed(mApplyCards, 100);
    }

    private void applyPendingCards() {
        if (mPendingCards == null || mPager.isBusy()) return;
        List<SmartspaceCard> cards = mPendingCards;
        mPendingCards = null;
        SmartspaceCard weather = null;
        Map<String, EventPage> pages = new LinkedHashMap<>();
        String newEvent = null;
        for (SmartspaceCard card : cards) {
            if (card.remoteViews == null && card.event == SmartspaceEvent.WEATHER && (card.target == null
                    || card.target.getFeatureType() == SmartspaceTarget.FEATURE_WEATHER)) {
                if (weather == null) weather = card;
                continue;
            }
            String key = card.event.name() + ":" + card.id;
            if (pages.containsKey(key)) continue;
            if (newEvent == null && !mKnownEvents.contains(key) && !mResumingEvents.contains(key)) {
                newEvent = key;
            }
            EventPage page = mEventPages.get(key);
            if (page == null) page = new EventPage();
            page.bind(card);
            pages.put(key, page);
        }
        mKnownEvents.clear();
        mKnownEvents.addAll(pages.keySet());
        mResumingEvents.removeAll(pages.keySet());
        for (Map.Entry<String, EventPage> entry : mEventPages.entrySet()) {
            if (!pages.containsKey(entry.getKey())) entry.getValue().release();
        }
        mEventPages.clear();
        mEventPages.putAll(pages);
        bindWeather(weather);
        List<String> keys = new ArrayList<>();
        keys.add(HOME_PAGE);
        keys.addAll(pages.keySet());
        boolean changed = !keys.equals(mPageKeys);
        if (newEvent != null) mSelectedKey = newEvent;
        int selected = keys.indexOf(mSelectedKey);
        if (selected < 0) {
            selected = 0;
            if (!mResumingEvents.contains(mSelectedKey)) {
                selected = keys.size() > 1 ? 1 : 0;
                mSelectedKey = keys.get(selected);
            }
        }
        mUpdatingPages = true;
        if (changed) {
            mPager.stopScrolling();
            mPager.removeAllViews();
            mPageKeys.clear();
            mPageKeys.addAll(keys);
            mPager.addView(mHomePage);
            for (EventPage page : pages.values()) mPager.addView(page.root);
        }
        boolean hasMultiplePages = keys.size() > 1;
        mDots.setVisibility(hasMultiplePages ? VISIBLE : GONE);
        if (hasMultiplePages && (changed || newEvent != null)) {
            // A newly-created indicator starts a fresh idle timeout. PageIndicatorDots also
            // restarts this timeout after a user scrolls.
            mDots.setShouldAutoHide(false);
            mHandler.postDelayed(() -> {
                if (mDots.getVisibility() == VISIBLE) mDots.setShouldAutoHide(true);
            }, 100);
        }
        if (changed || newEvent != null || selected != mPager.getNextPage()) {
            mPager.selectPage(selected, mActive && newEvent != null);
        }
        mUpdatingPages = false;
        updateVisiblePage();
    }

    private void bindWeather(SmartspaceCard card) {
        DateFormat format = DateFormat.getInstanceForSkeleton("EEEMMMd");
        format.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
        mDate.setText(format.format(System.currentTimeMillis()));
        if (mWeatherCard == card && mWeather.getVisibility() == (card == null ? GONE : VISIBLE)) {
            return;
        }
        mWeatherCard = card;
        int generation = ++mWeatherGeneration;
        CharSequence weather = card == null ? null : card.title;
        if (card != null && !TextUtils.isEmpty(card.subtitle)
                && !TextUtils.equals(card.title, card.subtitle)) {
            weather = getContext().getString(R.string.smartspace_weather_with_conditions,
                    card.title, card.subtitle);
        }
        mWeather.setText(weather);
        mWeather.setVisibility(card == null ? GONE : VISIBLE);
        bindAction(mWeather, card);
        mWeather.setCompoundDrawablesRelative(null, null, null, null);
        if (card != null) {
            loadIcon(card, drawable -> {
                if (generation != mWeatherGeneration) return;
                if (drawable != null) drawable.setBounds(0, 0, dp(22), dp(22));
                mWeather.setCompoundDrawablesRelative(drawable, null, null, null);
            });
        }
    }

    private void updateVisiblePage() {
        if (mPager.hasPendingPage()) return;
        int selected = mPager.getCurrentPage();
        if (selected < 0 || selected >= mPageKeys.size()) return;
        SmartspaceCard displayed = selected == 0 ? mWeatherCard
                : mEventPages.get(mPageKeys.get(selected)).card;
        if (mActive && (mDisplayed == null || displayed == null
                || !mDisplayed.id.equals(displayed.id))) {
            if (mDisplayed != null) {
                mController.notifyEvent(mDisplayed, SmartspaceTargetEvent.EVENT_TARGET_HIDDEN);
            }
            if (displayed != null) {
                mController.notifyEvent(displayed, SmartspaceTargetEvent.EVENT_TARGET_SHOWN);
            }
        }
        mDisplayed = mActive ? displayed : null;
        mHandler.removeCallbacks(mTickClock);
        tickClock();
        for (int i = 1; i < mPageKeys.size(); i++) {
            EventPage page = mEventPages.get(mPageKeys.get(i));
            if (mActive && (Math.abs(i - selected) <= 1
                    || Math.abs(i - mPager.getNextPage()) <= 1)) page.loadContent();
            else page.release();
        }
    }

    private void tickClock() {
        if (!mActive) return;
        EventPage page = mEventPages.get(mSelectedKey);
        if (page == null || page.card.clock == null) return;
        page.updateClockText();
        if (page.card.clock.running) mHandler.postDelayed(mTickClock, 1000);
    }

    private void bindAction(View view, SmartspaceCard card) {
        view.setOnClickListener(card == null || card.action == null ? null : clicked -> {
            if (!mActive || !card.event.isEnabled(getContext())
                    || (card.expiresAt > 0 && card.expiresAt <= System.currentTimeMillis())) return;
            card.action.onClick(clicked);
            mController.notifyEvent(card, SmartspaceTargetEvent.EVENT_TARGET_INTERACTION);
        });
        view.setClickable(card != null && card.action != null);
        view.setFocusable(card != null && card.action != null);
    }

    private void loadIcon(SmartspaceCard card, Consumer<Drawable> callback) {
        if (card.icon == null) {
            callback.accept(card.drawable);
            return;
        }
        card.icon.loadDrawableAsync(getContext(), drawable -> {
            if (!mActive) return;
            if (drawable != null && card.target == null && card.event == SmartspaceEvent.FLASHLIGHT) {
                drawable = drawable.mutate();
                drawable.setTint(Themes.getAttrColor(getContext(), R.attr.workspaceTextColor));
            }
            callback.accept(drawable);
        }, mHandler);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class EventPage {
        final FrameLayout root = new FrameLayout(getContext());
        final View content = LayoutInflater.from(getContext())
                .inflate(R.layout.smartspace_event_page, root, false);
        final FrameLayout remote = new FrameLayout(getContext());
        final TextView title = content.findViewById(R.id.smartspace_title);
        final TextView subtitle = content.findViewById(R.id.smartspace_subtitle);
        final TextView details = content.findViewById(R.id.smartspace_details);
        final View text = content.findViewById(R.id.smartspace_text);
        final ImageView icon = content.findViewById(R.id.smartspace_icon);
        SmartspaceCard card;
        boolean iconLoaded;
        int iconGeneration;
        int remoteGeneration;
        RemoteViews requestedRemote;
        RemoteViews appliedRemote;
        CancellationSignal remoteLoad;

        EventPage() {
            root.setLayoutParams(new ViewGroup.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            root.addView(content, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            root.addView(remote, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            remote.setVisibility(GONE);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(9));
                }
            });
            text.addOnLayoutChangeListener((view, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> updateIconSize());
        }

        void bind(SmartspaceCard value) {
            if (card == null || card.remoteViews != value.remoteViews) {
                cancelRemoteLoad();
                if (value.remoteViews == null) clearRemote();
            }
            if (card == null || card.icon != value.icon || card.drawable != value.drawable
                    || card.event != value.event) {
                iconGeneration++;
                iconLoaded = false;
            }
            card = value;
            title.setText(value.title);
            updateClockText();
            details.setText(value.details);
            details.setVisibility(TextUtils.isEmpty(value.details) ? GONE : VISIBLE);
            boolean music = value.event == SmartspaceEvent.MUSIC;
            subtitle.setMaxLines(music ? 1 : 2);
            icon.setScaleType(music ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            icon.setClipToOutline(music);
            updateIconSize();
            bindAction(content, value);
        }

        void updateClockText() {
            CharSequence value = card.clock == null ? card.subtitle : card.clock.text(getContext());
            subtitle.setText(value);
            subtitle.setVisibility(TextUtils.isEmpty(value) ? GONE : VISIBLE);
        }

        private void updateIconSize() {
            if (card == null) return;
            int size = dp(28);
            if (card.event == SmartspaceEvent.MUSIC) {
                size = Math.max(dp(44), text.getHeight());
            } else if ((card.event == SmartspaceEvent.BLUETOOTH
                    || card.event == SmartspaceEvent.PACKAGES
                    || card.event == SmartspaceEvent.DOORBELL) && card.icon != null) {
                size = dp(48);
            }
            ViewGroup.LayoutParams params = icon.getLayoutParams();
            if (params.width != size || params.height != size) {
                params.width = size;
                params.height = size;
                icon.setLayoutParams(params);
            }
        }

        void loadContent() {
            loadRemote();
            if (iconLoaded) return;
            iconLoaded = true;
            int generation = ++iconGeneration;
            if (card.icon == null && card.drawable == null) {
                setFallbackIcon();
                return;
            }
            SmartspaceView.this.loadIcon(card, drawable -> {
                if (generation != iconGeneration) return;
                if (drawable == null) {
                    setFallbackIcon();
                } else {
                    icon.setImageDrawable(drawable);
                    icon.setVisibility(VISIBLE);
                }
            });
        }

        private void setFallbackIcon() {
            int resource = switch (card.event) {
                case CALENDAR -> R.drawable.ic_search_event;
                case ALARMS -> R.drawable.ic_search_alarm;
                case MUSIC -> R.drawable.ic_music_note_24dp;
                case TIMERS -> R.drawable.ic_search_timer;
                case BLUETOOTH -> R.drawable.ic_smartspace_bluetooth;
                default -> 0;
            };
            Drawable drawable = resource == 0 ? null : getContext().getDrawable(resource).mutate();
            if (drawable != null) {
                drawable.setTint(Themes.getAttrColor(getContext(), R.attr.workspaceTextColor));
            }
            icon.setImageDrawable(drawable);
            icon.setVisibility(drawable == null ? GONE : VISIBLE);
        }

        private void loadRemote() {
            RemoteViews value = card.remoteViews;
            if (value == null || requestedRemote == value) return;
            requestedRemote = value;
            int generation = ++remoteGeneration;
            boolean reapply = appliedRemote != null && remote.getChildCount() == 1
                    && appliedRemote.getLayoutId() == value.getLayoutId()
                    && TextUtils.equals(appliedRemote.getPackage(), value.getPackage());
            applyRemote(value, generation, reapply);
        }

        private void applyRemote(RemoteViews value, int generation, boolean reapply) {
            RemoteViews.InteractionHandler interaction = (view, pending, response) -> {
                if (!mActive || !card.event.isEnabled(getContext())
                        || (card.expiresAt > 0 && card.expiresAt <= System.currentTimeMillis())) {
                    return false;
                }
                boolean launched = RemoteViews.startPendingIntent(view, pending,
                        response.getLaunchOptions(view));
                if (launched) {
                    mController.notifyEvent(card, SmartspaceTargetEvent.EVENT_TARGET_INTERACTION);
                }
                return launched;
            };
            RemoteViews.OnViewAppliedListener listener = new RemoteViews.OnViewAppliedListener() {
                @Override
                public void onViewApplied(View view) {
                    if (!mActive || generation != remoteGeneration) return;
                    remoteLoad = null;
                    if (!reapply) {
                        remote.removeAllViews();
                        remote.addView(view);
                    }
                    appliedRemote = value;
                    content.setVisibility(GONE);
                    remote.setVisibility(VISIBLE);
                }

                @Override
                public void onError(Exception error) {
                    if (!mActive || generation != remoteGeneration) return;
                    if (reapply) applyRemote(value, generation, false);
                    else clearRemote();
                }
            };
            try {
                remoteLoad = reapply
                        ? value.reapplyAsync(getContext(), remote.getChildAt(0),
                                UI_HELPER_EXECUTOR, listener, interaction)
                        : value.applyAsync(getContext(), remote, UI_HELPER_EXECUTOR, listener,
                                interaction);
            } catch (RuntimeException e) {
                listener.onError(e);
            }
        }

        private void cancelRemoteLoad() {
            remoteGeneration++;
            if (remoteLoad != null) remoteLoad.cancel();
            remoteLoad = null;
            requestedRemote = null;
        }

        private void clearRemote() {
            remote.removeAllViews();
            remote.setVisibility(GONE);
            content.setVisibility(VISIBLE);
            appliedRemote = null;
        }

        void release() {
            cancelRemoteLoad();
            clearRemote();
            iconGeneration++;
            iconLoaded = false;
            icon.setImageDrawable(null);
            icon.setVisibility(GONE);
        }
    }

    private final class SmartspacePager extends PagedView<PageIndicatorDots> {
        private final int mGestureSlop;
        private float mDownX;
        private float mDownY;
        private boolean mTouching;
        private boolean mVerticalGesture;
        private int mPendingPage = -1;
        private boolean mAnimatePage;

        SmartspacePager(Context context, PageIndicatorDots dots) {
            super(context);
            mPageIndicator = dots;
            mGestureSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        boolean hasPendingPage() {
            return mPendingPage >= 0;
        }

        boolean isBusy() {
            return mTouching || isPageInTransition();
        }

        void selectPage(int page, boolean animate) {
            mPendingPage = page;
            mAnimatePage = animate;
            if (mIsLayoutValid && isPageScrollsInitialized()) applySelectedPage();
            else requestLayout();
        }

        private void applySelectedPage() {
            if (!hasPendingPage()) return;
            int page = mPendingPage;
            mPendingPage = -1;
            boolean updating = mUpdatingPages;
            mUpdatingPages = true;
            if (mAnimatePage) snapToPage(page);
            else setCurrentPage(page);
            mUpdatingPages = updating;
            updateVisiblePage();
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            applySelectedPage();
            mPageIndicator.setScroll(getScrollX(), mMaxScroll);
        }

        @Override
        protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
            super.onScrollChanged(left, top, oldLeft, oldTop);
            if (mPageIndicator != null) mPageIndicator.setScroll(left, mMaxScroll);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                mTouching = true;
                mVerticalGesture = false;
                mDownX = event.getX();
                mDownY = event.getY();
                if (getPageCount() > 1) getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_MOVE && !isHandlingTouch()) {
                float dx = Math.abs(event.getX() - mDownX);
                float dy = Math.abs(event.getY() - mDownY);
                if (dy > mGestureSlop && dy > dx) {
                    mVerticalGesture = true;
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mTouching = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                mHandler.post(mApplyCards);
            }
            return handled;
        }

        @Override
        protected void determineScrollingStart(MotionEvent event) {
            if (!mVerticalGesture && getPageCount() > 1) super.determineScrollingStart(event);
        }

        @Override
        protected void onPageEndTransition() {
            super.onPageEndTransition();
            mHandler.post(mApplyCards);
        }

        void stopScrolling() {
            mTouching = false;
            mPendingPage = -1;
            int page = getNextPage();
            resetTouchState();
            abortScrollerAnimation();
            setCurrentPage(page);
            pageEndTransition();
        }
    }
}
