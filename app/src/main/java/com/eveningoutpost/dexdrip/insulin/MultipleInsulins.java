package com.eveningoutpost.dexdrip.insulin;

public class MultipleInsulins {

    // "Multiple" now means more than one enabled profile; no separate switch required.
    public static boolean isEnabled() {
        return InsulinManager.getEnabledProfileCount() > 1;
    }

    // Profile-based insulin modeling should be active whenever at least one profile exists.
    public static boolean useProfileModeling() {
        return InsulinManager.getEnabledProfileCount() > 0;
    }

    public static boolean useBasalActivity() {
        return com.eveningoutpost.dexdrip.utilitymodels.Pref.getBooleanDefaultFalse("multiple_insulin_use_basal_activity");
    }

}
