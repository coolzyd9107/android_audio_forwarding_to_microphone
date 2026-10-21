package com.coolzyd.audioforwarder;

import java.util.HashMap;
import java.util.Map;

final class AudioSession {
    static final PcmRingBuffer PCM = new PcmRingBuffer();
    static final Map<Integer, Integer> CLIENT_OWNERS = new HashMap<>();

    private AudioSession() {
    }
}
