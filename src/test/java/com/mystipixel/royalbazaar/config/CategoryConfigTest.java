package com.mystipixel.royalbazaar.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryConfigTest {

    @Test
    void npcBoundsLeaveAMissingSideAtTheConfiguredLimit() {
        assertArrayEquals(new double[]{60 / 1.02, 100 / 0.98},
                CategoryConfig.npcBounds(0.04, 8, 800, 100.0, 60.0), 1e-9);
        assertArrayEquals(new double[]{8, 100 / 0.98}, CategoryConfig.npcBounds(0.04, 8, 800, 100.0, null), 1e-9);
        assertArrayEquals(new double[]{60 / 1.02, 800}, CategoryConfig.npcBounds(0.04, 8, 800, null, 60.0), 1e-9);
        assertArrayEquals(new double[]{8, 800}, CategoryConfig.npcBounds(0.04, 8, 800, null, null), 1e-9);
    }

    @Test
    void npcBoundsNeverWidenTheConfiguredRange() {
        assertArrayEquals(new double[]{50, 60}, CategoryConfig.npcBounds(0.04, 50, 60, 1000.0, 1.0), 1e-9);
    }

    @Test
    void npcBoundsCollapseWhenEcoShopPaysMoreThanItCharges() {
        double[] bounds = CategoryConfig.npcBounds(0.04, 8, 800, 0.1, 1000.0);
        assertTrue(bounds[0] >= bounds[1]);
    }
}
