package com.dashcast.devtools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

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

        btnSniffer.setOnClickListener(v -> startActivity(new Intent(this, SnifferActivity.class)));
        btnRecon.setOnClickListener(v   -> startActivity(new Intent(this, ReconActivity.class)));
        btnFission.setOnClickListener(v -> startActivity(new Intent(this, FissionActivity.class)));
    }
}
