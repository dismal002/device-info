package com.dismal.deviceinfo.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A section header on the main screen (e.g. "Wireless &amp; networks")
 * together with the {@link SpecTile}s that belong to it. Populated at
 * runtime by SpecDashboardParser from res/xml/spec_categories.xml.
 */
public class SpecCategory {

    private final String title;
    private final List<SpecTile> tiles = new ArrayList<>();

    public SpecCategory(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public List<SpecTile> getTiles() {
        return tiles;
    }

    public void addTile(SpecTile tile) {
        tiles.add(tile);
    }
}
