package voxelin.core;

/** Координати чанка (long) - ключ у картах світу. Хеш - 64-бітне перемішування, без злипання від'ємних і гігантських значень. */
public record ChunkPos(long cx, long cz) {
    @Override public int hashCode() {
        long h = cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 32; h *= 0xD6E8FEB86659FD93L; h ^= h >>> 32;
        return (int) h;
    }
}
