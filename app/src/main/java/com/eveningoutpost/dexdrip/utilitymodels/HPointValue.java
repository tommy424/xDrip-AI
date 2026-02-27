package com.eveningoutpost.dexdrip.utilitymodels;

import static com.eveningoutpost.dexdrip.utilitymodels.BgGraphBuilder.FUZZER;

import lecho.lib.hellocharts.model.PointValue;
import lombok.AllArgsConstructor;

/**
 * JamOrHam
 * <p>
 * Handle de-fuzzing and legacy calls
 */

@AllArgsConstructor
public class HPointValue extends PointValue {
    private String customTooltip;

    public HPointValue set(double x, double y) {
        super.set(x, y);
        return this;
    }

    public HPointValue(double x, float y) {
        super(x, y);
    }

    public HPointValue(double x, double y) {
        super(x, y);
    }

    public long getTimeStamp() {
        return (long) (getX() * FUZZER);
    }

    public HPointValue setCustomTooltip(final String customTooltip) {
        this.customTooltip = customTooltip;
        return this;
    }

    public String getCustomTooltip() {
        return customTooltip;
    }

}
