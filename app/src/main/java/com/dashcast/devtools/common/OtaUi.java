package com.dashcast.devtools.common;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dashcast.devtools.R;

/**
 * UI glue around {@link UpdateChecker}: shows a centered AlertDialog with
 * version, changelog (with light Markdown formatting), and a progress bar
 * during download. Extracted from DashCast MainActivity.
 */
public final class OtaUi {

    private OtaUi() {}

    /** One-shot: check + show full UI flow. */
    public static void checkNow(Activity activity, boolean notifyIfUpToDate) {
        UpdateChecker.checkUpdate(activity, makeListener(activity, notifyIfUpToDate));
    }

    public static UpdateChecker.ProgressListener makeListener(final Activity activity,
                                                              final boolean notifyIfUpToDate) {
        final AlertDialog[] dlgHolder = {null};
        final ProgressBar[] pbHolder  = {null};
        final TextView[]    pctHolder = {null};

        return new UpdateChecker.ProgressListener() {
            @Override
            public void onUpdateFound(final String version, final String changelog, final String downloadUrl) {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
                layout.setPadding(pad, pad, pad, pad / 2);

                final TextView tvVersion = new TextView(activity);
                tvVersion.setText(activity.getString(R.string.ota_version_label, version));
                tvVersion.setTextSize(16);
                tvVersion.setPadding(pad, 0, pad, pad / 2);
                tvVersion.setTextColor(0xFF4FC3F7);
                layout.addView(tvVersion);

                final ScrollView sv = new ScrollView(activity);
                TextView tvChangelog = new TextView(activity);
                tvChangelog.setText(renderMarkdown(changelog));
                tvChangelog.setTextSize(13);
                tvChangelog.setPadding(pad, 0, pad, pad);
                tvChangelog.setTextColor(0xFFEEEEEE);
                sv.addView(tvChangelog);

                LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (activity.getResources().getDisplayMetrics().density * 250));
                layout.addView(sv, svParams);

                final LinearLayout progressLayout = new LinearLayout(activity);
                progressLayout.setOrientation(LinearLayout.VERTICAL);
                progressLayout.setPadding(pad, pad, pad, 0);
                progressLayout.setVisibility(View.GONE);

                ProgressBar pb = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(0);
                progressLayout.addView(pb);
                pbHolder[0] = pb;

                TextView tvPct = new TextView(activity);
                tvPct.setText(activity.getString(R.string.ota_progress_percent, 0));
                tvPct.setGravity(Gravity.CENTER);
                tvPct.setTextSize(12);
                tvPct.setTextColor(0xFF888888);
                progressLayout.addView(tvPct);
                pctHolder[0] = tvPct;

                layout.addView(progressLayout);

                final UpdateChecker.ProgressListener self = this;
                dlgHolder[0] = new AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.ota_dialog_title))
                        .setView(layout)
                        .setCancelable(false)
                        .setPositiveButton(activity.getString(R.string.ota_btn_update_now), null)
                        .setNegativeButton(activity.getString(R.string.ota_btn_later), (d, w) -> d.dismiss())
                        .create();

                dlgHolder[0].setOnShowListener(dialog -> {
                    Button pos = dlgHolder[0].getButton(AlertDialog.BUTTON_POSITIVE);
                    pos.setOnClickListener(v -> {
                        pos.setEnabled(false);
                        dlgHolder[0].getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                        sv.setVisibility(View.GONE);
                        tvVersion.setText(activity.getString(R.string.ota_downloading));
                        progressLayout.setVisibility(View.VISIBLE);
                        UpdateChecker.startDownload(activity, downloadUrl, self);
                    });
                });
                dlgHolder[0].show();
            }

            @Override
            public void onDownloadProgress(int percent) {
                if (pbHolder[0] == null) return;
                if (percent < 0) {
                    pbHolder[0].setIndeterminate(true);
                    if (pctHolder[0] != null)
                        pctHolder[0].setText(activity.getString(R.string.ota_progress_unknown));
                } else {
                    pbHolder[0].setIndeterminate(false);
                    pbHolder[0].setProgress(percent);
                    if (pctHolder[0] != null)
                        pctHolder[0].setText(activity.getString(R.string.ota_progress_percent, percent));
                }
            }

            @Override
            public void onInstalling() {
                if (dlgHolder[0] != null) {
                    dlgHolder[0].dismiss();
                    dlgHolder[0] = null;
                }
            }

            @Override
            public void onUpToDate() {
                if (notifyIfUpToDate) {
                    Toast.makeText(activity.getApplicationContext(),
                            activity.getString(R.string.ota_up_to_date),
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                if (dlgHolder[0] != null) {
                    dlgHolder[0].dismiss();
                    dlgHolder[0] = null;
                }
                if (notifyIfUpToDate) {
                    Toast.makeText(activity.getApplicationContext(),
                            activity.getString(R.string.ota_error_fmt, message),
                            Toast.LENGTH_LONG).show();
                }
                AppLogger.e("OTA", "error: " + message);
            }
        };
    }

    // ── Markdown renderer for the changelog ──────────────────────────────────

    private static CharSequence renderMarkdown(String raw) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = raw.split("\n");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            boolean bold = false;
            float relSize = 0f;
            if (line.startsWith("## ")) {
                line = line.substring(3);
                bold = true; relSize = 1.15f;
            } else if (line.startsWith("### ")) {
                line = line.substring(4);
                bold = true;
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                line = "\u2022 " + line.substring(2);
            }
            int lineStart = sb.length();
            appendWithInlineBold(sb, line);
            int lineEnd = sb.length();
            if (bold) {
                sb.setSpan(new StyleSpan(Typeface.BOLD),
                        lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (relSize > 0f) {
                sb.setSpan(new RelativeSizeSpan(relSize),
                        lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (li < lines.length - 1) sb.append('\n');
        }
        return sb;
    }

    private static void appendWithInlineBold(SpannableStringBuilder sb, String text) {
        int i = 0;
        while (i < text.length()) {
            int boldStart = text.indexOf("**", i);
            if (boldStart < 0) { sb.append(text.substring(i)); break; }
            sb.append(text.substring(i, boldStart));
            int boldEnd = text.indexOf("**", boldStart + 2);
            if (boldEnd < 0) { sb.append(text.substring(boldStart)); break; }
            int spanStart = sb.length();
            sb.append(text.substring(boldStart + 2, boldEnd));
            sb.setSpan(new StyleSpan(Typeface.BOLD),
                    spanStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = boldEnd + 2;
        }
    }
}
