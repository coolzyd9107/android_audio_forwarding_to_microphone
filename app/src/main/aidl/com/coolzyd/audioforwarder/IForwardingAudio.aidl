package com.coolzyd.audioforwarder;

interface IForwardingAudio {
    int registerClient(int sampleRate, int channels, int encoding);
    byte[] readAudio(int clientId, int frames);
    void unregisterClient(int clientId);
}
