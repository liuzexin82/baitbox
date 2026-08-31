package com.purewatch.collector;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String PREFS = "collector_prefs";
    static final String KEY_RUNNING = "running";
    static final String KEY_MODE = "mode";
    static final String KEY_STAGE = "stage";
    static final String KEY_MAX = "max_items";
    static final String KEY_STATUS = "status";
    static final String KEY_SERVICE = "service_connected";
    static final String MODE_AUTO = "auto";
    static final String MODE_CURRENT = "current";

    private SharedPreferences prefs;
    private CollectorStore store;
    private TextView statusText;
    private EditText maxItemsEdit;
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        store = new CollectorStore(this);
        statusText = findViewById(R.id.statusText);
        maxItemsEdit = findViewById(R.id.maxItemsEdit);
        maxItemsEdit.setText(String.valueOf(prefs.getInt(KEY_MAX, 50)));

        findViewById(R.id.accessibilityButton).setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.openSdjButton).setOnClickListener(v -> openSdj());
        findViewById(R.id.startAutoButton).setOnClickListener(v -> startCollect(MODE_AUTO));
        findViewById(R.id.startCurrentButton).setOnClickListener(v -> startCollect(MODE_CURRENT));
        findViewById(R.id.stopButton).setOnClickListener(v -> stopCollect("已手动停止"));
        findViewById(R.id.clearButton).setOnClickListener(v -> {
            stopCollect("已停止");
            store.clear();
            Toast.makeText(this, "本地采集结果已清空", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        findViewById(R.id.exportButton).setOnClickListener(v -> exportJson());
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    private void startCollect(String mode) {
        int max = 50;
        try { max = Integer.parseInt(maxItemsEdit.getText().toString().trim()); } catch (Exception ignored) {}
        max = Math.max(10, Math.min(max, 500));
        prefs.edit()
            .putBoolean(KEY_RUNNING, true)
            .putString(KEY_MODE, mode)
            .putInt(KEY_STAGE, mode.equals(MODE_AUTO) ? 0 : 3)
            .putInt(KEY_MAX, max)
            .putString(KEY_STATUS, mode.equals(MODE_AUTO) ? "准备自动进入奢当家" : "准备从当前页面采集")
            .apply();
        if (mode.equals(MODE_AUTO)) openSdj();
        else Toast.makeText(this, "已开始，请切回奢当家腕表列表", Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void stopCollect(String msg) {
        prefs.edit().putBoolean(KEY_RUNNING, false).putString(KEY_STATUS, msg).apply();
        refreshStatus();
    }

    private void openSdj() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.sdangjs.android");
        if (intent == null) {
            Toast.makeText(this, "未检测到奢当家 App", Toast.LENGTH_LONG).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void exportJson() {
        try {
            Uri uri = store.exportToDownloads();
            Toast.makeText(this, "已导出到 下载/PureWatchCollector", Toast.LENGTH_LONG).show();
            prefs.edit().putString(KEY_STATUS, "已导出 JSON：" + uri).apply();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        boolean service = prefs.getBoolean(KEY_SERVICE, false);
        boolean running = prefs.getBoolean(KEY_RUNNING, false);
        String msg = prefs.getString(KEY_STATUS, "等待设置");
        int count = store.count();
        int max = prefs.getInt(KEY_MAX, 50);
        statusText.setText("辅助功能：" + (service ? "已连接" : "未连接") +
                "\n采集状态：" + (running ? "运行中" : "已停止") +
                "\n本地候选条目：" + count + " / " + max +
                "\n\n" + msg);
    }
}
