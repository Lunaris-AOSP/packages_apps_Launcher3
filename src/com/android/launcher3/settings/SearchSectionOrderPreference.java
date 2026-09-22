package com.android.launcher3.settings;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.search.universal.UniversalSearchResults;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class SearchSectionOrderPreference extends Preference {

    public SearchSectionOrderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onClick() {
        Context context = getContext();
        List<Integer> order = UniversalSearchResults.sectionOrder(context);
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        ItemTouchHelper[] helper = new ItemTouchHelper[1];
        list.setAdapter(new SectionAdapter(order, holder -> helper[0].startDrag(holder)));
        helper[0] = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                    @NonNull RecyclerView.ViewHolder to) {
                int a = from.getBindingAdapterPosition();
                int b = to.getBindingAdapterPosition();
                Collections.swap(order, a, b);
                rv.getAdapter().notifyItemMoved(a, b);
                return true;
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) { }
        });
        helper[0].attachToRecyclerView(list);

        new AlertDialog.Builder(context)
                .setTitle(getTitle())
                .setView(list)
                .setPositiveButton(android.R.string.ok, (d, w) -> save(order.stream()
                        .map(String::valueOf).collect(Collectors.joining(","))))
                .setNeutralButton(R.string.search_section_order_reset, (d, w) -> save(""))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void save(String value) {
        LauncherPrefs.get(getContext()).put(LauncherPrefs.SEARCH_SECTION_ORDER, value);
    }

    private interface DragStarter {
        void start(RecyclerView.ViewHolder holder);
    }

    private static class SectionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Integer> mOrder;
        private final DragStarter mDragStarter;

        SectionAdapter(List<Integer> order, DragStarter dragStarter) {
            mOrder = order;
            mDragStarter = dragStarter;
        }

        @NonNull
        @Override
        @SuppressLint("ClickableViewAccessibility")
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            Context context = parent.getContext();
            int pad = dp(context, 24);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(context, 52));
            row.setPadding(pad, 0, pad, 0);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageView handle = new ImageView(context);
            handle.setImageResource(R.drawable.ic_search_drag);
            handle.setContentDescription(context.getString(R.string.search_section_order_drag));
            row.addView(handle, new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)));
            RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(row) { };
            handle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    mDragStarter.start(holder);
                }
                return false;
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            TextView label = (TextView) ((ViewGroup) holder.itemView).getChildAt(0);
            label.setText(UniversalSearchResults.getSectionTitle(
                    holder.itemView.getContext(), mOrder.get(position)));
        }

        @Override
        public int getItemCount() {
            return mOrder.size();
        }

        private static int dp(Context context, int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
