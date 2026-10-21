package com.coolzyd.audioforwarder;

final class PolyphaseFilter {
    private static final int RADIUS = 24;
    static final int RIGHT_LOOKAHEAD = RADIUS;
    private static final int TAPS = RADIUS * 2;
    private static final int PHASES = 256;

    interface SampleSource {
        int sampleAt(long frame, int channel);
    }

    private final float[][] coefficients = new float[PHASES][TAPS];

    PolyphaseFilter(int inputRate, int outputRate) {
        double cutoff = 1.0;
        if (outputRate < inputRate) {
            cutoff = 0.98 * outputRate / inputRate;
        }
        buildCoefficients(cutoff);
    }

    int filter(double position, int channel, SampleSource source) {
        long center = (long) Math.floor(position);
        double fraction = position - center;
        int phase = Math.min(PHASES - 1, (int) (fraction * PHASES));
        float[] phaseCoefficients = coefficients[phase];
        double value = 0.0;
        long firstFrame = center - RADIUS + 1;
        for (int tap = 0; tap < TAPS; tap++) {
            value += source.sampleAt(firstFrame + tap, channel) * phaseCoefficients[tap];
        }
        return (int) Math.round(value);
    }

    private void buildCoefficients(double cutoff) {
        for (int phase = 0; phase < PHASES; phase++) {
            double fractionalPosition = phase / (double) PHASES;
            double sum = 0.0;
            float[] phaseCoefficients = coefficients[phase];
            for (int tap = 0; tap < TAPS; tap++) {
                double sampleOffset = tap - RADIUS + 1;
                double distance = fractionalPosition - sampleOffset;
                double windowPosition = distance / RADIUS;
                if (Math.abs(windowPosition) >= 1.0) {
                    continue;
                }
                double window = 0.42 + 0.5 * Math.cos(Math.PI * windowPosition)
                        + 0.08 * Math.cos(2.0 * Math.PI * windowPosition);
                double x = cutoff * distance;
                double sinc = Math.abs(x) < 1.0e-9
                        ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
                double coefficient = cutoff * sinc * window;
                phaseCoefficients[tap] = (float) coefficient;
                sum += coefficient;
            }
            if (sum != 0.0) {
                for (int tap = 0; tap < TAPS; tap++) {
                    phaseCoefficients[tap] /= (float) sum;
                }
            }
        }
    }
}
