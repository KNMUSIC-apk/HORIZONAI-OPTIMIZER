package com.horizon.ai.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOptimizerConfigTest {

    @Test
    void defaultsAreSane() {
        EntityOptimizerConfig d = EntityOptimizerConfig.defaults();
        assertTrue(d.boatTarget() < d.boatMax());
        assertTrue(d.arrowTarget() < d.arrowMax());
        assertTrue(d.snowballTarget() < d.snowballMax());
    }

    @Test
    void targetIsRepairedBelowMax() {
        EntityOptimizerConfig c = new EntityOptimizerConfig(true, 5, 16, 8, 20, 40, 99, 40, 40, true);
        assertEquals(7, c.boatTarget());
        assertEquals(39, c.arrowTarget());
        assertEquals(39, c.snowballTarget());
    }

    @Test
    void valuesAreClamped() {
        EntityOptimizerConfig c = new EntityOptimizerConfig(true, 0, 0, 0, -3, 0, 0, 5000, 5000, false);
        assertEquals(1, c.scanIntervalSeconds());
        assertEquals(1, c.chunksPerCycle());
        assertEquals(1, c.boatMax());
        assertEquals(0, c.boatTarget());
        assertEquals(1000, c.snowballMax());
    }
}
