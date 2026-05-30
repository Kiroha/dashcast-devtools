package com.dashcast.devtools;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.dashcast.devtools.common.OtaUi;
import com.dashcast.devtools.dilink.DiLink2Activity;
import com.dashcast.devtools.dilink.DiLink3Activity;
import com.dashcast.devtools.dilink.DiLink4Activity;
import com.dashcast.devtools.dilink.DiLink50Activity;
import com.dashcast.devtools.dilink.DiLink51Activity;
import com.dashcast.devtools.sniffer.SnifferActivity;

/**
 * Main hub — Material 3 NavRail (left) + module cards (right),
 * mirroring the DashCast main screen logic.
 */
public class MainActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Nav rail entries
        findViewById(R.id.nav_sniffer).setOnClickListener(v ->
                startActivity(new Intent(this, SnifferActivity.class)));
        findViewById(R.id.nav_dl2).setOnClickListener(v ->
                startActivity(new Intent(this, DiLink2Activity.class)));
        findViewById(R.id.nav_dl3).setOnClickListener(v ->
                startActivity(new Intent(this, DiLink3Activity.class)));
        findViewById(R.id.nav_dl4).setOnClickListener(v ->
                startActivity(new Intent(this, DiLink4Activity.class)));
        findViewById(R.id.nav_dl50).setOnClickListener(v ->
                startActivity(new Intent(this, DiLink50Activity.class)));
        findViewById(R.id.nav_dl51).setOnClickListener(v ->
                startActivity(new Intent(this, DiLink51Activity.class)));

        // Module cards (same destinations, larger touch target)
        findViewById(R.id.card_sniffer).setOnClickListener(v ->
                startActivity(new Intent(this, SnifferActivity.class)));
        findViewById(R.id.card_dl2).setOnClickListener(v ->
                startActivity(new Intent(this, DiLink2Activity.class)));
        findViewById(R.id.card_dl3).setOnClickListener(v ->
                startActivity(new Intent(this, DiLink3Activity.class)));
        findViewById(R.id.card_dl4).setOnClickListener(v ->
                startActivity(new Intent(this, DiLink4Activity.class)));
        findViewById(R.id.card_dl50).setOnClickListener(v ->
                startActivity(new Intent(this, DiLink50Activity.class)));
        findViewById(R.id.card_dl51).setOnClickListener(v ->
                startActivity(new Intent(this, DiLink51Activity.class)));

        // Overflow: ⋮ button at the bottom of the rail + long-press on logo
        ImageView overflow = findViewById(R.id.nav_overflow);
        overflow.setOnClickListener(this::showOverflowMenu);
        findViewById(R.id.iv_nav_logo).setOnLongClickListener(v -> {
            showOverflowMenu(v);
            return true;
        });

        TextView tvVer = findViewById(R.id.tv_main_version);
        tvVer.setText(getString(R.string.main_version_fmt,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        // OTA auto-check on fresh launch only (not on rotation)
        if (savedInstanceState == null) {
            OtaUi.checkNow(this, /* notifyIfUpToDate= */ false);
            // Surface the "Allow USB debugging?" popup at launch so the
            // user accepts it once (and the key is whitelisted by adbd)
            // BEFORE they open Sniffer / Recon / Fission.
            com.dashcast.devtools.common.AdbClient.warmUp(this);
        }
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, getString(R.string.menu_check_updates));
        popup.getMenu().add(0, 2, 1, getString(R.string.menu_about));
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    OtaUi.checkNow(this, /* notifyIfUpToDate= */ true);
                    return true;
                case 2:
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle(R.string.app_name)
                            .setMessage(getString(R.string.about_text_fmt,
                                    BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }
}
