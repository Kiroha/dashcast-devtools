package com.dashcast.devtools.fission;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.dashcast.devtools.R;

/**
 * FissionActivity — placeholder.
 *
 * <p>TODO (next iteration): transplant {@code Dl5VdTestRunner} (VirtualDisplay
 * → MirrorDaemon projection pipeline validator, 554 LoC) plus
 * {@code MirrorDaemon} (474 LoC, daemon bootstrap via ADB → uid 2000 →
 * SurfaceControl). The whole pipeline requires {@code AdbClient} (already in
 * place) plus a minimal {@code Platform} detector for DL5 gating.
 *
 * <p>Once transplanted, this Activity should expose a "Run V01..V07" battery
 * with interactive Yes/No prompts (e.g. "Is the app visible on cluster and
 * well-proportioned?").
 */
public class FissionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_placeholder);
        TextView tv = findViewById(R.id.tv_placeholder);
        tv.setText(R.string.fission_placeholder);
    }
}
