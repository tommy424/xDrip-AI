package com.eveningoutpost.dexdrip.utilitymodels;

import com.eveningoutpost.dexdrip.models.Iob;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Prediction math extracted from BgGraphBuilder so it can evolve independently
 * of chart rendering concerns.
 */
public final class PredictiveSimulationEngine {

    private static final double INITIAL_PREDICT_WEIGHT = 0.1d;
    private static final double PREDICT_WEIGHT_MULTIPLIER = 2.5d;
    private static final boolean MOMENTUM_SMOOTHING = true;

    private PredictiveSimulationEngine() {
    }

    public interface MomentumPredictor {
        double predict(long timestampMs);
    }

    public static final class Step {
        public final long timestampMs;
        public final long timestampFuzzed;
        public final double iob;
        public final double cob;
        public final double insulinImpact;
        public final double carbImpact;
        public final boolean futurePredictionStep;
        public final boolean hasPolyPrediction;
        public final double polyPrediction;
        public final boolean hasPredictedBg;
        public final double predictedBg;

        private Step(long timestampMs,
                     long timestampFuzzed,
                     double iob,
                     double cob,
                     double insulinImpact,
                     double carbImpact,
                     boolean futurePredictionStep,
                     boolean hasPolyPrediction,
                     double polyPrediction,
                     boolean hasPredictedBg,
                     double predictedBg) {
            this.timestampMs = timestampMs;
            this.timestampFuzzed = timestampFuzzed;
            this.iob = iob;
            this.cob = cob;
            this.insulinImpact = insulinImpact;
            this.carbImpact = carbImpact;
            this.futurePredictionStep = futurePredictionStep;
            this.hasPolyPrediction = hasPolyPrediction;
            this.polyPrediction = polyPrediction;
            this.hasPredictedBg = hasPredictedBg;
            this.predictedBg = predictedBg;
        }
    }

    public static final class Result {
        public final List<Step> steps;
        public final double finalPredictedBg;
        public final long lastFuzzedTimestamp;

        private Result(List<Step> steps, double finalPredictedBg, long lastFuzzedTimestamp) {
            this.steps = steps;
            this.finalPredictedBg = finalPredictedBg;
            this.lastFuzzedTimestamp = lastFuzzedTimestamp;
        }
    }

    public static Result run(final List<Iob> iobInfo,
                             final long lastTimestampFuzzed,
                             final double initialPredictedBg,
                             final boolean useMomentum,
                             final MomentumPredictor momentumPredictor,
                             final int fuzzer) {
        if (iobInfo == null || iobInfo.isEmpty()) {
            return new Result(Collections.<Step>emptyList(), initialPredictedBg, 0);
        }

        final List<Step> steps = new ArrayList<>();
        double predictedBg = initialPredictedBg;
        double predictWeight = INITIAL_PREDICT_WEIGHT;
        long lastFuzzedTimestamp = 0;

        for (Iob iob : iobInfo) {
            if (!hasRenderableData(iob)) {
                continue;
            }

            final long timestampFuzzed = iob.timestamp / fuzzer;
            lastFuzzedTimestamp = timestampFuzzed;
            final boolean futurePredictionStep = timestampFuzzed > lastTimestampFuzzed;

            boolean hasPolyPrediction = false;
            double polyPrediction = 0;
            boolean hasPredictedBg = false;
            double predictedBgFinal = 0;

            if (futurePredictionStep) {
                if (momentumPredictor != null) {
                    try {
                        polyPrediction = momentumPredictor.predict(iob.timestamp);
                        hasPolyPrediction = true;
                    } catch (Exception e) {
                        hasPolyPrediction = false;
                    }
                }

                predictedBg -= iob.jActivity;
                predictedBg += iob.jCarbImpact;

                predictedBgFinal = predictedBg;
                if (useMomentum && hasPolyPrediction && (polyPrediction > 0)) {
                    predictedBgFinal = ((predictedBg * predictWeight) + polyPrediction) / (predictWeight + 1);
                    if (MOMENTUM_SMOOTHING) {
                        predictedBg = predictedBgFinal;
                    }
                }
                predictWeight *= PREDICT_WEIGHT_MULTIPLIER;
                hasPredictedBg = true;
            }

            steps.add(new Step(
                    iob.timestamp,
                    timestampFuzzed,
                    iob.iob,
                    iob.cob,
                    iob.jActivity,
                    iob.jCarbImpact,
                    futurePredictionStep,
                    hasPolyPrediction,
                    polyPrediction,
                    hasPredictedBg,
                    predictedBgFinal));
        }

        return new Result(steps, predictedBg, lastFuzzedTimestamp);
    }

    private static boolean hasRenderableData(final Iob iob) {
        return (iob != null)
                && ((iob.iob > 0) || (iob.cob > 0) || (iob.jActivity > 0) || (iob.jCarbImpact > 0));
    }
}
