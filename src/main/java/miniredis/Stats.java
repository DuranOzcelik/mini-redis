package miniredis;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Counters shown by INFO and the dashboard. */
final class Stats {
    private static final int WINDOW = 60;

    final LongAdder commands = new LongAdder(); // LongAdder: many threads increment, few read
    final AtomicInteger clients = new AtomicInteger();
    final long startedAt = System.currentTimeMillis();

    private final long[] perSecond = new long[WINDOW];
    private long lastTotal;
    private int next;

    /** Called once per second: records how many commands ran since the previous call. */
    synchronized void sample() {
        long total = commands.sum();
        perSecond[next] = total - lastTotal;
        next = (next + 1) % WINDOW;
        lastTotal = total;
    }

    /** Commands per second for the last minute, oldest first. */
    synchronized long[] history() {
        long[] ordered = new long[WINDOW];
        for (int i = 0; i < WINDOW; i++) {
            ordered[i] = perSecond[(next + i) % WINDOW];
        }
        return ordered;
    }
}
