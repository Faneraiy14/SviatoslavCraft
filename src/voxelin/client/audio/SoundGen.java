package voxelin.client.audio;

import java.util.Random;

/**
 * Усі звуки гри синтезуються тут кодом (як текстури в TextureGen): шум, тони, огинаючі, прості фільтри. Детерміновано (фіксовані зерна),
 * тож однаково на кожному комп'ютері. Свої .wav у теці sounds/ перекривають будь-який звук (SoundBank.loadOverrides).
 */
public final class SoundGen {
    private SoundGen() {}

    static final int R = Sound.RATE;
    public static final String[] MATERIALS = {"stone", "wood", "dirt", "grass", "sand", "glass", "metal", "snow"};

    // ------------------------------------------------------------------ цеглинки

    static float[] buf(double sec) { return new float[Math.max(1, (int) (sec * R))]; }

    static void noise(float[] a, Random r, float amp) { for (int i = 0; i < a.length; i++) a[i] += (r.nextFloat() * 2 - 1) * amp; }

    /** Однополюсний ФНЧ: cutoff у Гц. */
    static void lowpass(float[] a, double cutoff) {
        double k = 1 - Math.exp(-2 * Math.PI * cutoff / R); float y = 0;
        for (int i = 0; i < a.length; i++) { y += (float) ((a[i] - y) * k); a[i] = y; }
    }

    static void highpass(float[] a, double cutoff) {
        float[] lo = a.clone(); lowpass(lo, cutoff);
        for (int i = 0; i < a.length; i++) a[i] -= lo[i];
    }

    /** Смуговий: ФВЧ + ФНЧ. */
    static void band(float[] a, double lo, double hi) { highpass(a, lo); lowpass(a, hi); }

    /** Тон із ковзанням частоти f0 -> f1; wave: 0 синус, 1 пила, 2 квадрат. */
    static void tone(float[] a, int from, int len, double f0, double f1, int wave, float amp) {
        double ph = 0;
        for (int i = 0; i < len && from + i < a.length; i++) {
            double t = (double) i / Math.max(1, len), f = f0 + (f1 - f0) * t;
            ph += f / R;
            double x = ph - Math.floor(ph), v = wave == 0 ? Math.sin(2 * Math.PI * x) : wave == 1 ? 2 * x - 1 : (x < 0.5 ? 1 : -1);
            a[from + i] += (float) (v * amp);
        }
    }

    /** Огинаюча: атака attack с, далі експоненційне згасання з постійною decay с. */
    static void env(float[] a, double attack, double decay) {
        int at = (int) (attack * R);
        for (int i = 0; i < a.length; i++) {
            double g = i < at ? (double) i / Math.max(1, at) : Math.exp(-(i - at) / (decay * R));
            a[i] *= (float) g;
        }
        fadeOut(a, 0.004);
    }

    static void fadeOut(float[] a, double sec) { int n = Math.min(a.length, (int) (sec * R)); for (int i = 0; i < n; i++) a[a.length - 1 - i] *= (float) i / n; }

    static void gain(float[] a, float g) { for (int i = 0; i < a.length; i++) a[i] *= g; }

    /** Підняти до піку peak. */
    static float[] norm(float[] a, float peak) {
        float m = 0; for (float v : a) m = Math.max(m, Math.abs(v));
        if (m > 1e-6) gain(a, peak / m);
        return a;
    }

    static void add(float[] dst, float[] src, int at, float g) { for (int i = 0; i < src.length && at + i < dst.length; i++) dst[at + i] += src[i] * g; }

    /** Петля без шву: хвіст плавно накладається на початок. */
    static float[] loopable(float[] a, double crossSec) {
        int f = Math.min(a.length / 3, (int) (crossSec * R)), n = a.length - f;
        float[] out = new float[n];
        System.arraycopy(a, 0, out, 0, n);
        for (int i = 0; i < f; i++) { float t = (float) i / f; out[i] = a[i] * t + a[n + i] * (1 - t); }
        return out;
    }

    /** Повільна амплітудна модуляція (вітер «дихає»). */
    static void wobble(float[] a, double hz, float depth, double phase) {
        for (int i = 0; i < a.length; i++) a[i] *= (float) (1 - depth * 0.5 * (1 + Math.sin(2 * Math.PI * hz * i / R + phase)));
    }

    static Sound s(float[] a) { return new Sound(a); }

    // ------------------------------------------------------------------ звуки

