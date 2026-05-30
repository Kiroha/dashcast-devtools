package com.dashcast.devtools.dilink;

import com.dashcast.devtools.R;

/**
 * Sélecteur de drawable pour la pastille de statut d'une ligne {@code item_test_row}.
 * Utilisé par toutes les Activities DiLink afin d'avoir un retour visuel cohérent
 * (vert pour PASS, rouge pour FAIL, orange pour WARN/RUNNING, gris pour SKIPPED).
 */
final class DlPillBg {
    private DlPillBg() {}

    /**
     * @param statusName valeur de {@code enum.name()} parmi PASS/FAIL/WARN/RUNNING/SKIPPED/PENDING.
     * @return id de drawable à appliquer via {@code setBackgroundResource(...)}.
     */
    static int forStatus(String statusName) {
        if (statusName == null) return R.drawable.bg_test_pill;
        switch (statusName) {
            case "PASS":    return R.drawable.bg_test_pill_pass;
            case "FAIL":    return R.drawable.bg_test_pill_fail;
            case "WARN":
            case "RUNNING": return R.drawable.bg_test_pill_warn;
            case "SKIPPED": return R.drawable.bg_test_pill_skip;
            default:        return R.drawable.bg_test_pill;
        }
    }
}
