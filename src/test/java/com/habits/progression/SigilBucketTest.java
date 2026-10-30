package com.habits.progression;

import com.habits.core.Rarity;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Stored sigil counts saved before Mythical (4 types x 5 rarities, type-major) must read the same kinds now. */
class SigilBucketTest {
    @Test
    void oldLayoutKeptAndNewRaritiesAfterIt() {
        Set<Integer> seen = new HashSet<>();
        for (SigilType t : SigilType.values())
            for (Rarity r : Rarity.values()) {
                SigilData s = SigilData.of(t, r);
                if (r.ordinal() < 5) assertEquals(t.ordinal() * 5 + r.ordinal(), s.bucket(), "old index of " + s);
                else assertEquals(20 + t.ordinal(), s.bucket(), "Mythical after the old block: " + s);
                assertSame(s, SigilData.ALL.get(s.bucket()));
                seen.add(s.bucket());
            }
        assertEquals(SigilData.ALL.size(), seen.size());
    }
}
