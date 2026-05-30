package com.dashcast.devtools.fission;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.dashcast.devtools.R;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * FissionActivity — placeholder.
 *
 * <p>TODO (next iteration): transplant {@code Dl5VdTestRunner} + {@code MirrorDaemon}.
 */
public class FissionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_placeholder);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.main_btn_fission);
        toolbar.setNavigationOnClickListener(v -> finish());
        TextView tv = findViewById(R.id.tv_placeholder);
        tv.setText(R.string.fission_placeholder);
    }
}
