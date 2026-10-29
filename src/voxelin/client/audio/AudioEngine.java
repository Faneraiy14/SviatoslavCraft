package voxelin.client.audio;

import voxelin.core.Pos;

import javax.sound.sampled.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Рушій звуку: власний програмний мікшер (javax.sound.sampled, без сторонніх бібліотек). Ігровий потік лише кладе команди в чергу;
 * потік мікшера кожні ~20 мс змішує голоси (до MAX_VOICES; найтихіший витісняється) у стерео і пише в лінію звукової карти.
 * Позиційні звуки: гучність спадає до нуля на HEARING блоків, панорама за кутом до слухача (рівна потужність). Петлі оточення мають ключ
 * і плавно наближаються до заданої гучності. Без пристрою звуку (або в тестах) mix() працює так само - просто в масив.
 */
public final class AudioEngine implements AutoCloseable {
    public enum Cat { BLOCKS, MOBS, AMBIENT, UI }

    public static final int MAX_VOICES = 32, BLOCK_FRAMES = 882; // 20 мс
    public static final double HEARING = 32;

    private final SoundBank bank;
    private final Random rng = new Random();
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final List<Voice> voices = new ArrayList<>(); // лише потік мікшера
    private final Map<String, Voice> loops = new HashMap<>();
    /** Гучність: загальна і за категоріями (0..1). Пишеться з ігрового потоку, читається мікшером. */
    public volatile float master = 1f;
    private final float[] cat = {1f, 1f, 1f, 1f};
    private volatile Listener listener = new Listener(null, 0);

    private record Listener(Pos pos, float yaw) {}

    private static final class Voice {
        Sound s; double at, step; float vol, cur, target; Cat cat; boolean loop; Pos pos; String key; float gl = 1, gr = 1;
        float loudness() { return cur * Math.max(gl, gr); }
    }

    private SourceDataLine line;
    private Thread thread;
    private volatile boolean running;
    private ByteArrayOutputStream record;
    private volatile boolean enabled;

    public AudioEngine(SoundBank bank) { this.bank = bank; }

    public SoundBank bank() { return bank; }

    public void setCategory(Cat c, float v) { cat[c.ordinal()] = Float.isNaN(v) ? 1f : Math.max(0f, Math.min(1f, v)); }
    public float category(Cat c) { return cat[c.ordinal()]; }

    /** Слухач (очі гравця) і куди він дивиться. Викликати щокадру. */
    public void listener(Pos eye, float yaw) { listener = new Listener(eye == null ? null : eye.copy(), yaw); }

    /** Короткий звук. pos == null - без позиції (інтерфейс). pitch 1 - як є. */
    public void play(String id, Pos pos, float volume, float pitch, Cat c) {
        if (!enabled) return;
        Sound s = bank.pick(id, rng);
        if (s == null || !(volume > 0)) return;
        float p = Float.isNaN(pitch) ? 1f : Math.max(0.25f, Math.min(4f, pitch));
        Pos at = pos == null ? null : pos.copy();
        commands.add(() -> {
            Voice v = new Voice();
            v.s = s; v.step = p; v.vol = v.cur = v.target = Math.min(volume, 2f); v.cat = c; v.pos = at;
            add(v);
        });
    }

    /** Петля оточення з ключем: гучність плавно йде до volume (0 - згасає й зникає). Назву звуку можна змінити - стара згасає. */
    public void loop(String key, String id, float volume) {
        if (!enabled) return;
        Sound s = bank.pick(id, rng);
        float vol = Float.isNaN(volume) ? 0 : Math.max(0, Math.min(1.5f, volume));
        commands.add(() -> {
            Voice v = loops.get(key);
            if (v != null && s != null && v.s != s && !bank.variants(id).contains(v.s)) { v.target = 0; v.key = null; loops.remove(key); v = null; }
            if (v == null) {
                if (s == null || vol <= 0) return;
                v = new Voice(); v.s = s; v.step = 1; v.vol = 1; v.cur = 0; v.cat = Cat.AMBIENT; v.loop = true; v.key = key;
                v.at = rng.nextInt(Math.max(1, s.length()));
                loops.put(key, v);
                add(v);
            }
            v.target = vol;
        });
    }

    private void add(Voice v) {
        if (voices.size() >= MAX_VOICES) { // витіснити найтихіший короткий звук
            Voice q = null;
            for (Voice o : voices) if (!o.loop && (q == null || o.loudness() < q.loudness())) q = o;
            if (q == null) return;
            voices.remove(q);
        }
        voices.add(v);
    }

    public int voiceCount() { return voices.size(); }

