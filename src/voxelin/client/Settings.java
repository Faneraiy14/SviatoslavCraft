package voxelin.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.Properties;

/**
 * Налаштування гравця (файл settings.properties поруч зі збереженнями): розмір текстур (0 = автоматично), дальність огляду, чутливість миші, поле зору,
 * згладжування, вертикальна синхронізація, контури блоків. Биті або чужі значення замінюються типовими й обрізаються до меж. Прапорці командного рядка
 * (Voxelin.Options) перекривають файл.
 */
public final class Settings {
    public static final int[] TEXTURE_SIZES = {0, 16, 32, 64};
    public static final int[] MSAA_VALUES = {0, 2, 4};

    public int textureSize = 0, renderDistance = 12, fov = 70, msaa = 2;
    public float mouseSensitivity = 0.12f;
    public boolean vsync = true, outlines = true;
    /** Гучність у відсотках (0..100, крок 10): загальна, блоки, істоти, оточення, інтерфейс. */
    public int volMaster = 100, volBlocks = 100, volMobs = 100, volAmbient = 80, volUi = 100;

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int vol(int v) { return clamp(Math.round(v / 10f) * 10, 0, 100); }

    /** Привести значення до допустимих (використовується після читання й зі стрілок на екрані). */
    public void clampAll() {
        textureSize = textureSize >= 64 ? 64 : (textureSize >= 32 ? 32 : (textureSize >= 16 ? 16 : 0));
        renderDistance = clamp(renderDistance, 4, 24);
        fov = clamp(fov, 60, 100);
        msaa = msaa >= 4 ? 4 : (msaa >= 2 ? 2 : 0);
        if (Float.isNaN(mouseSensitivity)) mouseSensitivity = 0.12f;
        volMaster = vol(volMaster); volBlocks = vol(volBlocks); volMobs = vol(volMobs); volAmbient = vol(volAmbient); volUi = vol(volUi);
        mouseSensitivity = Math.max(0.04f, Math.min(0.30f, Math.round(mouseSensitivity * 100) / 100f));
    }

    public static Settings load(File f) {
        Settings s = new Settings();
        if (f == null || !f.isFile()) return s;
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) { p.load(r); }
        catch (IOException | IllegalArgumentException e) { System.out.println("[Voxelin] ⚠️ налаштування не прочитано (" + e + "), типові"); return s; }
        s.textureSize = "auto".equalsIgnoreCase(p.getProperty("textureSize", "auto").trim()) ? 0 : intOf(p, "textureSize", 0);
        s.renderDistance = intOf(p, "renderDistance", s.renderDistance);
        s.fov = intOf(p, "fov", s.fov);
        s.msaa = intOf(p, "msaa", s.msaa);
        try { s.mouseSensitivity = Float.parseFloat(p.getProperty("mouseSensitivity", "0.12").trim()); } catch (NumberFormatException e) { s.mouseSensitivity = 0.12f; }
        s.vsync = !"false".equalsIgnoreCase(p.getProperty("vsync", "true").trim());
        s.outlines = !"false".equalsIgnoreCase(p.getProperty("outlines", "true").trim());
        s.volMaster = intOf(p, "volume.master", s.volMaster); s.volBlocks = intOf(p, "volume.blocks", s.volBlocks); s.volMobs = intOf(p, "volume.mobs", s.volMobs);
        s.volAmbient = intOf(p, "volume.ambient", s.volAmbient); s.volUi = intOf(p, "volume.ui", s.volUi);
        s.clampAll();
        return s;
    }

    private static int intOf(Properties p, String k, int def) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim()); } catch (NumberFormatException e) { return def; }
    }

    public void save(File f) {
        clampAll();
        Properties p = new Properties();
        p.setProperty("textureSize", textureSize == 0 ? "auto" : String.valueOf(textureSize));
        p.setProperty("renderDistance", String.valueOf(renderDistance));
        p.setProperty("fov", String.valueOf(fov));
        p.setProperty("msaa", String.valueOf(msaa));
        p.setProperty("mouseSensitivity", String.format(Locale.ROOT, "%.2f", mouseSensitivity));
        p.setProperty("vsync", String.valueOf(vsync));
        p.setProperty("outlines", String.valueOf(outlines));
        p.setProperty("volume.master", String.valueOf(volMaster)); p.setProperty("volume.blocks", String.valueOf(volBlocks)); p.setProperty("volume.mobs", String.valueOf(volMobs));
        p.setProperty("volume.ambient", String.valueOf(volAmbient)); p.setProperty("volume.ui", String.valueOf(volUi));
        try {
            File dir = f.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { p.store(w, "Voxelin settings"); }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException e) {
            try { // на файлових системах без атомарного переміщення - звичайна заміна
                File tmp = new File(f.getAbsolutePath() + ".tmp");
                if (tmp.isFile()) Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) { System.out.println("[Voxelin] ⚠️ не вдалось зберегти налаштування: " + e2); }
        }
    }

    /** Евристика за назвою відеокарти: дискретна (NVIDIA/GeForce/Radeon RX/AMD Radeon без «Graphics») - 32, решта (Intel, вбудована Radeon Graphics, програмний рендер) - 16. */
    public static int autoTextureSize(String glRenderer) {
        String r = glRenderer == null ? "" : glRenderer.toLowerCase(Locale.ROOT);
        if (r.contains("llvmpipe") || r.contains("softpipe") || r.contains("software") || r.contains("intel")) return 16;
        if (r.contains("nvidia") || r.contains("geforce") || r.contains("quadro") || r.contains("rtx") || r.contains("gtx")) return 32;
        if ((r.contains("radeon") || r.contains("amd")) && !r.contains("graphics")) return 32;
        return 16;
    }

    /** Розмір текстур, який справді буде використано (авто розв'язується за відеокартою). */
    public int resolvedTextureSize(String glRenderer) { return textureSize == 0 ? autoTextureSize(glRenderer) : textureSize; }
}