    /** Удар/хрускіт матеріалу: dur - тривалість, scale - сила (ламання 1, ставлення ~0.7, крок ~0.35). */
    static float[] material(String m, Random r, double dur, float scale) {
        float[] a = buf(dur);
        switch (m) {
            case "wood" -> { noise(a, r, 1); lowpass(a, 1400); float[] t = buf(dur); tone(t, 0, t.length, 260 + r.nextInt(60), 180, 0, 0.8f); env(t, 0.002, dur * 0.25); add(a, t, 0, 1); env(a, 0.002, dur * 0.3); }
            case "dirt", "grass" -> { noise(a, r, 1); band(a, 120, m.equals("grass") ? 2600 : 1500); env(a, 0.004, dur * 0.35); }
            case "sand", "snow" -> { for (int k = 0; k < 6; k++) { float[] g = buf(dur * 0.3); noise(g, r, 1); band(g, m.equals("snow") ? 900 : 600, m.equals("snow") ? 5000 : 3500); env(g, 0.003, dur * 0.07); add(a, g, (int) (r.nextDouble() * a.length * 0.6), 0.6f); } }
            case "glass" -> { noise(a, r, 0.6f); highpass(a, 2500); for (int k = 0; k < 4; k++) { float[] t = buf(dur * 0.6); tone(t, 0, t.length, 2200 + r.nextInt(2400), 2000 + r.nextInt(2400), 0, 0.5f); env(t, 0.001, dur * 0.2); add(a, t, (int) (r.nextDouble() * a.length * 0.3), 0.7f); } env(a, 0.001, dur * 0.35); }
            case "metal" -> { noise(a, r, 0.7f); band(a, 300, 3000); float[] t = buf(dur); tone(t, 0, t.length, 900 + r.nextInt(300), 880, 0, 0.6f); tone(t, 0, t.length, 1430 + r.nextInt(200), 1400, 0, 0.3f); env(t, 0.001, dur * 0.4); add(a, t, 0, 1); env(a, 0.002, dur * 0.4); }
            default -> { noise(a, r, 1); band(a, 200, 2400); float[] t = buf(dur); tone(t, 0, t.length, 140 + r.nextInt(40), 90, 0, 0.6f); env(t, 0.002, dur * 0.2); add(a, t, 0, 1); env(a, 0.002, dur * 0.25); }
        }
        return norm(a, scale);
    }

    static float[] voice(Random r, double dur, double f0, double f1, int wave, double cutoff, float noiseAmt) {
        float[] a = buf(dur);
        tone(a, 0, a.length, f0, f1, wave, 0.8f);
        tone(a, 0, a.length, f0 * 2.01, f1 * 2.01, 0, 0.25f);
        if (noiseAmt > 0) noise(a, r, noiseAmt);
        lowpass(a, cutoff);
        env(a, Math.min(0.08, dur * 0.2), dur * 0.45);
        return a;
    }

