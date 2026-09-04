package com.dismal.deviceinfo.probe;

import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java rewrite of the original {@code h.java} string-utility grab-bag.
 *
 * The original had ~30 tiny helpers used across every probe category
 * (wifi, camera, sensors, etc). This keeps only what the simplified
 * probe (phone model / build / chip / storage / touch) actually needs.
 */
final class StrUtil {

    private StrUtil() {
    }

    /** First non-null/non-blank value, or null. */
    static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }

    /** Null-safe blank check (mirrors the original's h.c()). */
    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Null-safe non-blank-or-null (mirrors the original's h.b()). */
    static String blankToNull(String s) {
        return isBlank(s) ? null : s;
    }

    /** Joins non-blank values with a separator, skipping blanks/nulls entirely. */
    static String joinSkipBlanks(String sep, String... values) {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) continue;
            if (any) sb.append(sep);
            sb.append(v);
            any = true;
        }
        return any ? sb.toString() : null;
    }

    /** "key: value" style join, one per line, for a details map. */
    static String joinDetails(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append('\n');
            sb.append(e.getKey()).append(": ").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    /**
     * Matches {@code text} against a list of "vendor;pattern1,pattern2,~regexPattern"
     * rules (same convention the original used everywhere): a leading {@code ~} on
     * a token means "treat the rest as a regex", otherwise it's a plain
     * case-sensitive substring. Returns the first matching vendor name.
     */
    static String matchVendor(String text, Map<String, String> rules) {
        String lower = text.toLowerCase();
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            String vendor = entry.getKey();
            for (String token : entry.getValue().split("[,;]")) {
                boolean hit;
                if (token.startsWith("~")) {
                    hit = false;
                    try {
                        hit = Pattern.compile(token.substring(1)).matcher(lower).find();
                    } catch (Exception ignored) {
                        // hit stays false
                    }
                } else {
                    hit = lower.contains(token);
                }
                if (hit) return vendor;
            }
        }
        return null;
    }

    /** First capture group of {@code regex} found in {@code text}, or null. */
    static String findGroup(String text, String regex) {
        if (text == null) return null;
        try {
            Matcher m = Pattern.compile(regex).matcher(text);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Human-readable byte size, e.g. 137438953472 -> "128 G". */
    static String humanBytes(long bytes) {
        if (bytes <= 0) return "0";
        String[] units = {"", "K", "M", "G", "T"};
        int exp = (int) (Math.log10((double) bytes) / Math.log10(1024.0));
        if (exp < 0) exp = 0;
        if (exp > units.length - 1) exp = units.length - 1;
        double value = bytes / Math.pow(1024.0, (double) exp);
        return new DecimalFormat("#,##0.#").format(value) + " " + units[exp];
    }

    /** Parses "key: value" / "key:value" lines (as seen in /proc/hwinfo, getprop, etc). */
    static Map<String, String> parseKeyValueLines(String raw, char delimiter) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String line : raw.split("\n", -1)) {
            int idx = line.indexOf(delimiter);
            if (idx > 0) {
                String k = line.substring(0, idx).trim().toLowerCase();
                String v = line.substring(idx + 1).trim();
                if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
            }
        }
        return out;
    }

    /** Overload defaulting to ':' as in the original. */
    static Map<String, String> parseKeyValueLines(String raw) {
        return parseKeyValueLines(raw, ':');
    }

    /** Parses the {@code [key]: [value]} format that {@code getprop} prints. */
    static Map<String, String> parseGetprop(String raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        Pattern pattern = Pattern.compile("\\[([^\\]]+)\\]:\\s*\\[([^\\]]*)\\]");
        for (String line : raw.split("\n", -1)) {
            Matcher m = pattern.matcher(line);
            if (m.matches()) {
                out.put(m.group(1), m.group(2) == null ? "" : m.group(2));
            }
        }
        return out;
    }
}
