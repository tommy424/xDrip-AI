package com.eveningoutpost.dexdrip.insulin;

import com.eveningoutpost.dexdrip.utilitymodels.Constants;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;
import com.google.gson.JsonObject;

import java.util.ArrayList;

/**
 * Weibull-distributed insulin action profile.
 *
 * The model is truncated at duration and normalized so:
 * - IOB(t=0) = 1.0
 * - IOB(t=duration) = 0.0
 * - Integral(activity from onset to duration) = concentration
 */
public class WeibullInsulin extends Insulin {

    private final double durationMinutes;
    private final double shapeK;
    private final boolean doseParameterized;

    // Static Weibull mode (time-only parameters)
    private final double onsetMinutes;
    private final double scaleLambdaMinutes;

    // Dose-parameterized Weibull mode (dose in U/kg)
    private final double lambdaA;
    private final double lambdaB;
    private final double lagU;
    private final double lagV;
    private final double configuredDoseScaleUPerKgPerUnit;
    private final double configuredWeightKg;

    public WeibullInsulin(String n, String dn, ArrayList<String> ppn, String c, JsonObject curveData) {
        super(n, dn, ppn, c, curveData);

        durationMinutes = getDouble(curveData, "duration", 300d);
        shapeK = getDoubleWithAliases(curveData, new String[]{"shape", "k"}, 2d);

        if (durationMinutes <= 0d) {
            throw new IllegalArgumentException("duration must be > 0");
        }
        if (shapeK <= 1.0d) {
            throw new IllegalArgumentException("shape must be > 1 to avoid singular activity at onset");
        }

        doseParameterized = curveData != null && (curveData.has("lambda_a") || curveData.has("lambda_b"));
        if (doseParameterized) {
            onsetMinutes = 0d;
            scaleLambdaMinutes = 0d;

            lambdaA = getDouble(curveData, "lambda_a", 1d);
            lambdaB = getDouble(curveData, "lambda_b", 0d);
            lagU = getDouble(curveData, "lag_u", 0d);
            lagV = getDouble(curveData, "lag_v", 0d);
            configuredDoseScaleUPerKgPerUnit = getDouble(curveData, "dose_scale_u_per_kg_per_unit", 0d);
            configuredWeightKg = getDoubleWithAliases(curveData,
                    new String[]{"reference_weight_kg", "body_weight_kg", "weight_kg"}, 0d);
        } else {
            onsetMinutes = getDouble(curveData, "onset", 0d);
            scaleLambdaMinutes = getDoubleWithAliases(curveData, new String[]{"scale", "lambda"}, 60d);
            if (durationMinutes <= onsetMinutes) {
                throw new IllegalArgumentException("duration must be greater than onset");
            }
            if (scaleLambdaMinutes <= 0d) {
                throw new IllegalArgumentException("scale must be > 0");
            }

            lambdaA = 0d;
            lambdaB = 0d;
            lagU = 0d;
            lagV = 0d;
            configuredDoseScaleUPerKgPerUnit = 0d;
            configuredWeightKg = 0d;
        }

        maxEffect = Math.round(durationMinutes);
    }

    @Override
    public double calculateIOB(long tMinutes) {
        return iobFractionAtTime(tMinutes, 1d);
    }

    @Override
    public double calculateActivity(long tMinutes) {
        return activityPerUnitAtTime(tMinutes, 1d);
    }

    @Override
    public double calculateIOBContribution(final long tMinutes, final double doseUnits) {
        if (doseUnits <= 0d) {
            return 0.0d;
        }
        return doseUnits * Math.abs(iobFractionAtTime(tMinutes, doseUnits));
    }

    @Override
    public double calculateActivityContribution(final long tMinutes, final double doseUnits) {
        if (doseUnits <= 0d) {
            return 0.0d;
        }
        return doseUnits * Math.abs(activityPerUnitAtTime(tMinutes, doseUnits));
    }

    private double iobFractionAtTime(final long tMinutes, final double doseUnits) {
        final CurveState curve = resolveCurveState(doseUnits);

        if (tMinutes < curve.lagMinutes) {
            return 1.0d;
        }
        if (tMinutes >= durationMinutes) {
            return 0.0d;
        }
        if (curve.maxShiftedMinutes <= 0d || curve.normalizationFactor <= 0d) {
            return 0.0d;
        }

        final double shifted = tMinutes - curve.lagMinutes;
        return (survival(shifted, curve.lambdaMinutes) - survival(curve.maxShiftedMinutes, curve.lambdaMinutes))
                / curve.normalizationFactor;
    }

    private double activityPerUnitAtTime(final long tMinutes, final double doseUnits) {
        final CurveState curve = resolveCurveState(doseUnits);

        if (tMinutes < curve.lagMinutes || tMinutes >= durationMinutes) {
            return 0.0d;
        }
        if (curve.maxShiftedMinutes <= 0d || curve.normalizationFactor <= 0d) {
            return 0.0d;
        }

        final double shifted = tMinutes - curve.lagMinutes;
        return concentration * (density(shifted, curve.lambdaMinutes) / curve.normalizationFactor);
    }

