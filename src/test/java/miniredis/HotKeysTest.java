package miniredis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotKeysTest {
    @Test
    void countsExactlyWhileThereIsRoom() {
        HotKeys hot = new HotKeys(4);
        for (String key : List.of("a", "b", "a", "c", "a", "b")) {
            hot.record(key);
        }
        assertEquals(List.of(new HotKeys.Entry("a", 3, 0), new HotKeys.Entry("b", 2, 0), new HotKeys.Entry("c", 1, 0)),
                hot.top(10));
    }

    @Test
    void newcomerInheritsTheSmallestCount() {
        HotKeys hot = new HotKeys(2);
        hot.record("a");
        hot.record("a");
        hot.record("b");
        hot.record("c"); // replaces "b" (count 1): "c" may have been seen once before, we cannot know
        assertEquals(List.of(new HotKeys.Entry("a", 2, 0), new HotKeys.Entry("c", 2, 1)), hot.top(10));
    }

    @Test
    void findsHeavyHittersAmongManyDistinctKeys() {
        HotKeys hot = new HotKeys(16);
        // 20,000 accesses: two hot keys take 30 % and 15 %, the rest goes to 11,000 one-off keys.
        for (int i = 0; i < 20_000; i++) {
            if (i % 10 < 3) {
                hot.record("session:hot");
            } else if (i % 20 == 3 || i % 20 == 13 || i % 20 == 4) {
                hot.record("cart:warm");
            } else {
                hot.record("page:" + i);
            }
        }
        List<HotKeys.Entry> top = hot.top(2);
        assertEquals("session:hot", top.get(0).key());
        assertEquals("cart:warm", top.get(1).key());
        assertEquals(16, hot.tracked(), "memory stays bounded however many keys pass through");
        // The guarantee of the algorithm: true count is between count - error and count.
        assertTrue(top.get(0).count() >= 6_000 && top.get(0).count() - top.get(0).error() <= 6_000);
        assertTrue(top.get(1).count() >= 3_000 && top.get(1).count() - top.get(1).error() <= 3_000);
    }

    @Test
    void ignoresOversizedKeysAndBoundsTheLimit() {
        HotKeys hot = new HotKeys(4);
        hot.record("x".repeat(10_000));
        hot.record("a");
        assertEquals(1, hot.tracked());
        assertEquals(0, hot.top(0).size());
        assertEquals(1, hot.top(99).size());
    }
}
