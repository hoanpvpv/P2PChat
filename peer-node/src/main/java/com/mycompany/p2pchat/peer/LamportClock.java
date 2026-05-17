package com.mycompany.p2pchat.peer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lamport Logical Clock for causal ordering of messages.
 * Thread-safe via AtomicLong.
 */
public class LamportClock {
    private final AtomicLong clock = new AtomicLong(0);

    /** Increment and return new clock value (for sending). */
    public long tick() {
        return clock.incrementAndGet();
    }

    /** Update clock on receive: max(local, remote) + 1. */
    public long receive(long remoteClock) {
        return clock.updateAndGet(local -> Math.max(local, remoteClock) + 1);
    }

    /** Get current clock value without incrementing. */
    public long get() {
        return clock.get();
    }
}
