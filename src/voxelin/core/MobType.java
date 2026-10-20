package voxelin.core;

import voxelin.api.BlockSettings;
import voxelin.api.MobRef;
import voxelin.api.MobSettings;

import java.util.List;

/** Зареєстрований вид істоти: незмінні властивості. */
public final class MobType implements MobRef {
    public final String name, title;
    public final double health, speed, width, height, attackDamage, reach, detect;
    public final int cooldownTicks;
    public final MobSettings.Behavior behavior;
    public final boolean burnsInSun, persistent;
    public final double homeRadius;
    public final List<MobSettings.Trade> trades;
    public final List<String> greetings;
    public final List<BlockSettings.Drop> drops;
    public final List<MobSettings.Part> parts;
    public final List<String> spawnOn;

    MobType(String name, MobSettings s) {
        this.name = name;
        title = s.titleText() != null ? s.titleText() : ItemType.prettify(name);
        health = s.healthValue(); speed = s.speedValue(); width = s.widthValue(); height = s.heightValue(); behavior = s.behaviorValue();
        attackDamage = s.attackDamageValue(); reach = s.reachValue(); detect = s.detectValue(); cooldownTicks = Math.max(2, Math.round(s.cooldownValue() * 20));
        burnsInSun = s.burnsInSunValue(); persistent = s.persistentValue(); homeRadius = s.homeRadiusValue(); trades = List.copyOf(s.tradeList()); greetings = List.copyOf(s.greetingList());
        drops = List.copyOf(s.dropList()); parts = List.copyOf(s.partList()); spawnOn = List.copyOf(s.spawnOnList());
    }

    /** Можна говорити (є угоди або репліки). */
    public boolean talkable() { return !trades.isEmpty() || !greetings.isEmpty(); }
    public boolean hostile() { return behavior == MobSettings.Behavior.HOSTILE; }
    public boolean passive() { return behavior == MobSettings.Behavior.PASSIVE; }
    @Override public String name() { return name; }
    @Override public String toString() { return name; }
}
