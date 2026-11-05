package voxelin.loader;

import voxelin.api.ModConfig;
import voxelin.core.Files2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** ModConfig у файлі config/<id>.properties. Один екземпляр на мод; доступ із будь-якого потоку (synchronized). */
final class ModConfigFile implements ModConfig {
    private final File file;
    private final Properties p = new Properties();
    private boolean dirty;

    ModConfigFile(File dir, String modId) {
        file = new File(dir, modId + ".properties");
        if (file.isFile() && file.length() < 1 << 20) {
            try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) { p.load(r); }
            catch (IOException | IllegalArgumentException e) { System.out.println("[Voxelin] ⚠️ налаштування мода " + modId + " не прочитано: " + e.getMessage()); }
        }
    }

    public synchronized String get(String key, String def) {
        String v = p.getProperty(key);
        if (v == null && def != null) { p.setProperty(key, def); dirty = true; return def; }
        return v;
    }
    public int getInt(String key, int def) { try { return Integer.parseInt(get(key, String.valueOf(def)).trim()); } catch (NumberFormatException e) { return def; } }
    public boolean getBool(String key, boolean def) { String v = get(key, String.valueOf(def)).trim(); return v.equalsIgnoreCase("true") ? true : v.equalsIgnoreCase("false") ? false : def; }
    public synchronized void set(String key, String value) { if (key == null || value == null) throw new IllegalArgumentException("null"); p.setProperty(key, value); dirty = true; }

    public synchronized void save() {
        if (!dirty && file.isFile()) return;
        try {
            File d = file.getParentFile();
            if (d != null) d.mkdirs();
            File tmp = new File(d, file.getName() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { w.write("# Налаштування мода (можна правити; гра перечитує при запуску)\n"); p.store(w, null); } // коментар у store() екранувався б \\uXXXX
            Files2.replace(tmp, file);
            dirty = false;
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось записати " + file + ": " + e); }
    }
}
