package com.purewatch.collector;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class CollectorStore {
    private static final String FILE_NAME = "collector.jsonl";
    private final Context context;
    private final File file;
    private final Set<Integer> fingerprints = new HashSet<>();

    public CollectorStore(Context context) {
        this.context = context.getApplicationContext();
        this.file = new File(this.context.getFilesDir(), FILE_NAME);
        loadFingerprints();
    }

    private synchronized void loadFingerprints() {
        fingerprints.clear();
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JSONObject obj = new JSONObject(line);
                    String fp = obj.optString("fingerprint", "");
                    if (!fp.isEmpty()) fingerprints.add(fp.hashCode());
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    public synchronized boolean append(JSONArray texts, String activity, String bounds) {
        String normalized = texts.toString().replaceAll("\\s+", " ").trim();
        if (normalized.length() < 8) return false;
        int hash = normalized.hashCode();
        if (fingerprints.contains(hash)) return false;
        try {
            JSONObject obj = new JSONObject();
            obj.put("captured_at", System.currentTimeMillis());
            obj.put("captured_at_text", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
            obj.put("activity", activity == null ? "" : activity);
            obj.put("bounds", bounds == null ? "" : bounds);
            obj.put("texts", texts);
            obj.put("fingerprint", Integer.toHexString(hash));
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((obj.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            fingerprints.add(hash);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized int count() { return fingerprints.size(); }

    public synchronized void clear() {
        if (file.exists()) file.delete();
        fingerprints.clear();
    }

    public synchronized Uri exportToDownloads() throws Exception {
        JSONArray items = new JSONArray();
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try { items.put(new JSONObject(line)); } catch (Exception ignored) {}
                }
            }
        }
        JSONObject root = new JSONObject();
        root.put("app", "Pure Watch Collector");
        root.put("version", "0.1.0");
        root.put("exported_at", System.currentTimeMillis());
        root.put("count", items.length());
        root.put("items", items);

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, "purewatch_collector_" + stamp + ".json");
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PureWatchCollector");
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("无法创建下载文件");
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("无法写入下载文件");
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        return uri;
    }
}
