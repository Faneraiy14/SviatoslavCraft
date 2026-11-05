package voxelin.api.event;

import voxelin.api.WorldAccess;

/** Гравець вийшов зі світу (гість від'єднався; гравець закрив світ або пішов в інший вимір). reason - причина або null. Можливість "events.player_leave". */
public final class PlayerLeaveEvent {
    public final WorldAccess world;
    public final String name;
    public final boolean guest;
    public final String reason;
    public PlayerLeaveEvent(WorldAccess world, String name, boolean guest, String reason) { this.world = world; this.name = name; this.guest = guest; this.reason = reason; }
}
