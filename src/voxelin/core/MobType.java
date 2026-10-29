package voxelin.core;

import voxelin.api.BlockSettings;
import voxelin.api.MobRef;
import voxelin.api.MobSettings;

import java.util.List;

/** Зареєстрований вид істоти: незмінні властивості. */
public final class MobType implements MobRef {
    public final String name, title;
    public final double health, speed, width, height, attackDamage, reach, detect, armorPierce;
    public final int cooldownTicks;
    public final MobSettings.Behavior behavior;
    public final boolean burnsInSun, persistent, flying, boss, invulnerable;
    public final String dialog;
    public final double homeRadius;
    public final List<MobSettings.Trade> trades;
    public final List<String> greetings;
    public final List<BlockSettings.Drop> drops;
    public final List<MobSettings.Part> parts;
    public final List<String> spawnOn;
    /** Вимір природного спавну ("overworld" за замовчуванням). */
    public final String dimension;

    MobType(String name, MobSettings s) {
        this.name = name;
        title = s.titleText() != null ? s.titleText() : ItemType.prettify(name);
        health = s.healthValue(); speed = s.speedValue(); width = s.widthValue(); height = s.heightValue(); behavior = s.behaviorValue();
        attackDamage = s.attackDamageValue(); reach = s.reachValue(); detect = s.detectValue(); cooldownTicks = Math.max(2, Math.round(s.cooldownValue() * 20));
        burnsInSun = s.burnsInSunValue(); persistent = s.persistentValue(); flying = s.flyingValue(); boss = s.bossValue(); armorPierce = s.armorPierceValue(); invulnerable = s.invulnerableValue(); dialog = s.dialogKind(); homeRadius = s.homeRadiusValue(); trades = List.copyOf(s.tradeList()); greetings = List.copyOf(s.greetingList());
        drops = List.copyOf(s.dropList()); parts = List.copyOf(s.partList()); spawnOn = List.copyOf(s.spawnOnList()); dimension = s.dimensionValue() == null ? "overworld" : s.dimensionValue();
    }

    /** Можна говорити (є угоди або репліки). */
    public boolean talkable() { return !trades.isEmpty() || !greetings.isEmpty() || dialog != null; }
    public boolean hostile() { return behavior == MobSettings.Behavior.HOSTILE; }
    public boolean passive() { return behavior == MobSettings.Behavior.PASSIVE; }
    public boolean guard() { return behavior == MobSettings.Behavior.GUARD; }
    /** Житель (мирний, з домом): його захищає Вартовий, на нього полюють вороги. */
    public boolean villager() { return passive() && homeRadius > 0 && persistent; }
    @Override public String name() { return name; }
    @Override public String toString() { return name; }
}
