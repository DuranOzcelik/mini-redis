package miniredis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Counters shown by INFO and the dashboard. */
final class Stats {
    private static final int WINDOW = 60;

    final LongAdder commands = new LongAdder(); // LongAdder: many threads increment, few read
    final AtomicInteger clients = new AtomicInteger();
    final long startedAt = System.currentTimeMillis();
    final HotKeys hotKeys = new HotKeys(64);
    /** Per command name: {calls, total nanoseconds}. */
    final Map<String, LongAdder[]> timings = new ConcurrentHashMap<>();

    void time(String command, long nanos) {
        LongAdder[] timing = timings.computeIfAbsent(command, key -> new LongAdder[]{new LongAdder(), new LongAdder()});
        timing[0].increment();
        timing[1].add(nanos);
    }

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