    public static SoundBank build() {
        SoundBank b = new SoundBank();
        Random r = new Random(0x50D7DL);
        for (String m : MATERIALS) {
            b.register("voxelin:block." + m + ".break", s(material(m, r, 0.32, 0.9f)), s(material(m, r, 0.30, 0.85f)), s(material(m, r, 0.35, 0.9f)));
            b.register("voxelin:block." + m + ".place", s(material(m, r, 0.18, 0.7f)), s(material(m, r, 0.16, 0.65f)));
            b.register("voxelin:block." + m + ".hit", s(material(m, r, 0.08, 0.35f)), s(material(m, r, 0.07, 0.3f)));
            b.register("voxelin:step." + m, s(material(m, r, 0.11, 0.3f)), s(material(m, r, 0.10, 0.28f)), s(material(m, r, 0.12, 0.3f)), s(material(m, r, 0.11, 0.26f)));
        }
        // гравець і інтерфейс
        b.register("voxelin:player.hurt", s(norm(voice(r, 0.28, 240, 150, 1, 1200, 0.2f), 0.9f)), s(norm(voice(r, 0.25, 220, 140, 1, 1100, 0.2f), 0.9f)));
        { float[] a = buf(0.45); for (int k = 0; k < 3; k++) { float[] c = material("dirt", r, 0.09, 0.6f); add(a, c, (int) (k * 0.14 * R), 1); } b.register("voxelin:player.eat", s(norm(a, 0.7f))); }
        { float[] a = buf(0.12); tone(a, 0, a.length, 700, 1400, 0, 0.6f); env(a, 0.003, 0.05); b.register("voxelin:item.pickup", s(norm(a, 0.45f))); }
        { float[] a = buf(0.05); tone(a, 0, a.length, 1300, 1200, 2, 0.4f); lowpass(a, 5000); env(a, 0.001, 0.012); b.register("voxelin:ui.click", s(norm(a, 0.4f))); }
        { float[] a = buf(0.5); tone(a, 0, a.length, 120, 220, 1, 0.5f); noise(a, r, 0.15f); lowpass(a, 900); env(a, 0.05, 0.2); b.register("voxelin:chest.open", s(norm(a, 0.6f))); }
        { float[] a = buf(0.18); noise(a, r, 1); band(a, 300, 2500); env(a, 0.005, 0.05); float[] t = buf(0.18); tone(t, 0, t.length, 110, 60, 0, 1); env(t, 0.002, 0.05); add(a, t, 0, 1); b.register("voxelin:player.attack", s(norm(a, 0.7f))); }
        { float[] a = buf(0.25); noise(a, r, 1); band(a, 400, 3000); env(a, 0.06, 0.06); b.register("voxelin:player.swing", s(norm(a, 0.35f))); }
        // істоти
        b.register("voxelin:mob.chicken.idle", s(norm(voice(r, 0.12, 1500, 1900, 2, 4000, 0f), 0.4f)), s(norm(voice(r, 0.1, 1700, 1500, 2, 4000, 0f), 0.4f)));
        b.register("voxelin:mob.chicken.hurt", s(norm(voice(r, 0.15, 2000, 1300, 2, 4500, 0.1f), 0.5f)));
        b.register("voxelin:mob.cow.idle", s(norm(voice(r, 0.9, 120, 95, 1, 700, 0.03f), 0.6f)), s(norm(voice(r, 1.1, 105, 90, 1, 650, 0.03f), 0.6f)));
        b.register("voxelin:mob.cow.hurt", s(norm(voice(r, 0.35, 160, 110, 1, 900, 0.05f), 0.6f)));
        b.register("voxelin:mob.zombie.idle", s(norm(voice(r, 1.0, 85, 70, 1, 500, 0.25f), 0.55f)), s(norm(voice(r, 0.8, 95, 75, 1, 550, 0.25f), 0.55f)));
        b.register("voxelin:mob.zombie.hurt", s(norm(voice(r, 0.3, 120, 80, 1, 700, 0.3f), 0.6f)));
        b.register("voxelin:mob.spider.idle", s(norm(hiss(r, 0.5), 0.4f)), s(norm(hiss(r, 0.4), 0.4f)));
        b.register("voxelin:mob.spider.hurt", s(norm(hiss(r, 0.25), 0.55f)));
        b.register("voxelin:mob.villager.idle", s(norm(voice(r, 0.35, 190, 150, 2, 1100, 0.02f), 0.45f)), s(norm(voice(r, 0.3, 160, 200, 2, 1100, 0.02f), 0.45f)));
        b.register("voxelin:mob.villager.hurt", s(norm(voice(r, 0.25, 230, 160, 2, 1300, 0.05f), 0.5f)));
        b.register("voxelin:mob.ender_dragon.idle", s(norm(roar(r, 1.6, 55), 0.85f)));
        b.register("voxelin:mob.ender_dragon.hurt", s(norm(roar(r, 0.6, 70), 0.85f)));
        b.register("voxelin:mob.ender_dragon.flap", s(norm(whoosh(r, 0.5, 600), 0.6f)));
        b.register("voxelin:mob.seam_weaver.idle", s(norm(chime(r, 1.2, 660), 0.5f)));
        b.register("voxelin:mob.seam_unraveler.idle", s(norm(roar(r, 1.0, 80), 0.7f)));
        b.register("voxelin:mob.echo.idle", s(norm(whisper(r, 1.2), 0.45f)), s(norm(whisper(r, 0.9), 0.45f)));
        b.register("voxelin:mob.echo.hurt", s(norm(whisper(r, 0.3), 0.6f)));
        b.register("voxelin:mob.seam_keeper.idle", s(norm(voice(r, 1.4, 98, 96, 0, 600, 0.01f), 0.35f)));
        b.register("voxelin:mob.hurt", s(norm(voice(r, 0.2, 300, 200, 1, 1500, 0.2f), 0.5f)));
        { float[] a = buf(0.6); noise(a, r, 1); lowpass(a, 600); env(a, 0.01, 0.15); b.register("voxelin:mob.death", s(norm(a, 0.5f))); }
        // світ і сюжет
        { float[] a = buf(2.0); for (int k = 0; k < 3; k++) tone(a, 0, a.length, 220 * (k + 1), 440 * (k + 1), 0, 0.3f / (k + 1)); noise(a, r, 0.1f); lowpass(a, 3000); env(a, 0.3, 0.7); b.register("voxelin:portal.travel", s(norm(a, 0.7f))); }
        { float[] a = whoosh(r, 1.0, 1500); float[] c = chime(r, 1.0, 880); add(a, c, (int) (0.3 * R), 0.7f); b.register("voxelin:rift.open", s(norm(a, 0.7f))); }
        { float[] a = buf(0.9); tone(a, 0, a.length, 48, 38, 0, 1); env(a, 0.02, 0.18); float[] c = a.clone(); float[] z = buf(1.8); add(z, a, 0, 1); add(z, c, (int) (0.35 * R), 0.7f); b.register("voxelin:seam.pulse", s(norm(z, 0.8f))); }
        { float[] a = chime(r, 2.5, 523); add(a, chime(r, 2.2, 659), (int) (0.25 * R), 0.8f); add(a, chime(r, 2.0, 784), (int) (0.5 * R), 0.7f); b.register("voxelin:record.unlock", s(norm(a, 0.5f))); }
        // петлі оточення
        b.register("voxelin:ambient.wind", s(windLoop(r, 14, 500, 0.35f)));
        b.register("voxelin:ambient.night", s(nightLoop(r, 12)));
        b.register("voxelin:ambient.cave", s(droneLoop(r, 12, 55, 0.05f, 300)));
        b.register("voxelin:ambient.end", s(windLoop(r, 14, 300, 0.25f)));
        b.register("voxelin:ambient.between", s(droneLoop(r, 8, 73, 0.08f, 1200)));
        b.register("voxelin:ambient.between_dark", s(droneLoop(r, 8, 49, 0.12f, 500)));
        b.register("voxelin:ambient.glide", s(windLoop(r, 3, 1800, 0.6f)));
        return b;
    }

