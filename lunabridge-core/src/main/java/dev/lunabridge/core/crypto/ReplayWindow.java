package dev.lunabridge.core.crypto;

/** Bounded sliding replay window for one direction of an authenticated session. */
public final class ReplayWindow {
    private static final int WIDTH = 64;
    private long greatest = -1;
    private long seenBits;

    public synchronized boolean admit(long sequence) {
        if (sequence < 0) return false;
        if (greatest < 0) {
            greatest = sequence;
            seenBits = 1;
            return true;
        }
        if (sequence > greatest) {
            long shift = sequence - greatest;
            seenBits = shift >= WIDTH ? 1 : (seenBits << shift) | 1;
            greatest = sequence;
            return true;
        }
        long distance = greatest - sequence;
        if (distance >= WIDTH) return false;
        long bit = 1L << distance;
        if ((seenBits & bit) != 0) return false;
        seenBits |= bit;
        return true;
    }
}
