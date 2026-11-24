package com.hyperbot.remote;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Bundled animations plus the ones you draw or convert (stored privately as my_*.qgif). */
final class Library {
    private static final String TAG = "HyperBot";
    private final List<Qgif> bundled = new ArrayList<>();
    private final List<Qgif> mine = new ArrayList<>();
    private final File mineDir;

    Library(Context context) {
        mineDir = new File(context.getFilesDir(), "mine");
        //noinspection ResultOfMethodCallIgnored
        mineDir.mkdirs();
        try (InputStream in = context.getAssets().open("animations.json")) {
            JSONArray names = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
            for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                try (InputStream file = context.getAssets().open("animations/" + name)) {
                    bundled.add(Qgif.parse(name, readAll(file)));
                } catch (Exception e) {
                    Log.w(TAG, "skipping " + name + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "catalog failed", e);
        }
        reloadMine();
    }

    private void reloadMine() {
        mine.clear();
        File[] files = mineDir.listFiles();
        if (files == null) return;
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, files);
        sorted.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : sorted) {
            if (!f.getName().endsWith(".qgif")) continue;
            try (InputStream in = new java.io.FileInputStream(f)) {
                mine.add(Qgif.parse(f.getName(), readAll(in)));
            } catch (Exception e) {
                Log.w(TAG, "bad saved animation " + f.getName());
            }
        }
    }

    /** Your animations first, then the bundled set. */
    List<Qgif> all() {
        List<Qgif> out = new ArrayList<>(mine);
        out.addAll(bundled);
        return out;
    }

    int mineCount() { return mine.size(); }

    Qgif find(String name) {
        if (name == null) return null;
        for (Qgif q : mine) if (q.name.equalsIgnoreCase(name)) return q;
        for (Qgif q : bundled) if (q.name.equalsIgnoreCase(name)) return q;
        // The bot may report a path ("/gifs/eye_wink.qgif") or drop the extension: match on the bare name.
        String key = bare(name);
        if (key.isEmpty()) return null;
        for (Qgif q : mine) if (bare(q.name).equals(key)) return q;
        for (Qgif q : bundled) if (bare(q.name).equals(key)) return q;
        return null;
    }

    /** "/gifs/My_Face.QGIF " -> "my_face" */
    static String bare(String name) {
        String n = name.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".qgif")) n = n.substring(0, n.length() - 5);
        return n;
    }

    boolean isMine(Qgif q) { return mine.contains(q); }

    /** Save (or overwrite) one of your animations; returns the stored copy. */
    Qgif saveMine(String title, byte[] bytes) throws IOException {
        String name = fileNameFor(title);
        Qgif parsed = Qgif.parse(name, bytes);
        try (FileOutputStream out = new FileOutputStream(new File(mineDir, name))) {
            out.write(bytes);
        }
        reloadMine();
        Qgif stored = find(name);
        return stored != null ? stored : parsed;
    }

    boolean delete(Qgif q) {
        if (!isMine(q)) return false;
        boolean ok = new File(mineDir, q.name).delete();
        reloadMine();
        return ok;
    }

    /** "Happy Blink!" -> "my_happy_blink.qgif" (firmware accepts a-z 0-9 _ - . up to 64 chars). */
    static String fileNameFor(String title) {
        String slug = title == null ? "" : title.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) slug = "face";
        if (slug.length() > 40) slug = slug.substring(0, 40);
        return "my_" + slug + ".qgif";
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }
}