    static float[] hiss(Random r, double dur) { float[] a = buf(dur); noise(a, r, 1); band(a, 2500, 7000); env(a, 0.05, dur * 0.4); wobble(a, 18, 0.5f, 0); return a; }
    static float[] roar(Random r, double dur, double f) { float[] a = buf(dur); tone(a, 0, a.length, f * 1.2, f, 1, 0.7f); tone(a, 0, a.length, f * 1.51, f * 1.3, 1, 0.4f); noise(a, r, 0.5f); lowpass(a, 700); env(a, 0.15, dur * 0.5); return a; }
    static float[] whoosh(Random r, double dur, double cut) { float[] a = buf(dur); noise(a, r, 1); lowpass(a, cut); highpass(a, 80); env(a, dur * 0.35, dur * 0.25); return a; }
    static float[] chime(Random r, double dur, double f) { float[] a = buf(dur); tone(a, 0, a.length, f, f, 0, 0.6f); tone(a, 0, a.length, f * 2.76, f * 2.76, 0, 0.2f); tone(a, 0, a.length, f * 5.4, f * 5.4, 0, 0.08f); env(a, 0.003, dur * 0.3); return a; }
    static float[] whisper(Random r, double dur) { float[] a = buf(dur); noise(a, r, 1); band(a, 1200, 5000); env(a, dur * 0.3, dur * 0.35); wobble(a, 7 + r.nextInt(5), 0.8f, r.nextDouble() * 6); return a; }

    static float[] windLoop(Random r, double sec, double cut, float amp) {
        float[] a = buf(sec + 1); noise(a, r, 1); lowpass(a, cut); lowpass(a, cut); highpass(a, 40);
        wobble(a, 0.17, 0.6f, 0); wobble(a, 0.41, 0.3f, 1.3);
        return norm(loopable(a, 1), amp);
    }

    static float[] nightLoop(Random r, double sec) {
        float[] a = buf(sec + 1); noise(a, r, 0.05f); lowpass(a, 400);
        for (double t = 0.1; t < sec; t += 0.35 + r.nextDouble() * 0.6) { // цвіркуни: короткі серії високих «тр-р»
            for (int k = 0; k < 3; k++) { float[] c = buf(0.03); tone(c, 0, c.length, 4600, 4500, 0, 0.3f); env(c, 0.003, 0.008); add(a, c, (int) ((t + k * 0.045) * R), 1); }
        }
        return norm(loopable(a, 1), 0.3f);
    }

    static float[] droneLoop(Random r, double sec, double f, float noiseAmt, double cut) {
        float[] a = buf(sec + 1);
        tone(a, 0, a.length, f, f, 0, 0.5f); tone(a, 0, a.length, f * 1.006, f * 1.006, 0, 0.4f); tone(a, 0, a.length, f * 1.5, f * 1.5, 0, 0.15f);
        noise(a, r, noiseAmt); lowpass(a, cut);
        wobble(a, 0.13, 0.35f, 0);
        return norm(loopable(a, 1), 0.4f);
    }
}
