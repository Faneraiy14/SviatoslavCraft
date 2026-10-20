package voxelin.api;

import java.util.*;

/**
 * Опис виду істоти як даних. Будівник: {@code new MobSettings().health(10).size(0.9f, 1.4f).behavior(MobSettings.Behavior.PASSIVE).part(...)}.
 * Модель - набір коробок у системі координат істоти: початок - центр підніжжя, «вперед» - у бік -z, одиниці - блоки.
 */
public final class MobSettings {
    public enum Behavior {
        /** Мирна: блукає, тікає після удару. */
        PASSIVE,
        /** Ворожа: переслідує й б'є гравця. */
        HOSTILE,
        /** Вдень мирна (поки її не вдарили), вночі й у темряві ворожа. */
        NEUTRAL_DAY
    }

    /** Одна коробка моделі: центр по x/z, низ по y, розміри, колір; swing 0 - нерухома, 1/2 - хитається в протифазі при ході (ноги). */
    public static final class Part {
        public final float cx, y0, cz, w, h, d; public final int rgb, swing;
        public Part(float cx, float y0, float cz, float w, float h, float d, int rgb, int swing) { this.cx = cx; this.y0 = y0; this.cz = cz; this.w = w; this.h = h; this.d = d; this.rgb = rgb & 0xFFFFFF; this.swing = Math.max(0, Math.min(2, swing)); }
    }

    float health = 10, speed = 2f, width = 0.6f, height = 1.0f;
    Behavior behavior = Behavior.PASSIVE;
    float attackDamage = 2, reach = 1.5f, cooldown = 1f, detect = 16f;
    boolean burnsInSun;
    String title;
    final List<BlockSettings.Drop> drops = new ArrayList<>();
    final List<Part> parts = new ArrayList<>();
    final List<String> spawnOn = new ArrayList<>();

    public MobSettings health(float h) { health = Float.isNaN(h) ? 10 : Math.max(1, Math.min(10000, h)); return this; }
    /** Швидкість ходьби, блоків/с. */
    public MobSettings speed(float s) { speed = Float.isNaN(s) ? 2 : Math.max(0.1f, Math.min(20, s)); return this; }
    public MobSettings size(float width, float height) { this.width = Math.max(0.1f, Math.min(4, width)); this.height = Math.max(0.1f, Math.min(6, height)); return this; }
    public MobSettings behavior(Behavior b) { behavior = b == null ? Behavior.PASSIVE : b; return this; }
    /** Шкода гравцеві, відстань удару (блоків) і пауза між ударами (секунд). */
    public MobSettings attack(float damage, float reach, float cooldownSeconds) {
        attackDamage = Float.isNaN(damage) ? 0 : Math.max(0, Math.min(1000, damage)); this.reach = Float.isNaN(reach) ? 1.5f : Math.max(0.3f, Math.min(10, reach));
        cooldown = Float.isNaN(cooldownSeconds) ? 1f : Math.max(0.1f, Math.min(60, cooldownSeconds)); return this;
    }
    /** Радіус, у якому ворог помічає гравця. */
    public MobSettings detect(float r) { detect = Float.isNaN(r) ? 16 : Math.max(1, Math.min(128, r)); return this; }
    /** Горить на сонці (зомбі). */
    public MobSettings burnsInSun(boolean b) { burnsInSun = b; return this; }
    public MobSettings title(String t) { title = t; return this; }
    /** Що випадає після смерті: предмет, кількість min..max, шанс 0..1. */
    public MobSettings drops(String item, int min, int max, float chance) { drops.add(new BlockSettings.Drop(item, min, max, chance)); return this; }
    /** Мирна істота з'являється лише на цих блоках («voxelin:grass»); порожньо - на будь-якій твердій опорі. */
    public MobSettings spawnOn(String... blocks) { spawnOn.addAll(Arrays.asList(blocks)); return this; }
    public MobSettings part(float cx, float y0, float cz, float w, float h, float d, int rgb) { parts.add(new Part(cx, y0, cz, w, h, d, rgb, 0)); return this; }
    public MobSettings legPart(float cx, float y0, float cz, float w, float h, float d, int rgb, boolean opposite) { parts.add(new Part(cx, y0, cz, w, h, d, rgb, opposite ? 2 : 1)); return this; }

    public MobSettings qualified(String modId) {
        MobSettings s = new MobSettings();
        s.health = health; s.speed = speed; s.width = width; s.height = height; s.behavior = behavior; s.attackDamage = attackDamage; s.reach = reach; s.cooldown = cooldown;
        s.detect = detect; s.burnsInSun = burnsInSun; s.title = title; s.parts.addAll(parts);
        for (BlockSettings.Drop d : drops) s.drops.add(new BlockSettings.Drop(d.item.indexOf(':') >= 0 ? d.item : modId + ":" + d.item, d.min, d.max, d.chance));
        for (String b : spawnOn) s.spawnOn.add(b.indexOf(':') >= 0 ? b : modId + ":" + b);
        return s;
    }

    public float healthValue() { return health; }
    public float speedValue() { return speed; }
    public float widthValue() { return width; }
    public float heightValue() { return height; }
    public Behavior behaviorValue() { return behavior; }
    public float attackDamageValue() { return attackDamage; }
    public float reachValue() { return reach; }
    public float cooldownValue() { return cooldown; }
    public float detectValue() { return detect; }
    public boolean burnsInSunValue() { return burnsInSun; }
    public String titleText() { return title; }
    public List<BlockSettings.Drop> dropList() { return Collections.unmodifiableList(drops); }
    public List<Part> partList() { return Collections.unmodifiableList(parts); }
    public List<String> spawnOnList() { return Collections.unmodifiableList(spawnOn); }
}
