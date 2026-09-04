package com.dismal.deviceinfo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dismal.deviceinfo.adapter.SpecDashboardAdapter;
import com.dismal.deviceinfo.model.SpecCategory;
import com.dismal.deviceinfo.model.SpecTile;
import com.dismal.deviceinfo.parser.SpecDashboardParser;
import com.dismal.deviceinfo.probe.DeviceProbe;
import com.dismal.deviceinfo.probe.ShellExec;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main screen. Visually this is still the AOSP Settings dashboard --
 * white "category" cards grouped under teal headers -- but every
 * settings item is now a live Device Probe reading instead of a link
 * to another screen (except "About phone", kept as-is). The screen
 * layout itself has no hardcoded structure: on every launch it
 * re-parses res/xml/spec_categories.xml (see SpecDashboardParser) to
 * build the category/tile list, then runs {@link DeviceProbe#run} on
 * a background thread and hands the resulting sections to the adapter
 * once they're ready.
 */
public class MainActivity extends AppCompatActivity implements SpecDashboardAdapter.OnTileClickListener {

    private SpecDashboardAdapter adapter;
    private ProgressBar loadingIndicator;
    private MenuItem refreshItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        setSupportActionBar(toolbar);

        loadingIndicator = findViewById(R.id.loading_indicator);

        List<SpecCategory> categories = SpecDashboardParser.parse(this, R.xml.spec_categories);

        adapter = new SpecDashboardAdapter(categories, this);
        RecyclerView list = findViewById(R.id.dashboard_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        loadSpecs();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        refreshItem = menu.findItem(R.id.action_refresh);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_refresh) {
            loadSpecs();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Runs DeviceProbe on a background thread, then hands the sections to the adapter on the main thread. */
    private void loadSpecs() {
        if (refreshItem != null) refreshItem.setEnabled(false);
        loadingIndicator.setVisibility(View.VISIBLE);

        new Thread(() -> {
            Map<String, DeviceProbe.Section> byTitle;
            try {
                List<DeviceProbe.Section> sections = DeviceProbe.run(getApplicationContext(), new ShellExec(false));
                byTitle = new HashMap<>();
                for (DeviceProbe.Section section : sections) {
                    byTitle.put(section.title, section);
                }
            } catch (Exception e) {
                byTitle = Collections.emptyMap();
            }
            final Map<String, DeviceProbe.Section> result = byTitle;
            new Handler(Looper.getMainLooper()).post(() -> {
                adapter.setSections(result);
                loadingIndicator.setVisibility(View.GONE);
                if (refreshItem != null) refreshItem.setEnabled(true);
            });
        }).start();
    }

    @Override
    public void onTileClick(SpecTile tile) {
        if (tile.hasTarget() && "about".equals(tile.getOpensActivity())) {
            startActivity(new Intent(this, AboutDeviceActivity.class));
            return;
        }
        Toast.makeText(this, R.string.not_implemented_toast, Toast.LENGTH_SHORT).show();
    }
}
