package com.android.launcher3.customization;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BaseActivity;
import com.android.launcher3.R;
import com.android.launcher3.icons.pack.IconPack;
import com.android.launcher3.icons.pack.IconPackManager;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.views.AbstractSlideInView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.THREAD_POOL_EXECUTOR;

public class IconPickerBottomSheet extends AbstractSlideInView<BaseActivity> {
    private static final int DEFAULT_CLOSE_DURATION = 200;

    public interface OnIconChosen {
        void onIconChosen();
    }

    private View mContentView;
    private RecyclerView mRecyclerView;
    private TextView mTitle;
    private final IconPackManager mManager;
    private ComponentKey mKey;
    private OnIconChosen mCallback;

    public IconPickerBottomSheet(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public IconPickerBottomSheet(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mManager = IconPackManager.get(context);
        setWillNotDraw(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContent = findViewById(R.id.icon_picker_content); // mContent is declared in AbstractSlideInView
        mContentView = mContent;
        mRecyclerView = findViewById(R.id.icon_picker_recycler_view);
        mTitle = findViewById(R.id.icon_picker_title);
        setContentBackgroundWithParent(
                getContext().getDrawable(R.drawable.bg_rounded_corner_bottom_sheet), mContent);
    }

    public void show(ComponentKey key, OnIconChosen callback) {
        mKey = key;
        mCallback = callback;
        mTitle.setText(R.string.app_info_custom_icon_title);
        showPackList();
        attachToContainer();
        mIsOpen = false;
        animateOpenSelf();
    }

    private void animateOpenSelf() {
        if (mIsOpen || mOpenCloseAnimation.getAnimationPlayer().isRunning()) {
            return;
        }
        mIsOpen = true;
        setUpDefaultOpenAnimation().start();
    }

    private void showPackList() {
        Map<String, CharSequence> packs = mManager.getProviderNames();
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerView.setAdapter(new PackListAdapter(packs, this::showIconGrid));
    }

    private void showIconGrid(String packPackage) {
        THREAD_POOL_EXECUTOR.execute(() -> {
            List<IconPack.IconEntry> entries = mManager.getAllIconEntries(packPackage);
            MAIN_EXECUTOR.execute(() -> {
                mRecyclerView.setLayoutManager(new GridLayoutManager(getContext(), 5));
                mRecyclerView.setAdapter(
                        new IconGridAdapter(packPackage, entries, this::onIconPicked));
            });
        });
    }

    private void onIconPicked(String packPackage, IconPack.IconEntry entry) {
        IconDatabase.setExplicitIconForComponent(getContext(), mKey, packPackage, entry.drawableName);
        if (mCallback != null) {
            mCallback.onIconChosen();
        }
        close(true);
    }

    @Override
    protected void handleClose(boolean animate) {
        handleClose(animate, DEFAULT_CLOSE_DURATION);
    }

    @Override
    protected boolean isOfType(@AbstractFloatingView.FloatingViewType int type) {
        return (type & AbstractFloatingView.TYPE_ICON_PICKER_BOTTOM_SHEET) != 0;
    }

    @Override
    protected float getShiftRange() {
        return mContent.getHeight();
    }

    @Override
    protected Pair<View, String> getAccessibilityTarget() {
        return Pair.create(mTitle, getContext().getString(R.string.app_info_custom_icon_title));
    }

    // --- adapters ---

    private static class PackListAdapter extends RecyclerView.Adapter<PackListAdapter.VH> {
        interface OnPackClick { void onClick(String packPackage); }

        private final List<Map.Entry<String, CharSequence>> mItems;
        private final OnPackClick mListener;

        PackListAdapter(Map<String, CharSequence> packs, OnPackClick listener) {
            mItems = new ArrayList<>(packs.entrySet());
            mListener = listener;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_1, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            Map.Entry<String, CharSequence> entry = mItems.get(position);
            holder.text.setText(entry.getValue());
            holder.itemView.setOnClickListener(v -> mListener.onClick(entry.getKey()));
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView text;
            VH(View itemView) {
                super(itemView);
                text = itemView.findViewById(android.R.id.text1);
            }
        }
    }

    private static class IconGridAdapter extends RecyclerView.Adapter<IconGridAdapter.VH> {
        interface OnIconClick { void onClick(String packPackage, IconPack.IconEntry entry); }

        private final String mPackPackage;
        private final List<IconPack.IconEntry> mEntries;
        private final OnIconClick mListener;

        IconGridAdapter(String packPackage, List<IconPack.IconEntry> entries, OnIconClick listener) {
            mPackPackage = packPackage;
            mEntries = entries;
            mListener = listener;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            ImageView iv = new ImageView(parent.getContext());
            int size = parent.getContext().getResources()
                    .getDimensionPixelSize(R.dimen.icon_picker_cell_size);
            iv.setLayoutParams(new ViewGroup.LayoutParams(size, size));
            int pad = parent.getContext().getResources()
                    .getDimensionPixelSize(R.dimen.icon_picker_cell_padding);
            iv.setPadding(pad, pad, pad, pad);
            return new VH(iv);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            IconPack.IconEntry entry = mEntries.get(position);
            holder.image.setImageResource(entry.resId);
            holder.itemView.setOnClickListener(v -> mListener.onClick(mPackPackage, entry));
        }

        @Override
        public int getItemCount() {
            return mEntries.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            ImageView image;
            VH(View itemView) {
                super(itemView);
                image = (ImageView) itemView;
            }
        }
    }
}