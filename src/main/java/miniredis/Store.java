package miniredis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The keyspace: string values with optional expiry.
 *
 * Every read-modify-write runs inside ConcurrentHashMap.compute, which locks that one key.
 * This is what makes INCR and SET NX atomic without a global lock.
 */
final class Store {
    record Entry(byte[] value, long expireAt) {
        boolean expired(long now) {
            return expireAt != 0 && now >= expireAt;
        }
    }

    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,19}");

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    Store() {
        this(System::currentTimeMillis);
    }

    Store(LongSupplier clock) { // tests pass a fake clock instead of sleeping
        this.clock = clock;
    }

    /** Lazy expiry: an expired key is removed the moment somebody looks at it. */
    private Entry live(String key) {
        Entry entry = map.get(key);
        if (entry != null && entry.expired(clock.getAsLong())) {
            map.remove(key, entry);
            return null;
        }
        return entry;
    }

    private static long deadline(long now, long ttlMillis) {
        return ttlMillis > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + ttlMillis;
    }

    byte[] get(String key) {
        Entry entry = live(key);
        return entry == null ? null : entry.value();
    }

    /** ttlMillis 0 means no expiry. Returns false when NX or XX prevented the write. */
    boolean set(String key, byte[] value, long ttlMillis, boolean nx, boolean xx) {
        long now = clock.getAsLong();
        boolean[] written = {false};
        map.compute(key, (k, old) -> {
            boolean exists = old != null && !old.expired(now);
            if ((nx && exists) || (xx && !exists)) {
                return exists ? old : null;
            }
            written[0] = true;
            return new Entry(value, ttlMillis > 0 ? deadline(now, ttlMillis) : 0);
        });
        return written[0];
    }

    boolean delete(String key) {
        Entry removed = map.remove(key);
        return removed != null && !removed.expired(clock.getAsLong());
    }

    boolean exists(String key) {
        return live(key) != null;
    }

    /** Throws NumberFormatException if the value is not an integer, ArithmeticException on overflow. */
    long incrementBy(String key, long delta) {
        long now = clock.getAsLong();
        long[] result = new long[1];
        map.compute(key, (k, old) -> {
            boolean exists = old != null && !old.expired(now);
            long current = exists ? parseInteger(old.value()) : 0;
            result[0] = Math.addExact(current, delta);
            byte[] bytes = Long.toString(result[0]).getBytes(StandardCharsets.US_ASCII);
            return new Entry(bytes, exists ? old.expireAt() : 0); // INCR keeps the existing TTL
        });
        return result[0];
    }

    static long parseInteger(byte[] raw) {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        if (!INTEGER.matcher(text).matches()) {
            throw new NumberFormatException(text);
        }
        return Long.parseLong(text);
    }

    /** Returns false if the key does not exist. A ttl of zero or less deletes the key, as in Redis. */
    boolean expire(String key, long ttlMillis) {
        long now = clock.getAsLong();
        boolean[] found = {false};
        map.computeIfPresent(key, (k, old) -> {
            if (old.expired(now)) {
                return null;
            }
            found[0] = true;
            return ttlMillis <= 0 ? null : new Entry(old.value(), deadline(now, ttlMillis));
        });
        return found[0];
    }

    boolean persist(String key) {
        long now = clock.getAsLong();
        boolean[] changed = {false};
        map.computeIfPresent(key, (k, old) -> {
            if (old.expired(now)) {
                return null;
            }
            changed[0] = old.expireAt() != 0;
            return new Entry(old.value(), 0);
        });
        return changed[0];
    }

    /** Milliseconds to live. -2 when the key is missing, -1 when it has no expiry. */
    long ttlMillis(String key) {
        Entry entry = live(key);
        if (entry == null) {
            return -2;
        }
        return entry.expireAt() == 0 ? -1 : entry.expireAt() - clock.getAsLong();
    }

    /** Keys matching a glob pattern. Supports * and ?. */
    List<String> keys(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.replaceAll("\\*+", "*").toCharArray()) {
            regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
        }
        Pattern pattern = Pattern.compile(regex.toString(), Pattern.DOTALL);
        long now = clock.getAsLong();
        List<String> found = new ArrayList<>();
        map.forEach((key, entry) -> {
            if (!entry.expired(now) && pattern.matcher(key).matches()) {
                found.add(key);
            }
        });
        return found;
    }

    int size() {
        return map.size();
    }

    void clear() {
        map.clear();
    }

    /**
     * Active expiry: removes expired keys nobody reads any more, so they do not leak memory.
     * shortcut: scans the whole map; Redis samples 20 random keys per cycle.
     * Switch to sampling if the keyspace grows past a few million keys.
     */
    int sweep() {
        long now = clock.getAsLong();
        int before = map.size();
        map.values().removeIf(entry -> entry.expired(now));
        return Math.max(0, before - map.size());
    }
}
