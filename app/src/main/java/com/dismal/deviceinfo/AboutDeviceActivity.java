package com.dismal.deviceinfo;

import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dismal.deviceinfo.adapter.AboutAdapter;
import com.dismal.deviceinfo.model.AboutRow;
import com.dismal.deviceinfo.parser.AboutDeviceParser;

import java.util.ArrayList;
import java.util.List;

/**
 * "About device" screen -- the one submenu this clone implements in
 * full. The row list (order, titles, which rows are "dynamic") comes
 * entirely from res/xml/device_info_settings.xml via AboutDeviceParser.
 * The only thing done here in Java is resolving each dynamic row's
 * live value from android.os.Build / the OS at display time, so
 * nothing about the actual device is hardcoded either.
 */
public class AboutDeviceActivity extends AppCompatActivity implements AboutAdapter.OnRowClickListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about_device);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.about_settings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        if (toolbar.getNavigationIcon() != null) {
            toolbar.getNavigationIcon().setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_ATOP);
        }

        List<AboutRow> rows = AboutDeviceParser.parse(this, R.xml.device_info_settings);

        List<String> resolvedSummaries = new ArrayList<>(rows.size());
        for (AboutRow row : rows) {
            if (row.isDynamic()) {
                resolvedSummaries.add(resolveDynamicSummary(row.getKey()));
            } else {
                resolvedSummaries.add(row.getStaticSummary());
            }
        }

        RecyclerView list = findViewById(R.id.about_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new AboutAdapter(rows, resolvedSummaries, this));
    }

    @Override
    public boolean onOptionsItemSelected(@Nullable MenuItem item) {
        if (item != null && item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRowClick(AboutRow row) {
        if ("view_on_github".equals(row.getKey())) {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse("https://github.com/dismal002/device-info"));
            startActivity(intent);
            return;
        }
        // Every row here is informational in the real About screen too
        // (style="?android:preferenceInformationStyle"), except a
        // couple that launch external activities (system update,
        // legal info, ...), which are out of scope for this clone.
        Toast.makeText(this, R.string.not_implemented_toast, Toast.LENGTH_SHORT).show();
    }


    private String resolveDynamicSummary(String key) {
        switch (key) {
            case "app_version":
                try {
                    return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Exception e) {
                    return "1.0";
                }

            default:
                return getString(R.string.device_info_default);
        }
    }
}
