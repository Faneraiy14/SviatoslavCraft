package voxelin.api.event;

import voxelin.api.WorldAccess;

/** Раз на ігровий тік (20 разів на секунду), поки відкритий світ. */
public final class TickEvent {
    public final WorldAccess world;
    public final long tick;
    public TickEvent(WorldAccess world, long tick) { this.world = world; this.tick = tick; }
}