    /** Змішати frames кадрів стерео (L,R,L,R...) в out. Також виконує команди з черги. Потік мікшера (або тест). */
    public void mix(float[] out, int frames) {
        for (Runnable r; (r = commands.poll()) != null; ) r.run();
        Arrays.fill(out, 0, frames * 2, 0f);
        Listener l = listener;
        float m = master;
        for (Iterator<Voice> it = voices.iterator(); it.hasNext(); ) {
            Voice v = it.next();
            gains(v, l);
            float cg = m * cat[v.cat.ordinal()];
            float fade = 1f / (Sound.RATE * 0.5f); // петлі змінюють гучність за ~0.5 с
            int n = v.s.length();
            boolean done = false;
            for (int i = 0; i < frames; i++) {
                if (v.loop) { if (v.cur < v.target) v.cur = Math.min(v.target, v.cur + fade); else if (v.cur > v.target) v.cur = Math.max(v.target, v.cur - fade); }
                int a = (int) v.at;
                if (a >= n) { if (v.loop) { v.at -= n; a = (int) v.at; } else { done = true; break; } }
                float t = (float) (v.at - a), s = v.s.sample(a) * (1 - t) + v.s.sample(Math.min(n - 1, a + 1)) * t;
                float g = s * v.cur * cg;
                out[i * 2] += g * v.gl; out[i * 2 + 1] += g * v.gr;
                v.at += v.step;
            }
            if (done || (v.loop && v.target <= 0 && v.cur <= 0)) { it.remove(); if (v.key != null && loops.get(v.key) == v) loops.remove(v.key); }
        }
        for (int i = 0; i < frames * 2; i++) { float x = out[i]; out[i] = x > 1 ? 1 : (x < -1 ? -1 : x); } // жорстка межа, без переповнення
    }

    /** Підсилення каналів за відстанню й кутом. */
    private static void gains(Voice v, Listener l) {
        if (v.pos == null || l.pos == null) { v.gl = v.gr = 1; return; }
        double dx = v.pos.relX(l.pos.cx) - l.pos.x, dz = v.pos.relZ(l.pos.cz) - l.pos.z, dy = v.pos.y - l.pos.y;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double att = d >= HEARING ? 0 : Math.pow(1 - d / HEARING, 1.6);
        double yr = Math.toRadians(l.yaw), rx = Math.cos(yr), rz = Math.sin(yr);
        double hd = Math.hypot(dx, dz), pan = hd < 1e-6 ? 0 : (dx * rx + dz * rz) / hd; // -1 ліворуч, +1 праворуч
        pan *= Math.min(1, hd / 2); // зовсім поруч - майже по центру
        double a = (pan + 1) * Math.PI / 4;
        v.gl = (float) (att * Math.cos(a) * Math.sqrt(2) * 0.85);
        v.gr = (float) (att * Math.sin(a) * Math.sqrt(2) * 0.85);
    }

    /** Відкрити звукову карту й запустити мікшер. false - пристрою нема (гра працює мовчки). */
    public boolean start() {
        try {
            AudioFormat fmt = new AudioFormat(Sound.RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, BLOCK_FRAMES * 4 * 3);
            line.start();
        } catch (Exception | LinkageError e) {
            System.out.println("[Voxelin] звук вимкнено: немає пристрою (" + e.getMessage() + ")");
            line = null;
            return false;
        }
        running = true; enabled = true;
        thread = new Thread(this::run, "voxelin-audio");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** Без звукової карти: мікшер іде в темпі реального часу, але все лише записується в пам'ять (--record-audio; нічого не звучить). */
    public void startVirtual() {
        record = new ByteArrayOutputStream(Sound.RATE * 4 * 60); // одразу на хвилину - без перевиділень посеред гри
        running = true; enabled = true;
        thread = new Thread(this::run, "voxelin-audio-virtual");
        thread.setDaemon(true);
        thread.start();
    }

    /** Для тестів: приймати команди без потоку (mix викликає тест). */
    public void enableForTest() { enabled = true; }

    private void run() {
        float[] buf = new float[BLOCK_FRAMES * 2];
        byte[] bytes = new byte[BLOCK_FRAMES * 4];
        while (running) {
            mix(buf, BLOCK_FRAMES);
            for (int i = 0; i < BLOCK_FRAMES * 2; i++) { short s = (short) Math.round(buf[i] * 32767); bytes[i * 2] = (byte) s; bytes[i * 2 + 1] = (byte) (s >> 8); }
            if (line != null) line.write(bytes, 0, bytes.length); // блокує, поки карта не забере - так потік іде в темпі звуку
            else try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            ByteArrayOutputStream r = record;
            if (r != null && r.size() < Sound.RATE * 4 * 60) r.write(bytes, 0, bytes.length);
        }
    }

    /** Записати накопичене (--record-audio) у .wav. */
    public void saveRecording(File f) {
        ByteArrayOutputStream r = record;
        if (r == null) return;
        byte[] b = r.toByteArray();
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(b), new AudioFormat(Sound.RATE, 16, 2, true, false), b.length / 4)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, f);
            System.out.println("[Voxelin] запис звуку: " + f + " (" + String.format(Locale.ROOT, "%.1f", b.length / 4.0 / Sound.RATE) + " с)");
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ запис звуку: " + e); }
    }

    @Override public void close() {
        running = false;
        if (thread != null) try { thread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        if (line != null) { line.stop(); line.close(); }
    }
}
