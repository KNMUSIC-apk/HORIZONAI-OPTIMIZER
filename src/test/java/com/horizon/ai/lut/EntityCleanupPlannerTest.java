package com.horizon.ai.lut;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityCleanupPlannerTest {

    @Test
    void noRemovalAtOrBelowMax() {
        assertEquals(0, EntityCleanupPlanner.removeCount(5, 8, 4));
        assertEquals(0, EntityCleanupPlanner.removeCount(8, 8, 4));
    }

    @Test
    void removesDownToTargetWhenAboveMax() {
        assertEquals(8, EntityCleanupPlanner.removeCount(12, 8, 4));
        assertEquals(30, EntityCleanupPlanner.removeCount(50, 40, 20));
    }

    @Test
    void neverNegativeWhenTargetIsInvalid() {
        assertEquals(0, EntityCleanupPlanner.removeCount(9, 8, 10));
        assertEquals(9, EntityCleanupPlanner.removeCount(9, 8, -5));
    }
}
