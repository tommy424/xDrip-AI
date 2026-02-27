package com.eveningoutpost.dexdrip.insulin;

import com.google.gson.JsonObject;

import java.util.ArrayList;

public class LinearTrapezoidInsulin extends Insulin {
    /// curvedata and all timestamps are defined in minutes
    private final long onset;  // when does profile activity starts
    private final long t1;     // when does activity reaches max
    private final long t2;     // when does activity leaves max
    private final long t3;     // when does activity ends

    public LinearTrapezoidInsulin(String n, String dn, ArrayList<String> ppn, String c, JsonObject curveData) {
        super(n, dn, ppn, c, curveData);

        onset = curveData.get("onset").getAsLong();
        if (curveData.get("peak").getAsString().contains("-")) {
            t1 = Integer.parseInt(curveData.get("peak").getAsString().split("-")[0]);
            t2 = Integer.parseInt(curveData.get("peak").getAsString().split("-")[1]);
        } else {
            t1 = Integer.parseInt(curveData.get("peak").getAsString());
            t2 = t1;
        }
        t3 = curveData.get("duration").getAsLong();

        maxEffect = t3;
    }

    @Override
    public double calculateIOB(final long t) {
        return calculateIOBWithDurationOverride(t, -1d);
    }

    @Override
    public double calculateActivity(final long t) {
        return calculateActivityWithDurationOverride(t, -1d);
    }

    @Override
    public double calculateIOBContribution(final long time, final double doseUnits, final double durationOverrideMinutes) {
        return doseUnits * Math.abs(calculateIOBWithDurationOverride(time, durationOverrideMinutes));
    }

    @Override
    public double calculateActivityContribution(final long time, final double doseUnits, final double durationOverrideMinutes) {
        return doseUnits * Math.abs(calculateActivityWithDurationOverride(time, durationOverrideMinutes));
    }

    private double calculateIOBWithDurationOverride(final long t, final double durationOverrideMinutes) {
        final CurvePoints curve = resolveCurve(durationOverrideMinutes);

        if ((0 <= t) && (t < curve.onset))
            return 1.0;
        else if ((curve.onset <= t) && (t < curve.t1))
            return 1.0 - 0.5 * (t - curve.onset) * (t - curve.onset) * curve.max / (curve.t1 - curve.onset);
        else if ((curve.t1 <= t) && (t < curve.t2))
            return 1.0 + 0.5 * curve.max * (curve.t1 - curve.onset) - curve.max * (t - curve.onset);
        else if ((curve.t2 <= t) && (t < curve.t3))
            return 0.5 * (curve.t3 - t) * (curve.t3 - t) * curve.max / (curve.t3 - curve.t2);
        else return 0;
    }

    private double calculateActivityWithDurationOverride(final long t, final double durationOverrideMinutes) {
        final CurvePoints curve = resolveCurve(durationOverrideMinutes);

        if ((0 <= t) && (t < curve.onset))
            return 0.0;
        else if ((curve.onset <= t) && (t < curve.t1))
            return concentration * (t - curve.onset) * curve.max / (curve.t1 - curve.onset);
        else if ((curve.t1 <= t) && (t < curve.t2))
            return concentration * curve.max;
        else if ((curve.t2 <= t) && (t < curve.t3))
            return concentration * (t - curve.t3) * curve.max / (curve.t3 - curve.t2);
        else return 0;
    }

    private CurvePoints resolveCurve(final double durationOverrideMinutes) {
        if (durationOverrideMinutes <= 0d) {
            return buildCurve(onset, t1, t2, t3);
        }

        // Scale all trapezoid time anchors proportionally to preserve curve shape.
        final double scale = durationOverrideMinutes / t3;
        final long sOnset = Math.max(0L, Math.round(onset * scale));
        final long sT1 = Math.max(sOnset + 1L, Math.round(t1 * scale));
        final long sT2 = Math.max(sT1, Math.round(t2 * scale));
        final long sT3 = Math.max(sT2 + 1L, Math.round(durationOverrideMinutes));
        return buildCurve(sOnset, sT1, sT2, sT3);
    }

    private CurvePoints buildCurve(final long cOnset, final long cT1, final long cT2, final long cT3) {
        final double cMax = 2.0 / (cT2 - cT1 + cT3 - cOnset);
        return new CurvePoints(cOnset, cT1, cT2, cT3, cMax);
    }

    private static final class CurvePoints {
        final long onset;
        final long t1;
        final long t2;
        final long t3;
        final double max;

        CurvePoints(final long onset, final long t1, final long t2, final long t3, final double max) {
            this.onset = onset;
            this.t1 = t1;
            this.t2 = t2;
            this.t3 = t3;
            this.max = max;
        }
    }
}
