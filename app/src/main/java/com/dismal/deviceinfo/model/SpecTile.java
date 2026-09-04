package com.dismal.deviceinfo.model;

import com.dismal.deviceinfo.probe.DeviceProbe;


public class SpecTile {

    private final String key;
    private final String title;
    private final int iconRes;
    private final String specSection;
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
