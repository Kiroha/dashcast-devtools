package com.dashcast.devtools.dilink;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.dashcast.devtools.R;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * DiLink51Activity — placeholder.
 *
 * <p>TODO: outils RE spécifiques à la plateforme DiLink 5.1 (DL5.1).
 */
public class DiLink51Activity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_placeholder);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.dilink51_name);
        toolbar.setNavigationOnClickListener(v -> finish());
        TextView tv = findViewById(R.id.tv_placeholder);
        tv.setText(R.string.dl51_placeholder);
    }
}
