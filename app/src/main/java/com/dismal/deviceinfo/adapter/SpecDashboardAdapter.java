package com.dismal.deviceinfo.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.dismal.deviceinfo.R;
import com.dismal.deviceinfo.model.SpecCategory;
import com.dismal.deviceinfo.model.SpecTile;
import com.dismal.deviceinfo.probe.DeviceProbe;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Renders the list of {@link SpecCategory} produced by
 * SpecDashboardParser, one white card per category (reusing
 * item_dashboard_category.xml unchanged) and one inflated
 * item_spec_tile row per tile. Nothing about which categories/tiles
 * exist, or which DeviceProbe section feeds a given tile, is
 * hardcoded here -- that all comes from the XML resource via the
 * SpecTile objects this class is handed.
 *
 * DeviceProbe.run() happens on a background thread elsewhere
 * (MainActivity); until {@link #setSections} is called with the
 * result, every spec tile just shows a "Reading..." placeholder row
 * instead of blocking the initial render.
 */
public class SpecDashboardAdapter extends RecyclerView.Adapter<SpecDashboardAdapter.CategoryViewHolder> {

    /** Callback for a tile click -- only ever fires for the legacy About-phone tile. */
    public interface OnTileClickListener {
        void onTileClick(SpecTile tile);
    }

    private final List<SpecCategory> categories;
    private final OnTileClickListener listener;
    private Map<String, DeviceProbe.Section> sectionsByTitle = Collections.emptyMap();
    private boolean loaded = false;

    public SpecDashboardAdapter(List<SpecCategory> categories, OnTileClickListener listener) {
        this.categories = categories;
        this.listener = listener;
    }

    /** Called once DeviceProbe.run() returns on the main thread; refreshes every bound tile. */
    public void setSections(Map<String, DeviceProbe.Section> sectionsByTitle) {
        this.sectionsByTitle = sectionsByTitle != null ? sectionsByTitle : Collections.emptyMap();
        this.loaded = true;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CategoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_dashboard_category, parent, false);
        return new CategoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position) {
        SpecCategory category = categories.get(position);
        holder.title.setText(category.getTitle());

        holder.content.removeAllViews();
        Context context = holder.content.getContext();
        List<SpecTile> tiles = category.getTiles();

        for (int i = 0; i < tiles.size(); i++) {
            SpecTile tile = tiles.get(i);
            if (tile.isSpecTile()) {
                tile.setSection(sectionsByTitle.get(tile.getSpecSection()));
            }

            View row = LayoutInflater.from(context)
                    .inflate(R.layout.item_spec_tile, holder.content, false);

            ImageView icon = row.findViewById(R.id.icon);
            TextView title = row.findViewById(R.id.title);
            LinearLayout specRows = row.findViewById(R.id.spec_rows);
            View divider = row.findViewById(R.id.tile_divider);

            if (tile.getIconRes() != 0) {
                icon.setImageResource(tile.getIconRes());
            }
            title.setText(headerText(tile));

            bindSpecRows(context, specRows, tile);

            divider.setVisibility(i == tiles.size() - 1 ? View.GONE : View.VISIBLE);

            if (tile.hasTarget()) {
                row.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onTileClick(tile);
                    }
                });
                row.setBackgroundResource(resolveSelectableBackground(context));
            } else {
                row.setOnClickListener(null);
                row.setClickable(false);
                row.setBackground(null);
            }

            holder.content.addView(row);
        }
    }

    /** "Title" alone, or "Title: subtitle" once the section has loaded and has a subtitle -- matches the concept screenshot. */
    private String headerText(SpecTile tile) {
        DeviceProbe.Section section = tile.getSection();
        if (section != null && section.subtitle != null && !section.subtitle.trim().isEmpty()) {
            return tile.getTitle() + ": " + section.subtitle;
        }
        return tile.getTitle();
    }

    private void bindSpecRows(Context context, LinearLayout container, SpecTile tile) {
        container.removeAllViews();
        if (!tile.isSpecTile()) {
            // The About-phone tile: no inline rows, it just navigates away.
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);

        DeviceProbe.Section section = tile.getSection();
        if (!loaded) {
            container.addView(specRow(context, container, context.getString(R.string.spec_loading)));
            return;
        }
        if (section == null) {
            container.addView(specRow(context, container, context.getString(R.string.spec_load_failed)));
            return;
        }
        if (section.details.isEmpty()) {
            container.addView(specRow(context, container, context.getString(R.string.spec_no_values)));
            return;
        }
        for (Map.Entry<String, String> entry : section.details.entrySet()) {
            String value = entry.getValue();
            String text = entry.getKey() + ": " + (value == null || value.trim().isEmpty() ? "-" : value);
            container.addView(specRow(context, container, text));
        }
    }

    private View specRow(Context context, ViewGroup parent, String text) {
        View row = LayoutInflater.from(context).inflate(R.layout.item_spec_row, parent, false);
        TextView textView = row.findViewById(R.id.spec_row_text);
        textView.setText(text);
        return row;
    }

    private int resolveSelectableBackground(Context context) {
        android.util.TypedValue outValue = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        return outValue.resourceId;
    }

    @Override
    public int getItemCount() {
        return categories.size();
    }

    static class CategoryViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final LinearLayout content;

        CategoryViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.category_title);
            content = itemView.findViewById(R.id.category_content);
        }
    }
}
