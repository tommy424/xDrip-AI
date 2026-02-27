package com.eveningoutpost.dexdrip.insulin;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;

import static com.google.common.truth.Truth.assertThat;

public class WeibullInsulinTest {

    private static WeibullInsulin buildProfile() {
        final JsonObject curve = new JsonObject();
        curve.addProperty("onset", 5);
        curve.addProperty("duration", 300);
        curve.addProperty("shape", 2.1);
        curve.addProperty("scale", 95.0);

        return new WeibullInsulin(
                "FIASP",
                "FIASP Weibull",
                new ArrayList<>(),
                "U100",
                curve);
    }

    private static WeibullInsulin buildDoseParameterizedProfile() {
        final JsonObject curve = new JsonObject();
        curve.addProperty("duration", 360);
        curve.addProperty("k", 1.74176863);
        curve.addProperty("lambda_a", 112.623112);
        curve.addProperty("lambda_b", 210.417362);
        curve.addProperty("lag_u", 7.34062613);
        curve.addProperty("lag_v", -9.80150106);
        curve.addProperty("weight_kg", 70.0); // deterministic test scale

        return new WeibullInsulin(
                "FIASP",
                "FIASP Weibull Dose",
                new ArrayList<>(),
                "U100",
                curve);
    }

    @Test
    public void iobBoundsAreCorrect() {
        final WeibullInsulin insulin = buildProfile();

        assertThat(insulin.calculateIOB(0)).isEqualTo(1.0d);
        assertThat(insulin.calculateIOB(5)).isAtMost(1.0d);
        assertThat(insulin.calculateIOB(300)).isEqualTo(0.0d);
        assertThat(insulin.calculateIOB(360)).isEqualTo(0.0d);
    }

    @Test
    public void iobIsMonotonicNonIncreasing() {
        final WeibullInsulin insulin = buildProfile();
        double last = insulin.calculateIOB(0);
        for (int minute = 1; minute <= 300; minute++) {
            final double current = insulin.calculateIOB(minute);
            assertThat(current).isAtMost(last + 1e-9);
            last = current;
        }
    }

    @Test
    public void activityIntegratesCloseToOneUnitForU100() {
        final WeibullInsulin insulin = buildProfile();

        double area = 0.0d;
        for (int minute = 0; minute < 300; minute++) {
            area += insulin.calculateActivity(minute); // minute-sized rectangles
        }
        assertThat(area).isWithin(0.02d).of(1.0d);
    }

    @Test
    public void doseParameterizedAreaScalesLinearlyWithDose() {
        final WeibullInsulin insulin = buildDoseParameterizedProfile();
        final double lowDose = 3.5d;
        final double highDose = 14.0d;

        double lowArea = 0.0d;
        double highArea = 0.0d;
        for (int minute = 0; minute < 360; minute++) {
            lowArea += insulin.calculateActivityContribution(minute, lowDose);
            highArea += insulin.calculateActivityContribution(minute, highDose);
        }

        assertThat(highArea / lowArea).isWithin(0.08d).of(highDose / lowDose);
    }

    @Test
    public void doseParameterizedPeakShiftsWithDose() {
        final WeibullInsulin insulin = buildDoseParameterizedProfile();
        final double lowDose = 3.5d;
        final double highDose = 14.0d;

        int lowPeakMinute = 0;
        int highPeakMinute = 0;
        double lowPeak = -1d;
        double highPeak = -1d;

        for (int minute = 0; minute < 360; minute++) {
            final double low = insulin.calculateActivityContribution(minute, lowDose) / lowDose;
            final double high = insulin.calculateActivityContribution(minute, highDose) / highDose;
            if (low > lowPeak) {
                lowPeak = low;
                lowPeakMinute = minute;
            }
            if (high > highPeak) {
                highPeak = high;
                highPeakMinute = minute;
            }
        }

        assertThat(highPeakMinute).isGreaterThan(lowPeakMinute);
    }
}
