package com.dismal.deviceinfo.parser;

import android.content.Context;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;

import com.dismal.deviceinfo.R;
import com.dismal.deviceinfo.model.AboutRow;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads res/xml/device_info_settings.xml into a list of {@link AboutRow}.
 * Rows flagged app:dynamic="true" carry no fixed summary here -- their
 * value is resolved live by AboutDeviceActivity from android.os.Build
 * and friends, so the screen always reflects the actual device it runs
 * on rather than a value baked into the XML or the Java code.
 */
public final class AboutDeviceParser {

    private static final String TAG_ROW = "about-preference";

    private AboutDeviceParser() {
    }

    public static List<AboutRow> parse(Context context, int xmlResId) {
        List<AboutRow> rows = new ArrayList<>();
        XmlResourceParser parser = context.getResources().getXml(xmlResId);
        try {
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                if (TAG_ROW.equals(parser.getName())) {
                    TypedArray sa = context.obtainStyledAttributes(attrs, R.styleable.AboutPreference);
                    String key = sa.getString(R.styleable.AboutPreference_android_key);
                    String title = sa.getString(R.styleable.AboutPreference_android_title);
                    String summary = sa.getString(R.styleable.AboutPreference_android_summary);
                    boolean dynamic = sa.getBoolean(R.styleable.AboutPreference_dynamic, false);
                    boolean isCheckbox = sa.getBoolean(R.styleable.AboutPreference_isCheckbox, false);
                    sa.recycle();

                    rows.add(new AboutRow(key, title, summary, dynamic, isCheckbox));
                }
            }
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Failed to parse device_info_settings.xml", e);
        } finally {
            parser.close();
        }
        return rows;
    }
}
