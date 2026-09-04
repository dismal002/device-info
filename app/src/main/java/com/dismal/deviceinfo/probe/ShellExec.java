package com.dismal.deviceinfo.probe;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;


public final class ShellExec {

    private static final String TAG = "deviceprobe";
    private static final String PINGBACK = "//shellPingback//";

    private final boolean useRoot;

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;

    /** True if the shell failed to start, or (for root) root was denied. */
    private boolean isBroken;

    /**
     * @param useRoot true to open "su" (root shell), false for plain "sh".
     */
    public ShellExec(boolean useRoot) {
        this.useRoot = useRoot;
        try {
            Process p = Runtime.getRuntime().exec(useRoot ? "su" : "sh");
            process = p;
            writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            if (useRoot) {
                String idOut = run("id");
                if (idOut == null || !idOut.contains("uid=0(root)")) {
                    isBroken = true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "shell init error", e);
            isBroken = true;
        }
    }

    boolean isBroken() {
        return isBroken;
    }

    /**
     * Runs a single shell command and returns its stdout (trimmed), or null
     * if the shell is dead/broken.
     */
    String run(String command) {
        BufferedWriter w = writer;
        BufferedReader r = reader;
        if (w == null || r == null) return null;
        try {
            w.write(command + "\necho " + PINGBACK + "\n");
            w.flush();
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.equals(PINGBACK)) break;
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            isBroken = true;
            return null;
        }
    }

    /**
     * Reads a file's contents. Tries a direct (permission-respecting) read
     * first - no shell needed - and only falls back to {@code cat} through the
     * shell (useful when root is required to see the file) if that fails.
     */
    String readFile(String path) {
        try {
            File f = new File(path);
            if (f.isFile()) {
                if (f.canRead()) {
                    return readTextTrimmed(f);
                } else if (useRoot) {
                    return run("cat " + path);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "readFile " + path, e);
            return useRoot ? run("cat " + path) : null;
        }
    }

    /**
     * Lists a directory's entries, one per line - direct read first, {@code ls}
     * through the shell as a root-only fallback.
     */
    String listDir(String path) {
        try {
            File f = new File(path);
            if (f.isDirectory()) {
                if (f.canRead()) {
                    String[] names = f.list();
                    if (names == null) return null;
                    return String.join("\n", names);
                } else if (useRoot) {
                    return run("ls " + path);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "listDir " + path, e);
            return useRoot ? run("ls " + path) : null;
        }
    }

    private static String readTextTrimmed(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f)))) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString().trim();
    }
}
