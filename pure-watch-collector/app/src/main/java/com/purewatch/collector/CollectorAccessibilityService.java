package com.purewatch.collector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CollectorAccessibilityService extends AccessibilityService {
    private SharedPreferences prefs;
    private CollectorStore store;
    private final Handler handler = new Handler();
    private boolean scheduled = false;
    private int noNewScrolls = 0;
    private String lastActivity = "";

    @Override protected void onServiceConnected() {
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        store = new CollectorStore(this);
        prefs.edit().putBoolean(MainActivity.KEY_SERVICE, true).putString(MainActivity.KEY_STATUS, "辅助功能已连接").apply();
    }

    @Override public void onDestroy() {
        if (prefs != null) prefs.edit().putBoolean(MainActivity.KEY_SERVICE, false).apply();
        super.onDestroy();
    }

    @Override public void onInterrupt() {
        if (prefs != null) prefs.edit().putString(MainActivity.KEY_STATUS, "辅助功能被系统中断").apply();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null || !prefs.getBoolean(MainActivity.KEY_RUNNING, false)) return;
        if (event.getPackageName() == null || !"com.sdangjs.android".contentEquals(event.getPackageName())) return;
        lastActivity = event.getClassName() == null ? "" : event.getClassName().toString();
        if (!scheduled) {
            scheduled = true;
            handler.postDelayed(this::step, 700);
        }
    }

    private void step() {
        scheduled = false;
        if (!prefs.getBoolean(MainActivity.KEY_RUNNING, false)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            status("等待奢当家页面...");
            return;
        }
        String mode = prefs.getString(MainActivity.KEY_MODE, MainActivity.MODE_CURRENT);
        int stage = prefs.getInt(MainActivity.KEY_STAGE, mode.equals(MainActivity.MODE_AUTO) ? 0 : 3);
        if (mode.equals(MainActivity.MODE_AUTO) && stage < 3) {
            if (navigate(root, stage)) return;
        }
        collectAndScroll(root);
    }

    private boolean navigate(AccessibilityNodeInfo root, int stage) {
        if (stage == 0) {
            if (clickText(root, "同行市场")) {
                setStage(1, "已点击同行市场，等待腕表分类");
                scheduleNext(1200);
                return true;
            }
            if (containsText(root, "腕表")) setStage(1, "已在同行市场，继续查找腕表");
            else status("正在寻找“同行市场”…；找不到时请手动进入后使用“从当前页面采集”");
            scheduleNext(1200);
            return true;
        }
        if (stage == 1) {
            if (clickText(root, "腕表")) {
                setStage(2, "已点击腕表，等待最近更新排序");
                scheduleNext(1200);
                return true;
            }
            if (containsText(root, "最近更新") || containsText(root, "按最近更新")) setStage(2, "已进入腕表列表");
            else status("正在寻找“腕表”…");
            scheduleNext(1200);
            return true;
        }
        if (stage == 2) {
            if (clickText(root, "按最近更新") || clickText(root, "最近更新")) {
                setStage(3, "已选择最近更新，开始采集");
                scheduleNext(1300);
                return true;
            }
            setStage(3, "未找到排序按钮，按当前列表开始采集");
            scheduleNext(500);
            return true;
        }
        return false;
    }

    private void collectAndScroll(AccessibilityNodeInfo root) {
        int before = store.count();
        List<AccessibilityNodeInfo> containers = findLikelyContainers(root);
        for (AccessibilityNodeInfo node : containers) {
            JSONArray texts = new JSONArray();
            collectTexts(node, texts, 0);
            if (texts.length() < 3 || texts.length() > 30) continue;
            String joined = texts.toString();
            if (isNavigationOnly(joined)) continue;
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            store.append(texts, lastActivity, r.flattenToString());
        }
        int now = store.count();
        int max = prefs.getInt(MainActivity.KEY_MAX, 50);
        if (now >= max) {
            prefs.edit().putBoolean(MainActivity.KEY_RUNNING, false).putString(MainActivity.KEY_STATUS, "已达到本次上限 " + max + " 条，采集停止。请导出 JSON。").apply();
            return;
        }
        if (now == before) noNewScrolls++; else noNewScrolls = 0;
        status("采集中：" + now + " / " + max + "，准备继续下滑");
        if (noNewScrolls >= 6) {
            prefs.edit().putBoolean(MainActivity.KEY_RUNNING, false).putString(MainActivity.KEY_STATUS, "连续多次没有发现新条目，已自动停止。当前 " + now + " 条。").apply();
            return;
        }
        AccessibilityNodeInfo scrollable = findScrollable(root);
        boolean ok = scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (!ok) swipeUp();
        scheduleNext(1400);
    }

    private List<AccessibilityNodeInfo> findLikelyContainers(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        collectRecyclerChildren(root, out, seen, 0);
        if (out.isEmpty()) collectClickable(root, out, seen, 0);
        return out;
    }

    private void collectRecyclerChildren(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, Set<Integer> seen, int depth) {
        if (node == null || depth > 18) return;
        String cls = node.getClassName() == null ? "" : node.getClassName().toString();
        if ((node.isScrollable() || cls.contains("RecyclerView")) && node.getChildCount() > 0) {
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                int id = System.identityHashCode(child);
                if (seen.add(id)) out.add(child);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) collectRecyclerChildren(node.getChild(i), out, seen, depth + 1);
    }

    private void collectClickable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, Set<Integer> seen, int depth) {
        if (node == null || depth > 18) return;
        if (node.isClickable() && node.getChildCount() > 0) {
            int id = System.identityHashCode(node);
            if (seen.add(id)) out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) collectClickable(node.getChild(i), out, seen, depth + 1);
    }

    private void collectTexts(AccessibilityNodeInfo node, JSONArray arr, int depth) {
        if (node == null || depth > 12 || arr.length() >= 30) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null) addUnique(arr, text.toString());
        if (desc != null) addUnique(arr, desc.toString());
        for (int i = 0; i < node.getChildCount(); i++) collectTexts(node.getChild(i), arr, depth + 1);
    }

    private void addUnique(JSONArray arr, String value) {
        String v = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (v.isEmpty() || v.length() > 180) return;
        for (int i = 0; i < arr.length(); i++) if (v.equals(arr.optString(i))) return;
        arr.put(v);
    }

    private boolean isNavigationOnly(String s) {
        String x = s.replace(" ", "");
        return x.length() < 12 || (x.contains("首页") && x.contains("我的") && x.contains("消息"));
    }

    private boolean containsText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(text);
        return list != null && !list.isEmpty();
    }

    private boolean clickText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(text);
        if (list == null) return false;
        for (AccessibilityNodeInfo node : list) {
            AccessibilityNodeInfo cur = node;
            for (int i = 0; i < 5 && cur != null; i++) {
                if (cur.isClickable() && cur.isVisibleToUser()) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                cur = cur.getParent();
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isScrollable()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = findScrollable(root.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private void swipeUp() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Path path = new Path();
        path.moveTo(w * 0.5f, h * 0.78f);
        path.lineTo(w * 0.5f, h * 0.30f);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 450);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private void scheduleNext(long ms) {
        if (scheduled) return;
        scheduled = true;
        handler.postDelayed(() -> { scheduled = false; step(); }, ms);
    }

    private void setStage(int stage, String msg) {
        prefs.edit().putInt(MainActivity.KEY_STAGE, stage).putString(MainActivity.KEY_STATUS, msg).apply();
    }

    private void status(String msg) {
        prefs.edit().putString(MainActivity.KEY_STATUS, msg).apply();
    }
}
