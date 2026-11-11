package miniredis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final Store store = new Store(now::get);

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void setAndGet() {
        assertNull(store.get("k"));
        assertTrue(store.set("k", bytes("v"), 0, false, false));
        assertArrayEquals(bytes("v"), store.get("k"));
        assertTrue(store.delete("k"));
        assertFalse(store.delete("k"));
    }

    @Test
    void keyExpiresExactlyAtDeadline() {
        store.set("k", bytes("v"), 500, false, false);
        now.set(1_499);
        assertEquals(1, store.ttlMillis("k"));
        assertTrue(store.exists("k"));
        now.set(1_500);
        assertNull(store.get("k"));
        assertEquals(-2, store.ttlMillis("k"));
    }

    @Test
    void ttlCodes() {
        assertEquals(-2, store.ttlMillis("missing"));
        store.set("k", bytes("v"), 0, false, false);
        assertEquals(-1, store.ttlMillis("k"));
        assertTrue(store.expire("k", 2_000));
        assertEquals(2_000, store.ttlMillis("k"));
        assertTrue(store.persist("k"));
        assertEquals(-1, store.ttlMillis("k"));
        assertFalse(store.expire("missing", 1_000));
    }

    @Test
    void nxAndXx() {
        assertFalse(store.set("k", bytes("a"), 0, false, true));  // XX: key must exist
        assertTrue(store.set("k", bytes("a"), 0, true, false));   // NX: key must not exist
        assertFalse(store.set("k", bytes("b"), 0, true, false));
        assertTrue(store.set("k", bytes("c"), 0, false, true));
        assertArrayEquals(bytes("c"), store.get("k"));
    }

    @Test
    void nxTreatsExpiredKeyAsMissing() {
        store.set("lock", bytes("owner-1"), 100, true, false);
        now.addAndGet(100);
        assertTrue(store.set("lock", bytes("owner-2"), 100, true, false));
    }

    @Test
    void incrementKeepsTtlAndStartsAtZero() {
        assertEquals(1, store.incrementBy("n", 1));
        store.expire("n", 5_000);
        assertEquals(11, store.incrementBy("n", 10));
        assertEquals(5_000, store.ttlMillis("n"));
        assertArrayEquals(bytes("11"), store.get("n"));
    }

    @Test
    void incrementRejectsNonIntegersAndOverflow() {
        store.set("text", bytes("abc"), 0, false, false);
        assertThrows(NumberFormatException.class, () -> store.incrementBy("text", 1));
        store.set("spaced", bytes(" 1"), 0, false, false);
        assertThrows(NumberFormatException.class, () -> store.incrementBy("spaced", 1));
        store.set("max", bytes(Long.toString(Long.MAX_VALUE)), 0, false, false);
        assertThrows(ArithmeticException.class, () -> store.incrementBy("max", 1));
        assertArrayEquals(bytes(Long.toString(Long.MAX_VALUE)), store.get("max")); // unchanged
    }

    @Test
    void sweepRemovesExpiredKeysNobodyReads() {
        store.set("a", bytes("1"), 100, false, false);
        store.set("b", bytes("2"), 0, false, false);
        now.addAndGet(100);
        assertEquals(2, store.size()); // still in memory until swept or read
        assertEquals(1, store.sweep());
        assertEquals(1, store.size());
    }

    @Test
    void keysGlob() {
        for (String key : new String[]{"user:1", "user:22", "order:1", "a.b"}) {
            store.set(key, bytes("x"), 0, false, false);
        }
        assertEquals(2, store.keys("user:*").size());
        assertEquals(1, store.keys("user:?").size());
        assertEquals(4, store.keys("*").size());
        assertEquals(0, store.keys("a?b?").size());
        assertEquals(1, store.keys("a.b").size()); // '.' is literal, not a regex wildcard
        assertEquals(0, store.keys("axb").size());
    }
}
