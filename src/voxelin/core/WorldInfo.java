package voxelin.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Файл saves/<світ>/voxelin.world (UTF-8 properties): формат, назва, зерно, тип рельєфу і ПАЛІТРА - список назв блоків.
 * У чанках на диску зберігається номер у палітрі (а не id цього запуску): нові назви лише дописуються в кінець,
 * тому моди можна додавати/міняти місцями без псування світу. Старі світи v0.0.3 (без цього файлу) нова гра не показує.
 */
public final class WorldInfo {
    public static final String FILE = "voxelin.world";
    public static final int FORMAT = 2;
    public enum Type { NORMAL, FLAT }

    public String name;
    public long seed;
    public Type type = Type.NORMAL;
    public long created;
    /** Останнє місце гравця (світові одиниці, кути); hasPlayer=false - світ ще не відкривали. */
    public boolean hasPlayer;
    public long playerCx, playerCz;
    public double playerX, playerY, playerZ;
    public float playerYaw, playerPitch;
    public final List<String> palette = new ArrayList<>();

    public static boolean isValidName(String n) {
        return n != null && !n.trim().isEmpty() && n.length() <= 32 && !n.contains("/") && !n.contains("\\") && !n.contains("..")
                && !n.startsWith(".") && n.chars().noneMatch(c -> c < 32 || ":*?\"<>|".indexOf(c) >= 0);
    }

    public static boolean exists(File dir) { return new File(dir, FILE).isFile(); }

    public static WorldInfo create(File dir, String name, long seed, Type type) throws IOException {
        WorldInfo w = new WorldInfo();
        w.name = name; w.seed = seed; w.type = type; w.created = System.currentTimeMillis();
        w.palette.add(BlockRegistry.AIR);
        w.save(dir);
        return w;
    }

    public static WorldInfo load(File dir) throws IOException {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(new File(dir, FILE)), StandardCharsets.UTF_8)) { p.load(r); }
        int fmt = Integer.parseInt(p.getProperty("format", "0").trim());
        if (fmt > FORMAT) throw new IOException("світ створено новішою версією гри (формат " + fmt + ")");
        if (fmt < FORMAT) throw new IOException("світ старого формату " + fmt + " (збірка 0.1.0-dev до безмежного світу) - файли не чіпаю, створи новий світ");
        WorldInfo w = new WorldInfo();
        w.name = p.getProperty("name", dir.getName());
        w.seed = Long.parseLong(p.getProperty("seed", "0").trim());
        try { w.type = Type.valueOf(p.getProperty("type", "NORMAL").trim()); } catch (IllegalArgumentException e) { w.type = Type.NORMAL; }
        w.created = Long.parseLong(p.getProperty("created", "0").trim());
        w.hasPlayer = "true".equals(p.getProperty("hasPlayer"));
        if (w.hasPlayer) try {
            w.playerCx = Long.parseLong(p.getProperty("pcx").trim()); w.playerCz = Long.parseLong(p.getProperty("pcz").trim());
            w.playerX = Double.parseDouble(p.getProperty("px")); w.playerY = Double.parseDouble(p.getProperty("py")); w.playerZ = Double.parseDouble(p.getProperty("pz"));
            w.playerYaw = Float.parseFloat(p.getProperty("pyaw", "0")); w.playerPitch = Float.parseFloat(p.getProperty("ppitch", "0"));
            double sum = w.playerX + w.playerY + w.playerZ;
            if (Double.isNaN(sum) || Double.isInfinite(sum) || w.playerX < 0 || w.playerX >= 16 || w.playerZ < 0 || w.playerZ >= 16) w.hasPlayer = false;
        } catch (RuntimeException e) { w.hasPlayer = false; }
        for (String s : p.getProperty("palette", "").split(",")) if (!s.trim().isEmpty()) w.palette.add(s.trim());
        if (w.palette.isEmpty() || !w.palette.get(0).equals(BlockRegistry.AIR)) w.palette.add(0, BlockRegistry.AIR);
        return w;
    }

    public synchronized void save(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("не вдалось створити " + dir);
        Properties p = new Properties();
        p.setProperty("format", String.valueOf(FORMAT));
        p.setProperty("name", name);
        p.setProperty("seed", String.valueOf(seed));
        p.setProperty("type", type.name());
        p.setProperty("created", String.valueOf(created));
        p.setProperty("palette", String.join(",", palette));
        if (hasPlayer) {
            p.setProperty("hasPlayer", "true");
            p.setProperty("pcx", String.valueOf(playerCx)); p.setProperty("pcz", String.valueOf(playerCz));
            p.setProperty("px", String.valueOf(playerX)); p.setProperty("py", String.valueOf(playerY)); p.setProperty("pz", String.valueOf(playerZ));
            p.setProperty("pyaw", String.valueOf(playerYaw)); p.setProperty("ppitch", String.valueOf(playerPitch));
        }
        File tmp = new File(dir, FILE + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { p.store(w, "Voxelin world"); }
        Files2.replace(tmp, new File(dir, FILE));
    }

    public synchronized void setPlayer(Pos p, float yaw, float pitch) {
        playerCx = p.cx; playerCz = p.cz; playerX = p.x; playerY = p.y; playerZ = p.z; playerYaw = yaw; playerPitch = pitch; hasPlayer = true;
    }

    public Pos playerPos() { return new Pos(playerCx, playerCz, playerX, playerY, playerZ); }

    /** Номер у палітрі для назви (нова назва дописується). */
    public synchronized int paletteId(String blockName) {
        int i = palette.indexOf(blockName);
        if (i >= 0) return i;
        palette.add(blockName);
        return palette.size() - 1;
    }
}
