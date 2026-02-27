package com.eveningoutpost.dexdrip.insulin;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;

import static com.google.common.truth.Truth.assertThat;

public class LinearTrapezoidInsulinTest {

    private static LinearTrapezoidInsulin buildProfile() {
        final JsonObject curve = new JsonObject();
        curve.addProperty("onset", 2);
        curve.addProperty("peak", 45);
        curve.addProperty("duration", 300);

        return new LinearTrapezoidInsulin(
                "FIASP",
                "FIASP Trapezoid",
                new ArrayList<>(),
                "U100",
                curve);
    }

    @Test
    public void baseCurveRespectsConfiguredDuration() {
        final LinearTrapezoidInsulin insulin = buildProfile();
        assertThat(insulin.calculateIOB(299)).isGreaterThan(0d);
        assertThat(insulin.calculateIOB(300)).isEqualTo(0d);
        assertThat(insulin.calculateIOB(360)).isEqualTo(0d);
    }

    @Test
    public void overrideDurationRespectsRequestedEndTime() {
        final LinearTrapezoidInsulin insulin = buildProfile();
        final double dose = 1d;
        final double overrideMinutes = 180d;

        assertThat(insulin.calculateIOBContribution(179, dose, overrideMinutes)).isGreaterThan(0d);
        assertThat(insulin.calculateIOBContribution(180, dose, overrideMinutes)).isEqualTo(0d);
        assertThat(insulin.calculateActivityContribution(180, dose, overrideMinutes)).isEqualTo(0d);
    }
}
