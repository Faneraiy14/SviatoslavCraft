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
        public Part(float cx, float y0, float cz, float w, float h, float d, int rgb, int swing) { this.cx = cx; this.y0 = y0; this.cz = cz; this.w = w; this.h = h; this.d = d; this.rgb = rgb & 0xFFFFFF; this.swing = Math.max(0, Math.min(4, swing)); }
    }

    /** Угода жителя: віддай inCount x in - отримай outCount x out. */
    public static final class Trade {
        public final String in, out; public final int inCount, outCount;
        public Trade(String in, int inCount, String out, int outCount) { this.in = in; this.out = out; this.inCount = Math.max(1, Math.min(64, inCount)); this.outCount = Math.max(1, Math.min(64, outCount)); }
    }

    final List<Trade> trades = new ArrayList<>();
    final List<String> greetings = new ArrayList<>();
    float homeRadius;
    boolean flying, boss, invulnerable;
    String dialog;
    boolean persistent;
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
    /** Угода для вікна торгівлі (ПКМ по жителю). */
    public MobSettings trade(String in, int inCount, String out, int outCount) { trades.add(new Trade(in, inCount, out, outCount)); return this; }
    /** Репліки жителя (випадкова при розмові). */
    public MobSettings greeting(String... lines) { greetings.addAll(Arrays.asList(lines)); return this; }
    /** Радіус від дому, далі якого житель не блукає (0 - без дому). */
    public MobSettings homeRadius(float r) { homeRadius = Float.isNaN(r) ? 0 : Math.max(0, Math.min(256, r)); return this; }
    /** Зберігається у світі й не зникає (жителі); решта мирних теж зберігаються, але можуть зникати далеко. */
    public MobSettings persistent(boolean b) { persistent = b; return this; }
    /** Літає (без гравітації): кружляє над домом, пікірує на гравця (дракон). */
    public MobSettings flying(boolean b) { flying = b; return this; }
    /** Бос: смуга здоров'я, не відкидається, його смерть записується у світ. */
    public MobSettings boss(boolean b) { boss = b; return this; }
    /** Частка броні гравця, яку пробиває удар (0 - броня діє повністю, 1 - ігнорується). Для босів. */
    public MobSettings armorPierce(float f) { armorPierce = Float.isNaN(f) ? 0 : Math.max(0, Math.min(1, f)); return this; }
    float armorPierce;
    String dimension;
    /** Вимір, де вид з'являється природно ("between" тощо); null - звичайний світ. */
    public MobSettings dimension(String d) { dimension = d; return this; }
    public String dimensionValue() { return dimension; }
    public float armorPierceValue() { return armorPierce; }
    /** Крило: хитається вгору-вниз (поворот навколо осі вперед); left - ліве чи праве. */
    public MobSettings wingPart(float cx, float y0, float cz, float w, float h, float d, int rgb, boolean left) { parts.add(new Part(cx, y0, cz, w, h, d, rgb, left ? 3 : 4)); return this; }
    /** Невразливий (Сторож Шва). */
    public MobSettings invulnerable(boolean b) { invulnerable = b; return this; }
    /** Особливе вікно розмови замість звичайної торгівлі («keeper»). */
    public MobSettings dialog(String kind) { dialog = kind; return this; }
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
        s.homeRadius = homeRadius; s.persistent = persistent; s.flying = flying; s.boss = boss; s.armorPierce = armorPierce; s.dimension = dimension; s.invulnerable = invulnerable; s.dialog = dialog; s.greetings.addAll(greetings);
        for (Trade t : trades) s.trades.add(new Trade(t.in.indexOf(':') >= 0 ? t.in : modId + ":" + t.in, t.inCount, t.out.indexOf(':') >= 0 ? t.out : modId + ":" + t.out, t.outCount));
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
    public List<Trade> tradeList() { return Collections.unmodifiableList(trades); }
    public List<String> greetingList() { return Collections.unmodifiableList(greetings); }
    public boolean invulnerableValue() { return invulnerable; }
    public String dialogKind() { return dialog; }
    public boolean flyingValue() { return flying; }
    public boolean bossValue() { return boss; }
    public float homeRadiusValue() { return homeRadius; }
    public boolean persistentValue() { return persistent; }
    public boolean burnsInSunValue() { return burnsInSun; }
    public String titleText() { return title; }
    public List<BlockSettings.Drop> dropList() { return Collections.unmodifiableList(drops); }
    public List<Part> partList() { return Collections.unmodifiableList(parts); }
    public List<String> spawnOnList() { return Collections.unmodifiableList(spawnOn); }
}
