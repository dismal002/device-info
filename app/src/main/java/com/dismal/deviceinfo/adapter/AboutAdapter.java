package com.dismal.deviceinfo.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.dismal.deviceinfo.R;
import com.dismal.deviceinfo.model.AboutRow;

import java.util.List;

/**
 * Renders the About-device rows produced by AboutDeviceParser. The
 * summary text for a row is passed in already-resolved (see
 * AboutDeviceActivity#resolveDynamicSummary) so this class has no
 * knowledge of which rows are "dynamic" -- it only knows how to draw
 * a title + summary row.
 */
public class AboutAdapter extends RecyclerView.Adapter<AboutAdapter.RowViewHolder> {

    public interface OnRowClickListener {
        void onRowClick(AboutRow row);
    }

    private final List<AboutRow> rows;
    private final List<String> resolvedSummaries;
    private final OnRowClickListener listener;

    public AboutAdapter(List<AboutRow> rows, List<String> resolvedSummaries, OnRowClickListener listener) {
        this.rows = rows;
        this.resolvedSummaries = resolvedSummaries;
        this.listener = listener;
    }

    @NonNull
    @Override
    public RowViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_about_row, parent, false);
        return new RowViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RowViewHolder holder, int position) {
        AboutRow row = rows.get(position);
        String summary = resolvedSummaries.get(position);

        holder.title.setText(row.getTitle());
        if (summary == null || summary.isEmpty()) {
            holder.summary.setVisibility(View.GONE);
        } else {
            holder.summary.setVisibility(View.VISIBLE);
            holder.summary.setText(summary);
        }
        
        if (row.isCheckbox()) {
            holder.checkbox.setVisibility(View.VISIBLE);
            // Optionally, you might want to handle state here if it was saved
        } else {
            holder.checkbox.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (row.isCheckbox()) {
                holder.checkbox.setChecked(!holder.checkbox.isChecked());
            }
            if (listener != null) {
                listener.onRowClick(row);
            }
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class RowViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView summary;
        final android.widget.CheckBox checkbox;

        RowViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.row_title);
            summary = itemView.findViewById(R.id.row_summary);
            checkbox = itemView.findViewById(R.id.row_checkbox);
        }
    }
}
