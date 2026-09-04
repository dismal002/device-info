package com.dismal.deviceinfo.parser;

import android.content.Context;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;

import com.dismal.deviceinfo.R;
import com.dismal.deviceinfo.model.SpecCategory;
import com.dismal.deviceinfo.model.SpecTile;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads res/xml/spec_categories.xml and turns it into a list of
 * {@link SpecCategory}. This is the only place that knows about the
 * "spec-categories / spec-category / spec-tile" tag format -- MainActivity
 * just gets back plain data and renders it, so the on-screen layout
 * (which category a tile lives in, its icon/title, and which
 * DeviceProbe section feeds it) changes purely by editing the XML
 * resource.
 */
public final class SpecDashboardParser {

    private static final String TAG_CATEGORY = "spec-category";
    private static final String TAG_TILE = "spec-tile";

    private SpecDashboardParser() {
    }

    public static List<SpecCategory> parse(Context context, int xmlResId) {
        List<SpecCategory> categories = new ArrayList<>();
        XmlResourceParser parser = context.getResources().getXml(xmlResId);
        try {
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            SpecCategory currentCategory = null;

            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                String tagName = parser.getName();

                if (TAG_CATEGORY.equals(tagName)) {
                    TypedArray sa = context.obtainStyledAttributes(attrs, R.styleable.SpecCategory);
                    String title = sa.getString(R.styleable.SpecCategory_android_title);
                    sa.recycle();
                    currentCategory = new SpecCategory(title);
                    categories.add(currentCategory);

                } else if (TAG_TILE.equals(tagName)) {
                    TypedArray sa = context.obtainStyledAttributes(attrs, R.styleable.SpecTile);
                    String key = sa.getString(R.styleable.SpecTile_android_key);
                    String title = sa.getString(R.styleable.SpecTile_android_title);
                    int icon = sa.getResourceId(R.styleable.SpecTile_android_icon, 0);
                    String specSection = sa.getString(R.styleable.SpecTile_specSection);
                    String opensActivity = sa.getString(R.styleable.SpecTile_opensActivity);
                    sa.recycle();

                    if (currentCategory != null) {
                        currentCategory.addTile(new SpecTile(key, title, icon, specSection, opensActivity));
                    }
                }
            }
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Failed to parse spec_categories.xml", e);
        } finally {
            parser.close();
        }
        return categories;
    }
}
