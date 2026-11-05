package voxelin.api.event;

import voxelin.api.WorldAccess;

/** Істота загинула (один раз на істоту, перед випаданням здобичі). Можливість "events.mob_death". */
public final class MobDeathEvent {
    public final WorldAccess world;
    /** Повна назва виду: "voxelin:zombie", "mymod:golem". */
    public final String mob;
    public final boolean boss;
    /** Де загинула (блоки). */
    public final long x, z;
    public final int y;
    public MobDeathEvent(WorldAccess world, String mob, boolean boss, long x, int y, long z) { this.world = world; this.mob = mob; this.boss = boss; this.x = x; this.y = y; this.z = z; }
}
