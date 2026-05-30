package com.dashcast.devtools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import com.dashcast.devtools.common.OtaUi;
import com.dashcast.devtools.fission.FissionActivity;
import com.dashcast.devtools.recon.ReconActivity;
import com.dashcast.devtools.sniffer.SnifferActivity;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnSniffer = findViewById(R.id.btn_main_sniffer);
        Button btnRecon   = findViewById(R.id.btn_main_recon);
        Button btnFission = findViewById(R.id.btn_main_fission);
        Button btnUpdate  = findViewById(R.id.btn_main_check_updates);
        TextView tvVer    = findViewById(R.id.tv_main_version);

        btnSniffer.setOnClickListener(v -> startActivity(new Intent(this, SnifferActivity.class)));
        btnRecon.setOnClickListener(v   -> startActivity(new Intent(this, ReconActivity.class)));
        btnFission.setOnClickListener(v -> startActivity(new Intent(this, FissionActivity.class)));
        btnUpdate.setOnClickListener(v  -> OtaUi.checkNow(this, /* notifyIfUpToDate= */ true));

        tvVer.setText(getString(R.string.main_version_fmt,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        // OTA auto-check on fresh launch only (not on rotation)
        if (savedInstanceState == null) {
            OtaUi.checkNow(this, /* notifyIfUpToDate= */ false);
        }
    }
}
