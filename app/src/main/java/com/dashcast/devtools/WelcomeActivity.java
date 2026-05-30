package com.dashcast.devtools;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * WelcomeActivity — shown only on the first launch.
 *
 * Propose le choix de langue. Une fois la langue sélectionnée, la locale
 * est appliquée, le flag "setup_done" est sauvegardé, et MainActivity est lancée.
 * Aux lancements suivants, MainActivity démarre directement.
 */
@android.annotation.SuppressLint("SetTextI18n")
public class WelcomeActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Already configured → go directly to MainActivity
        if (LocaleHelper.isSetupDone(this)) {
            startMainActivity();
            return;
        }

        setContentView(R.layout.activity_welcome);

        // Version subtitle
        TextView subtitle = findViewById(R.id.tv_welcome_subtitle);
        if (subtitle != null) {
            subtitle.setText("DashCast DevTools · v" + BuildConfig.VERSION_NAME);
        }

        setLanguageButton(R.id.btn_lang_fr, LocaleHelper.LANG_FR);
        setLanguageButton(R.id.btn_lang_en, LocaleHelper.LANG_EN);
        setLanguageButton(R.id.btn_lang_de, LocaleHelper.LANG_DE);
        setLanguageButton(R.id.btn_lang_tr, LocaleHelper.LANG_TR);
        setLanguageButton(R.id.btn_lang_it, LocaleHelper.LANG_IT);
        setLanguageButton(R.id.btn_lang_es, LocaleHelper.LANG_ES);
        setLanguageButton(R.id.btn_lang_ru, LocaleHelper.LANG_RU);
        setLanguageButton(R.id.btn_lang_uk, LocaleHelper.LANG_UK);
        setLanguageButton(R.id.btn_lang_ar, LocaleHelper.LANG_AR);
        setLanguageButton(R.id.btn_lang_uz, LocaleHelper.LANG_UZ);
        setLanguageButton(R.id.btn_lang_kk, LocaleHelper.LANG_KK);
        setLanguageButton(R.id.btn_lang_be, LocaleHelper.LANG_BE);
        setLanguageButton(R.id.btn_lang_pl, LocaleHelper.LANG_PL);

        // "Continue without changing" — keep current locale, mark setup done
        View btnContinue = findViewById(R.id.btn_continue_without_change);
        if (btnContinue != null) {
            btnContinue.setOnClickListener(v -> {
                LocaleHelper.markSetupDone(WelcomeActivity.this);
                startMainActivity();
            });
        }
    }

    private void setLanguageButton(int buttonId, final String lang) {
        Button button = findViewById(buttonId);
        if (button == null) return;
        button.setOnClickListener(v -> selectLanguage(lang));
    }

    private void selectLanguage(String lang) {
        LocaleHelper.setLocale(this, lang);
        LocaleHelper.markSetupDone(this);
        startMainActivity();
    }

    private void startMainActivity() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
