package com.horizon.ai.utils;

/** Hộp (bounding box) theo block trong một world. Dùng cho whitelist và ranh giới máy. */
public record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Region {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("min phải nhỏ hơn hoặc bằng max");
        }
    }

    /** Định dạng: world;minX;minY;minZ;maxX;maxY;maxZ. Trả về null nếu sai định dạng. */
    public static Region parse(String raw) {
        if (raw == null) {
            return null;
        }
        String[] p = raw.split(";");
        if (p.length != 7) {
            return null;
        }
        try {
            return new Region(p[0].trim(),
                    Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()),
                    Integer.parseInt(p[4].trim()), Integer.parseInt(p[5].trim()), Integer.parseInt(p[6].trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean contains(String w, int x, int y, int z) {
        return world.equals(w) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean intersects(Region o) {
        return world.equals(o.world)
                && minX <= o.maxX && maxX >= o.minX
                && minY <= o.maxY && maxY >= o.minY
                && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    /** Hộp mở rộng thêm {@code margin} block về mọi phía. */
    public Region expand(int margin) {
        return new Region(world, minX - margin, minY - margin, minZ - margin,
                maxX + margin, maxY + margin, maxZ + margin);
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public int centerX() {
        return (minX + maxX) / 2;
    }

    public int centerY() {
        return (minY + maxY) / 2;
    }

    public int centerZ() {
        return (minZ + maxZ) / 2;
    }
}
