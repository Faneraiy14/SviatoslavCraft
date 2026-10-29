package voxelin.client.audio;

import javax.sound.sampled.*;
import java.io.File;
import java.util.*;

/**
 * Банк звуків: назва ("voxelin:block.stone.break") -> варіанти. Згенеровані кодом (SoundGen) можна перекрити своїми .wav у теці sounds/:
 * файл block.stone.break.wav (або block.stone.break_2.wav для кількох варіантів) замінює всі згенеровані варіанти цієї назви.
 */
public final class SoundBank {
    private final Map<String, List<Sound>> map = new HashMap<>();

    public void register(String id, Sound... variants) {
        if (variants.length == 0) return;
        map.computeIfAbsent(id, k -> new ArrayList<>()).addAll(Arrays.asList(variants));
    }

    public boolean has(String id) { return map.containsKey(id); }
    public Set<String> ids() { return Collections.unmodifiableSet(map.keySet()); }
    public List<Sound> variants(String id) { return map.getOrDefault(id, List.of()); }

    public Sound pick(String id, Random rng) {
        List<Sound> v = map.get(id);
        return v == null || v.isEmpty() ? null : v.get(rng.nextInt(v.size()));
    }

    /** Перекрити звуки файлами .wav із теки (назва файла без «.wav» і без «_N» = назва звуку з префіксом voxelin:). Повертає, скільки прочитано. */
    public int loadOverrides(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".wav"));
        if (files == null) return 0;
        Arrays.sort(files);
        Map<String, List<Sound>> loaded = new HashMap<>();
        for (File f : files) {
            String base = f.getName().substring(0, f.getName().length() - 4).replaceAll("_\\d+$", "");
            if (!base.matches("[a-z0-9_.]+")) { System.out.println("[Voxelin] ⚠️ звук " + f.getName() + ": погана назва"); continue; }
            Sound s = readWav(f);
            if (s == null) continue;
            loaded.computeIfAbsent(base.contains(":") ? base : "voxelin:" + base, k -> new ArrayList<>()).add(s);
        }
        for (Map.Entry<String, List<Sound>> e : loaded.entrySet()) map.put(e.getKey(), e.getValue());
        int n = 0; for (List<Sound> l : loaded.values()) n += l.size();
        if (n > 0) System.out.println("[Voxelin] власних звуків: " + n);
        return n;
    }

    /** Прочитати .wav (будь-який формат, що вміє Java) як моно 44.1 кГц; null і попередження, якщо не вийшло. */
    public static Sound readWav(File f) {
        try (AudioInputStream in0 = AudioSystem.getAudioInputStream(f)) {
            AudioFormat src = in0.getFormat();
            AudioFormat pcm16 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16, src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(pcm16, in0)) {
                byte[] b = in.readAllBytes();
                int ch = Math.max(1, pcm16.getChannels()), frames = b.length / (2 * ch);
                if (frames == 0 || frames > Sound.RATE * 120) { System.out.println("[Voxelin] ⚠️ звук " + f.getName() + ": порожній або довший за 2 хв"); return null; }
                float[] mono = new float[frames];
                for (int i = 0; i < frames; i++) {
                    float sum = 0;
                    for (int c = 0; c < ch; c++) { int o = (i * ch + c) * 2; sum += (short) ((b[o] & 0xFF) | (b[o + 1] << 8)) / 32768f; }
                    mono[i] = sum / ch;
                }
                double ratio = Sound.RATE / (double) pcm16.getSampleRate();
                if (Math.abs(ratio - 1) < 1e-9) return new Sound(mono);
                float[] out = new float[Math.max(1, (int) (frames * ratio))]; // проста лінійна передискретизація
                for (int i = 0; i < out.length; i++) {
                    double p = i / ratio; int a = (int) p; float t = (float) (p - a);
                    out[i] = mono[Math.min(frames - 1, a)] * (1 - t) + mono[Math.min(frames - 1, a + 1)] * t;
                }
                return new Sound(out);
            }
        } catch (Exception e) {
            System.out.println("[Voxelin] ⚠️ звук " + f.getName() + " не прочитано: " + e.getMessage());
            return null;
        }
    }
}
