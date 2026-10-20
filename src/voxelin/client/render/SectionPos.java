package voxelin.client.render;

/** Ключ секції (cx, sy, cz) - координати чанків long. */
public record SectionPos(long cx, int sy, long cz) {
    @Override public int hashCode() {
        long h = cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL + sy * 0x165667B19E3779F9L;
        h ^= h >>> 32; h *= 0xD6E8FEB86659FD93L; h ^= h >>> 32;
        return (int) h;
    }
}
