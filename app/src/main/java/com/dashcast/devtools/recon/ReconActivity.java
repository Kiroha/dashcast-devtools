package com.dashcast.devtools.recon;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.dashcast.devtools.R;

/**
 * ReconActivity — placeholder.
 *
 * <p>TODO (next iteration): transplant {@code Dl5ClusterReconRunner} from
 * DashCast (com.byd.dashcast.dilink5.Dl5ClusterReconRunner — 1357 LoC),
 * along with its dep {@code DiLink5TestRunner} (data model), {@code Platform}
 * (DL3/DL5 detection), and the read-only test catalogue.
 *
 * <p>Once transplanted, this Activity should show a list of test rows
 * (PASS/WARN/FAIL/SKIPPED with per-row detail), a "Run all" button, and a
 * "Send via Telegram/Share" export.
 */
public class ReconActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_placeholder);
        TextView tv = findViewById(R.id.tv_placeholder);
        tv.setText(R.string.recon_placeholder);
    }
}
