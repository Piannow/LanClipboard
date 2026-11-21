package com.lanclip.sync;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * 仅在本应用窗口内执行全选复制，不读取其他应用内容。
 */
public class ClipAccessibilityService extends AccessibilityService {

    private static final String TAG = "LanClipA11y";
    private static volatile ClipAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public static boolean isConnected() {
        return instance != null;
    }

    public static void performCopy(long delayMs) {
        ClipAccessibilityService service = instance;
        if (service == null) {
            return;
        }
        service.handler.postDelayed(service::copyFromBridge, delayMs);
    }

    private void copyFromBridge() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return;
            }
            AccessibilityNodeInfo target = null;
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId("com.lanclip.sync:id/bridge_edit");
            if (nodes != null && !nodes.isEmpty()) {
                target = nodes.get(0);
            }
            if (target == null) {
                return;
            }
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            CharSequence text = target.getText();
            int length = text == null ? 0 : text.length();
            Bundle selection = new Bundle();
            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length);
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_COPY);
            Log.i(TAG, "select-all copy result=" + ok + ", length=" + length);
        } catch (Throwable t) {
            Log.w(TAG, "copy failed", t);
        }
    }
}
