package com.lanclip.sync;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

public class RoleActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_role);

        View phone = findViewById(R.id.btn_phone);
        View box = findViewById(R.id.btn_box);
        phone.setOnClickListener(v -> startActivity(new Intent(this, SenderActivity.class)));
        box.setOnClickListener(v -> startActivity(new Intent(this, ReceiverActivity.class)));

        // 机顶盒 / TV 上默认聚焦「接收端」，手机默认聚焦「发送端」；避免遥控器无焦点
        PackageManager pm = getPackageManager();
        boolean tv = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)))
                || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        (tv ? box : phone).requestFocus();
    }
}