    private CurveState resolveCurveState(final double doseUnits) {
        final double lambdaMinutes;
        final double lagMinutes;

        if (doseParameterized) {
            final double doseScaleUPerKgPerUnit = resolveDoseScaleUPerKgPerUnit();
            final double doseUPerKg = Math.max(0d, doseUnits) * doseScaleUPerKgPerUnit;
            lambdaMinutes = Math.max(1e-6d, lambdaA + (lambdaB * doseUPerKg));
            lagMinutes = Math.max(0d, lagU + (lagV * doseUPerKg));
        } else {
            lambdaMinutes = scaleLambdaMinutes;
            lagMinutes = onsetMinutes;
        }

        final double maxShiftedMinutes = durationMinutes - lagMinutes;
        final double normalizationFactor = (maxShiftedMinutes > 0d)
                ? (1.0d - survival(maxShiftedMinutes, lambdaMinutes))
                : 0d;

        return new CurveState(lambdaMinutes, lagMinutes, maxShiftedMinutes, normalizationFactor);
    }

    /**
     * Resolve dose scaling in U/kg per insulin unit.
     *
     * Priority:
     * 1) curve data explicit scale: dose_scale_u_per_kg_per_unit
     * 2) runtime preference override: fiasp_weibull_dose_scale_u_per_kg_per_unit
     * 3) curve or preference weight in kg (scale = 1/weight)
     * 4) fallback estimate from DIA + ISF profile values
     */
    private double resolveDoseScaleUPerKgPerUnit() {
        if (configuredDoseScaleUPerKgPerUnit > 0d) {
            return configuredDoseScaleUPerKgPerUnit;
        }

        final Double prefScale = parsePositiveDouble(Pref.getString("fiasp_weibull_dose_scale_u_per_kg_per_unit", ""));
        if (prefScale != null) {
            return prefScale;
        }

        final double weightKg;
        if (configuredWeightKg > 0d) {
            weightKg = configuredWeightKg;
        } else {
            final Double prefWeight = parsePositiveDouble(Pref.getString("fiasp_weibull_weight_kg", ""));
            weightKg = (prefWeight != null) ? prefWeight : 0d;
        }
        if (weightKg > 0d) {
            return 1d / weightKg;
        }

        return estimateDoseScaleFromProfile();
    }

    /**
     * Heuristic fallback that infers a dose scale from user DIA + ISF.
     * Keeps scale within physiologic bounds and falls back to 70 kg equivalent.
     */
    private double estimateDoseScaleFromProfile() {
        final double defaultScale = 1d / 70d;
        try {
            final double diaHours = Math.max(1.5d, parseOrDefault(Pref.getString("xplus_insulin_dia", "3.0"), 3.0d));
            double isf = Math.max(5d, parseOrDefault(Pref.getString("profile_insulin_sensitivity_default", "54"), 54d));
            final boolean mgdl = "mgdl".equals(Pref.getString("units", "mgdl"));
            if (!mgdl) {
                isf *= Constants.MMOLL_TO_MGDL;
            }

            // Legacy model uses peak ~= 75min at DIA=3h; preserve similar characteristic response.
            final double targetPeakMin = 75d * (diaHours / 3d);
            final double peakFactor = Math.pow((shapeK - 1d) / shapeK, 1d / shapeK);
            final double denom = lagV + (lambdaB * peakFactor);
            if (Math.abs(denom) < 1e-9d) {
                return defaultScale;
            }

            // Solve approximate u/kg producing target peak for a representative correction bolus.
            final double dRefUPerKg = (targetPeakMin - lagU - (lambdaA * peakFactor)) / denom;
            final double representativeUnits = clamp(30d / isf, 1d, 15d); // ~30 mg/dL correction dose
            final double inferredScale = dRefUPerKg / representativeUnits;

            if (Double.isFinite(inferredScale) && inferredScale > 0d) {
                return clamp(inferredScale, 1d / 200d, 1d / 30d);
            }
        } catch (Exception ignored) {
            // Use default
        }
        return defaultScale;
    }

    private double survival(final double shiftedMinutes, final double lambdaMinutes) {
        return Math.exp(-Math.pow(shiftedMinutes / lambdaMinutes, shapeK));
    }

    private double density(final double shiftedMinutes, final double lambdaMinutes) {
        final double scaled = shiftedMinutes / lambdaMinutes;
        return (shapeK / lambdaMinutes) * Math.pow(scaled, shapeK - 1.0d) * Math.exp(-Math.pow(scaled, shapeK));
    }

    private static double getDouble(final JsonObject json, final String key, final double fallback) {
        if (json != null && json.has(key)) {
            return json.get(key).getAsDouble();
        }
        return fallback;
    }

    private static double getDoubleWithAliases(final JsonObject json, final String[] keys, final double fallback) {
        if (json != null) {
            for (String key : keys) {
                if (json.has(key)) {
                    return json.get(key).getAsDouble();
                }
            }
        }
        return fallback;
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double parseOrDefault(final String value, final double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Double parsePositiveDouble(final String value) {
        try {
            final double v = Double.parseDouble(value);
            if (Double.isFinite(v) && v > 0d) {
                return v;
            }
        } catch (Exception ignored) {
            //
        }
        return null;
    }

    private static final class CurveState {
        private final double lambdaMinutes;
        private final double lagMinutes;
        private final double maxShiftedMinutes;
        private final double normalizationFactor;

        private CurveState(final double lambdaMinutes, final double lagMinutes,
                           final double maxShiftedMinutes, final double normalizationFactor) {
            this.lambdaMinutes = lambdaMinutes;
            this.lagMinutes = lagMinutes;
            this.maxShiftedMinutes = maxShiftedMinutes;
            this.normalizationFactor = normalizationFactor;
        }
    }
}
