package voxelin.core;

/** Позиція блока (X/Z - long, Y - 0..255). */
public record BlockPos(long x, int y, long z) {}
