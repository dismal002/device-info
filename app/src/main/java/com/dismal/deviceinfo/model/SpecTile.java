package com.dismal.deviceinfo.model;

import com.dismal.deviceinfo.probe.DeviceProbe;

/**
 * A single "settings item" row on the main screen. Unlike the original
 * {@code DashboardTile} (which only navigated to another screen), most
 * spec tiles render their data inline: {@link #specSection} names the
 * {@link DeviceProbe.Section#title} this tile should display once the
 * probe finishes running (wired up in res/xml/spec_categories.xml via
 * app:specSection). {@link #section} starts null and is filled in by
 * SpecDashboardAdapter#setSections once DeviceProbe.run() returns.
 */
public class SpecTile {

    private final String key;
    private final String title;
    private final int iconRes;
    private final String specSection;
    /** Non-null only for the one legacy tile that still opens a real screen (About phone). */
    private final String opensActivity;

    private DeviceProbe.Section section;

    public SpecTile(String key, String title, int iconRes, String specSection, String opensActivity) {
        this.key = key;
        this.title = title;
        this.iconRes = iconRes;
        this.specSection = specSection;
        this.opensActivity = opensActivity;
    }

    public String getKey() {
        return key;
    }

    public String getTitle() {
        return title;
    }

    public int getIconRes() {
        return iconRes;
    }

    public String getSpecSection() {
        return specSection;
    }

    public String getOpensActivity() {
        return opensActivity;
    }

    public boolean hasTarget() {
        return opensActivity != null && !opensActivity.isEmpty();
    }

    public boolean isSpecTile() {
        return specSection != null && !specSection.isEmpty();
    }

    public DeviceProbe.Section getSection() {
        return section;
    }

    public void setSection(DeviceProbe.Section section) {
        this.section = section;
    }
}
