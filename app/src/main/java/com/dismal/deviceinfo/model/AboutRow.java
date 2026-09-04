package com.dismal.deviceinfo.model;

/**
 * A single row on the About device screen, built at runtime by
 * AboutDeviceParser from res/xml/device_info_settings.xml.
 */
public class AboutRow {

    private final String key;
    private final String title;
    private final String staticSummary;
    private final boolean dynamic;
    private final boolean isToggle;
    private final boolean isEnabled;

    public AboutRow(String key, String title, String staticSummary, boolean dynamic, boolean isToggle, boolean isEnabled) {
        this.key = key;
        this.title = title;
        this.staticSummary = staticSummary;
        this.dynamic = dynamic;
        this.isToggle = isToggle;
        this.isEnabled = isEnabled;
    }

    public String getKey() {
        return key;
    }

    public String getTitle() {
        return title;
    }

    public String getStaticSummary() {
        return staticSummary;
    }

    public boolean isDynamic() {
        return dynamic;
    }
    
    public boolean isToggle() {
        return isToggle;
    }
    
    public boolean isEnabled() {
        return isEnabled;
    }
}
