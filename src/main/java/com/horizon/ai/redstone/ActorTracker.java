package com.horizon.ai.redstone;

import com.horizon.ai.utils.BlockPos;
import com.horizon.ai.utils.Region;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ghi lại người đặt và người tương tác gần nhất với linh kiện Redstone.
 * Chỉ trả về thông tin khi có bản ghi thật. Không bao giờ suy đoán chủ sở hữu.
 *
 * LUỒNG: chỉ dùng trên Main Thread. Dung lượng bị giới hạn bằng LRU.
 */
public final class ActorTracker {

    public record Actor(UUID uuid, String name, long timeMillis) {
    }

    private record Entry(UUID uuid, String name, boolean placed, long timeMillis) {
    }

    private final int capacity;
    private final Map<BlockPos, Entry> entries;

    public ActorTracker(int capacity) {
        this.capacity = Math.max(64, capacity);
        this.entries = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<BlockPos, Entry> eldest) {
                return size() > ActorTracker.this.capacity;
            }
        };
    }

    public void rememberPlacement(BlockPos pos, UUID uuid, String name) {
        entries.put(pos, new Entry(uuid, name, true, System.currentTimeMillis()));
    }

    public void rememberInteraction(BlockPos pos, UUID uuid, String name) {
        entries.put(pos, new Entry(uuid, name, false, System.currentTimeMillis()));
    }

    /** Người đặt gần nhất trong vùng (mở rộng margin), nếu có bản ghi còn hạn. */
    public Optional<Actor> builderIn(Region region, int margin, long maxAgeMillis) {
        return find(region, margin, maxAgeMillis, true);
    }

    /** Người tương tác gần nhất trong vùng, nếu có bản ghi còn hạn. */
    public Optional<Actor> activatorIn(Region region, int margin, long maxAgeMillis) {
        return find(region, margin, maxAgeMillis, false);
    }

    private Optional<Actor> find(Region region, int margin, long maxAgeMillis, boolean placed) {
        Region r = region.expand(margin);
        long now = System.currentTimeMillis();
        Entry best = null;
        Iterator<Map.Entry<BlockPos, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Entry> e = it.next();
            BlockPos b = e.getKey();
            Entry v = e.getValue();
            if (v.placed() != placed || now - v.timeMillis() > maxAgeMillis) {
                continue;
            }
            if (r.contains(b.world(), b.x(), b.y(), b.z())
                    && (best == null || v.timeMillis() > best.timeMillis())) {
                best = v;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Actor(best.uuid(), best.name(), best.timeMillis()));
    }
}
