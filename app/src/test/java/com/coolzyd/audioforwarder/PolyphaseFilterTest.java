package com.coolzyd.audioforwarder;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class PolyphaseFilterTest {
    private static final int INPUT_RATE = 48000;
    private static final int OUTPUT_RATE = 16000;
    private static final int AMPLITUDE = 20000;

    @Test
    public void preservesVoiceBandAndRejectsFrequenciesAboveNyquist() {
        PolyphaseFilter filter = new PolyphaseFilter(INPUT_RATE, OUTPUT_RATE);
        double voiceRms = measureOutputRms(filter, 1000.0);
        double aliasRms = measureOutputRms(filter, 12000.0);

        assertTrue("pass band unexpectedly attenuated: " + voiceRms, voiceRms > 12000.0);
        assertTrue("out-of-band tone aliases into output: " + aliasRms, aliasRms < 300.0);
    }

    private static double measureOutputRms(PolyphaseFilter filter, double frequency) {
        final double radiansPerFrame = 2.0 * Math.PI * frequency / INPUT_RATE;
        PolyphaseFilter.SampleSource source = (frame, channel) ->
                (int) Math.round(AMPLITUDE * Math.sin(frame * radiansPerFrame));
        double sumSquares = 0.0;
        int count = 0;
        for (int outputFrame = 128; outputFrame < 2048; outputFrame++) {
            int sample = filter.filter(outputFrame * (INPUT_RATE / (double) OUTPUT_RATE), 0, source);
            sumSquares += (double) sample * sample;
            count++;
        }
        return Math.sqrt(sumSquares / count);
    }
}
