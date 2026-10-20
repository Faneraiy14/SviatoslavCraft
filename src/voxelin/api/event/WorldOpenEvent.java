package voxelin.api.event;

import voxelin.api.WorldAccess;

/** Світ відкрито (після завантаження, перед першим тіком). */
public final class WorldOpenEvent {
    public final WorldAccess world;
    public WorldOpenEvent(WorldAccess world) { this.world = world; }
}
