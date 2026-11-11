package com.horizon.ai.redstone;

import com.horizon.ai.utils.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineIdentifierTest {

    private static MachineIdentifier.Point p(int x, int y, int z) {
        return new MachineIdentifier.Point(new BlockPos("world", x, y, z), 10, 1.0, 0.1, 6);
    }

    private static List<MachineIdentifier.Point> line(int x0, int y, int z, int len) {
        List<MachineIdentifier.Point> out = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            out.add(p(x0 + i, y, z));
        }
        return out;
    }

    /** Hai máy cách xa nhau phải thành hai cụm riêng và đều rõ ranh giới. */
    @Test
    void farApartMachinesAreSeparateAndClear() {
        List<MachineIdentifier.Point> pts = new ArrayList<>();
        pts.addAll(line(0, 64, 0, 4));
        pts.addAll(line(100, 64, 100, 4));
        var clusters = MachineIdentifier.identify(pts, 2, 32, 6);
        assertEquals(2, clusters.size());
        assertTrue(clusters.stream().allMatch(MachineIdentifier.Cluster::isContainable));
    }

    /** Hai cụm gần nhau (trong margin) không được cách ly nhầm: phải đánh dấu ambiguous. */
    @Test
    void nearbyMachinesAreAmbiguous() {
        List<MachineIdentifier.Point> pts = new ArrayList<>();
        pts.addAll(line(0, 64, 0, 4));
        pts.addAll(line(7, 64, 0, 4));   // khoảng cách 3 block so với cụm đầu
        var clusters = MachineIdentifier.identify(pts, 1, 32, 6);
        assertEquals(2, clusters.size());
        assertTrue(clusters.stream().allMatch(MachineIdentifier.Cluster::ambiguous));
        assertFalse(clusters.stream().anyMatch(MachineIdentifier.Cluster::isContainable));
    }

    /** Cụm quá rộng (flying machine lớn) bị đánh dấu oversized và không được cách ly. */
    @Test
    void oversizedClusterIsNotContainable() {
        var clusters = MachineIdentifier.identify(line(0, 64, 0, 60), 2, 32, 6);
        assertEquals(1, clusters.size());
        assertTrue(clusters.get(0).oversized());
        assertFalse(clusters.get(0).isContainable());
    }
}
