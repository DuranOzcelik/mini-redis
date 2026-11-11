package miniredis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the most frequently used keys in bounded memory with the Space-Saving algorithm
 * (Metwally, Agrawal, El Abbadi, 2005).
 *
 * Counting every key exactly would need memory proportional to the number of keys. This keeps
 * a fixed number of counters instead. When a new key arrives and all counters are taken, the
 * key with the smallest count is replaced and the newcomer inherits that count. Counts can
 * therefore be too high, never too low, and the overestimate is at most `error`.
 */
final class HotKeys {
    record Entry(String key, long count, long error) {
    }

    private static final int MAX_KEY_LENGTH = 256; // longer keys are not tracked, to bound memory

    private final int capacity;
    private final Map<String, long[]> counters = new HashMap<>(); // key -> {count, error}

    HotKeys(int capacity) {
        this.capacity = capacity;
    }

    // shortcut: one lock for all connections. Fine for a local server; a busy one would
    // sample (record every Nth access) or keep one sketch per thread and merge on read.
    synchronized void record(String key) {
        if (key.length() > MAX_KEY_LENGTH) {
            return;
        }
        long[] counter = counters.get(key);
        if (counter != null) {
            counter[0]++;
        } else if (counters.size() < capacity) {
            counters.put(key, new long[]{1, 0});
        } else {
            Map.Entry<String, long[]> smallest = null;
            for (Map.Entry<String, long[]> candidate : counters.entrySet()) {
                if (smallest == null || candidate.getValue()[0] < smallest.getValue()[0]) {
                    smallest = candidate;
                }
            }
            long inherited = smallest.getValue()[0];
            counters.remove(smallest.getKey());
            counters.put(key, new long[]{inherited + 1, inherited});
        }
    }

    /** The `limit` keys with the highest counts, highest first. */
    synchronized List<Entry> top(int limit) {
        List<Entry> entries = new ArrayList<>();
        counters.forEach((key, counter) -> entries.add(new Entry(key, counter[0], counter[1])));
        entries.sort(Comparator.comparingLong(Entry::count).reversed().thenComparing(Entry::key));
        return entries.subList(0, Math.min(Math.max(limit, 0), entries.size()));
    }

    synchronized int tracked() {
        return counters.size();
    }
}
