package com.lanclip.sync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.EditText;

/**
 * 1px 透明桥接窗口：文字放进 EditText，由无障碍服务执行「全选 → 复制」。
 * 同时直接写入系统剪切板作为兜底。
 */
public class CopyBridgeActivity extends Activity {

    private EditText edit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        setContentView(R.layout.activity_bridge);
        edit = findViewById(R.id.bridge_edit);
        apply(getIntent());
        ClipAccessibilityService.performCopy(320);
        edit.postDelayed(this::finish, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 某些定制系统会在窗口显示后才允许获取焦点与选择文本。
        edit.requestFocus();
        edit.setSelection(0, edit.getText().length());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        apply(intent);
        ClipAccessibilityService.performCopy(280);
    }

    private void apply(Intent intent) {
        String text = intent == null ? "" : intent.getStringExtra("text");
        if (text == null) {
            text = "";
        }
        edit.setText(text);
        edit.requestFocus();
        edit.setSelection(0, text.length());
        Util.setClipText(this, text);
    }
}
