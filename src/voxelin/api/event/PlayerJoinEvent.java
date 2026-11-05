package voxelin.api.event;

import voxelin.api.WorldAccess;

/** Гравець увійшов у світ: сам гравець (guest = false; також після переходу в інший вимір - це інший світ) або гість гри по мережі (guest = true). Можливість "events.player_join". */
public final class PlayerJoinEvent {
    public final WorldAccess world;
    public final String name;
    public final boolean guest;
    public PlayerJoinEvent(WorldAccess world, String name, boolean guest) { this.world = world; this.name = name; this.guest = guest; }
}
