package voxelin.core.entity;

import voxelin.api.Vitality;

/**
 * Здоров'я, голод і повітря гравця (ядро, без LWJGL). Тік - 20/с. Значення 0..20 («половинки»: 20 = десять сердець/«м'ясок»).
 * Правила (спрощені з Minecraft, за згодою зі Святославом - «як у Minecraft»): виснаження від бігу/стрибків/ламання з'їдає насиченість, потім голод;
 * при голоді >= 18 здоров'я повільно відновлюється; при голоді 0 - шкода (але не вбиває: лишається 1); падіння з висоти > 3; утоплення після 15 с без повітря.
 */
public final class Vitals implements Vitality {
    public static final double MAX = 20, MAX_AIR = 300, SAFE_FALL = 3;
    public static final int REGEN_TICKS = 80, INVULN_TICKS = 10, RESPAWN_INVULN = 60;

    private double health = MAX, hunger = MAX, saturation = 5, exhaustion;
    private int air = (int) MAX_AIR, foodTimer, invuln;
    private boolean dead;
    private boolean god;

    public Vitals() {}

    public Vitals(double health, double hunger, double saturation) {
        this.health = clamp(health, 0, MAX); this.hunger = clamp(hunger, 0, MAX); this.saturation = clamp(saturation, 0, this.hunger);
        dead = this.health <= 0;
    }

    private static double clamp(double v, double lo, double hi) { return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    @Override public double health() { return health; }
    @Override public double maxHealth() { return MAX; }
    @Override public double hunger() { return hunger; }
    public double saturation() { return saturation; }
    public int air() { return air; }
    /** Креатив: шкоди й голоду нема. */
    public void setGod(boolean g) { god = g; if (g && !dead) { hunger = MAX; saturation = 5; exhaustion = 0; air = (int) MAX_AIR; } }
    public boolean god() { return god; }
    @Override public boolean dead() { return dead; }

    /** Шкода, що минає невразливість (команди, сюжетні події). */
    @Override public void damage(double amount) { hurt(amount, true); }

    /** Природна шкода (голод, падіння, вода): з коротким вікном невразливості. */
    public void hurt(double amount, boolean ignoreInvuln) {
        if (dead || god || !(amount > 0)) return; // NaN теж відкидається
        if (!ignoreInvuln && invuln > 0) return;
        health = Math.max(0, health - Math.min(amount, 1e6));
        if (!ignoreInvuln) invuln = INVULN_TICKS;
        if (health <= 0) { dead = true; air = (int) MAX_AIR; }
    }

    @Override public void heal(double amount) { if (!dead && amount > 0) health = Math.min(MAX, health + Math.min(amount, MAX)); }

    @Override public void feed(double amount) {
        if (dead || !(amount > 0)) return;
        double a = Math.min(amount, MAX);
        hunger = Math.min(MAX, hunger + a);
        saturation = Math.min(hunger, saturation + a * 0.6);
    }

    /** Виснаження від дій (МС: біг на метр 0.1, стрибок 0.05, ламання блока 0.005). */
    public void exhaust(double e) { if (!dead && !god && e > 0) exhaustion += Math.min(e, 40); }

    /** Падіння з висоти (блоків) при приземленні: шкода за кожен блок понад безпечні 3. */
    public void land(double fallBlocks) {
        if (fallBlocks > SAFE_FALL) hurt(Math.ceil(fallBlocks - SAFE_FALL), false);
    }

    /** Один тік: голод, відновлення, повітря під водою. */
    public void tick(boolean headInWater) {
        if (dead) return;
        if (god) { air = (int) MAX_AIR; return; }
        if (invuln > 0) invuln--;
        while (exhaustion >= 4) {
            exhaustion -= 4;
            if (saturation > 0) saturation = Math.max(0, saturation - 1); else hunger = Math.max(0, hunger - 1);
        }
        if (hunger >= 18 && health < MAX) {
            if (++foodTimer >= REGEN_TICKS) { foodTimer = 0; heal(1); exhaust(6); }
        } else if (hunger <= 0) {
            if (++foodTimer >= REGEN_TICKS) { foodTimer = 0; if (health > 1) hurt(1, true); }
        } else foodTimer = 0;
        if (headInWater) {
            if (--air <= -20) { air = 0; hurt(2, true); }
        } else air = (int) Math.min(MAX_AIR, air + 4);
    }

    /** Відродження: повне здоров'я й голод, коротка невразливість. */
    public void respawn() {
        health = MAX; hunger = MAX; saturation = 5; exhaustion = 0; air = (int) MAX_AIR; foodTimer = 0; invuln = RESPAWN_INVULN; dead = false;
    }
}
