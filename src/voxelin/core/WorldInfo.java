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
    public enum Type { NORMAL, FLAT, END, BETWEEN }
    public enum Mode { SURVIVAL, CREATIVE }
    /** Вибір після смерті дракона. */
    public enum EggChoice { NONE, RITUAL, INFECTION }
    /** Налаштування світу «Катаклізми» (вибирається при створенні): ON - усе; MILD - зараження не доходить до кризи й прориву; OFF - розвилка лише з Ритуалом. */
    public enum Cataclysms { ON, MILD, OFF }

    public String name;
    public long seed;
    public Type type = Type.NORMAL;
    public long created;
    /** Останнє місце гравця (світові одиниці, кути); hasPlayer=false - світ ще не відкривали. */
    public boolean hasPlayer;
    public long playerCx, playerCz;
    public double playerX, playerY, playerZ;
    public float playerYaw, playerPitch;
    /** Ігровий час (тіки від початку) і довжина доби в хвилинах; старі світи без цих полів починають з початку дня. */
    public long time;
    public double dayMinutes = DayCycle.DEFAULT_MINUTES;
    /** Стан гравця (здоров'я/голод/насиченість); hasVitals=false - значення за замовчуванням. */
    public boolean hasVitals;
    public double health = 20, hunger = 20, saturation = 5;
    /** Режим гри. Старі світи без поля лишаються творчими (як у них грали), нові - виживання. */
    public Mode mode = Mode.CREATIVE;
    /** Інвентар гравця: слоти «назва|кількість|зношеність» (null - порожній) і вибраний слот; hasInv=false - ще не зберігали. */
    public boolean hasInv;
    public final String[] inv = new String[Inventory.SIZE];
    public int invSelected;
    public final String[] armor = new String[4];
    /** Лише для виміру Енд: куди повертати гравця в звичайний світ і чи вбито дракона. */
    public boolean hasReturn, dragonDead;
    /** Журнал записів (відкриті id) і сюжетний стан, спільні для вимірів світу (переносяться разом із гравцем). */
    public final java.util.LinkedHashSet<String> records = new java.util.LinkedHashSet<>();
    /** Досягнуті цілі «Шляху» (Progress) і чи вже показано титри фіналу. */
    public final java.util.LinkedHashSet<String> goals = new java.util.LinkedHashSet<>();
    public boolean creditsShown;
    /** Ліжко - точка відродження (лише звичайний світ). */
    public boolean hasBed;
    public long bedX, bedZ; public int bedY;
    public EggChoice eggChoice = EggChoice.NONE;
    public Cataclysms cataclysms = Cataclysms.ON;
    /** Ритуал: 0 не розпочато, 1 боси в Енді, 2 виконано. */
    public int ritualStage;
    /** Ритуал: хто з босів уже впав (1 - Ткач, 2 - Розплітач). */
    public int ritualKills;
    /** Зараження (лише звичайний світ): стан, ядро, час початку. */
    public enum InfState { NONE, ACTIVE, CURED, BURNED, TAMED }
    public InfState infState = InfState.NONE;
    public long infX, infZ, infStart; public int infY;
    /** Накопичені спроби росту зараження (виконуються, коли ділянка ядра завантажена). */
    public double infOwed;
    /** Криза: село, на яке пішов Шов (центр), і чи вже посіяно там наріст. */
    public boolean infVillage, infFrontSeeded;
    public long infVx, infVz;
    /** Міжсвіття: стан зараження звичайного світу, з якого прийшов гравець (настрій виміру). */
    public InfState overInf = InfState.NONE;
    /** Відкриті розриви Шву: {x, y (нижній блок), z, час закриття}. */
    public final java.util.List<long[]> rifts = new java.util.ArrayList<>();
    public long returnX, returnZ;
    public int returnY;
    public final List<String> palette = new ArrayList<>();

    public static boolean isValidName(String n) {
        return n != null && !n.trim().isEmpty() && n.length() <= 32 && !n.contains("/") && !n.contains("\\") && !n.contains("..")
                && !n.startsWith(".") && n.chars().noneMatch(c -> c < 32 || ":*?\"<>|".indexOf(c) >= 0);
    }

    public static boolean exists(File dir) { return new File(dir, FILE).isFile(); }

    public static WorldInfo create(File dir, String name, long seed, Type type) throws IOException {
        WorldInfo w = new WorldInfo();
        w.name = name; w.seed = seed; w.type = type; w.mode = Mode.SURVIVAL; w.created = System.currentTimeMillis();
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
        try { w.time = Math.max(0, Long.parseLong(p.getProperty("time", "0").trim())); } catch (NumberFormatException e) { w.time = 0; }
        try { double m = Double.parseDouble(p.getProperty("dayMinutes", String.valueOf(DayCycle.DEFAULT_MINUTES)).trim()); w.dayMinutes = Double.isNaN(m) ? DayCycle.DEFAULT_MINUTES : Math.max(DayCycle.MIN_MINUTES, Math.min(DayCycle.MAX_MINUTES, m)); }
        catch (NumberFormatException e) { w.dayMinutes = DayCycle.DEFAULT_MINUTES; }
        try { w.mode = Mode.valueOf(p.getProperty("mode", "CREATIVE").trim()); } catch (IllegalArgumentException e) { w.mode = Mode.CREATIVE; }
        w.hasInv = "true".equals(p.getProperty("hasInv"));
        for (int i = 0; i < Inventory.SIZE; i++) w.inv[i] = p.getProperty("inv." + i);
        for (int i = 0; i < 4; i++) w.armor[i] = p.getProperty("armor." + i);
        try { w.invSelected = Math.max(0, Math.min(Inventory.HOTBAR - 1, Integer.parseInt(p.getProperty("invSelected", "0").trim()))); } catch (NumberFormatException e) { w.invSelected = 0; }
        w.dragonDead = "true".equals(p.getProperty("dragonDead"));
        for (String r : p.getProperty("records", "").split(",")) if (r.trim().matches("[a-z0-9_]+:[a-z0-9_/]+")) w.records.add(r.trim());
        for (String g : p.getProperty("goals", "").split(",")) if (g.trim().matches("[a-z0-9_]+")) w.goals.add(g.trim());
        w.creditsShown = "true".equals(p.getProperty("creditsShown"));
        try { w.bedX = Long.parseLong(p.getProperty("bedX").trim()); w.bedY = Integer.parseInt(p.getProperty("bedY").trim()); w.bedZ = Long.parseLong(p.getProperty("bedZ").trim()); w.hasBed = w.bedY >= 0 && w.bedY < Chunk.HEIGHT; }
        catch (RuntimeException e) { w.hasBed = false; }
        try { w.eggChoice = EggChoice.valueOf(p.getProperty("eggChoice", "NONE").trim()); } catch (IllegalArgumentException e) { w.eggChoice = EggChoice.NONE; }
        try { w.cataclysms = Cataclysms.valueOf(p.getProperty("cataclysms", "ON").trim()); } catch (IllegalArgumentException e) { w.cataclysms = Cataclysms.ON; }
        try { w.infState = InfState.valueOf(p.getProperty("infState", "NONE").trim()); } catch (IllegalArgumentException e) { w.infState = InfState.NONE; }
        if (w.infState != InfState.NONE) {
            try { w.infX = Long.parseLong(p.getProperty("infX").trim()); w.infY = Integer.parseInt(p.getProperty("infY").trim()); w.infZ = Long.parseLong(p.getProperty("infZ").trim()); w.infStart = Long.parseLong(p.getProperty("infStart").trim()); }
            catch (RuntimeException e) { w.infState = InfState.NONE; }
            if (w.infY < 0 || w.infY >= Chunk.HEIGHT) w.infState = InfState.NONE;
            try { w.infVx = Long.parseLong(p.getProperty("infVx").trim()); w.infVz = Long.parseLong(p.getProperty("infVz").trim()); w.infVillage = true; w.infFrontSeeded = "true".equals(p.getProperty("infFront")); }
            catch (RuntimeException e) { w.infVillage = false; }
            try { double o = Double.parseDouble(p.getProperty("infOwed", "0").trim()); w.infOwed = Double.isNaN(o) || o < 0 ? 0 : Math.min(Infection.OWED_CAP, o); } catch (NumberFormatException e) { w.infOwed = 0; }
        }
        try { w.ritualKills = Math.max(0, Math.min(3, Integer.parseInt(p.getProperty("ritualKills", "0").trim()))); } catch (NumberFormatException e) { w.ritualKills = 0; }
        try { w.overInf = InfState.valueOf(p.getProperty("overInf", "NONE").trim()); } catch (IllegalArgumentException e) { w.overInf = InfState.NONE; }
        for (String r : p.getProperty("rifts", "").split(";")) {
            String[] q = r.trim().split(",");
            if (q.length != 4 || w.rifts.size() >= 64) continue;
            try { long ry = Long.parseLong(q[1]); if (ry >= 0 && ry < Chunk.HEIGHT) w.rifts.add(new long[]{Long.parseLong(q[0]), ry, Long.parseLong(q[2]), Long.parseLong(q[3])}); } catch (NumberFormatException e) { /* битий запис */ }
        }
        try { w.ritualStage = Math.max(0, Math.min(2, Integer.parseInt(p.getProperty("ritualStage", "0").trim()))); } catch (NumberFormatException e) { w.ritualStage = 0; }
        try {
            w.returnX = Long.parseLong(p.getProperty("returnX").trim()); w.returnY = Integer.parseInt(p.getProperty("returnY").trim()); w.returnZ = Long.parseLong(p.getProperty("returnZ").trim());
            w.hasReturn = w.returnY >= 0 && w.returnY < 256;
        } catch (RuntimeException e) { w.hasReturn = false; }
        w.hasVitals = "true".equals(p.getProperty("hasVitals"));
        if (w.hasVitals) try {
            w.health = Double.parseDouble(p.getProperty("health")); w.hunger = Double.parseDouble(p.getProperty("hunger")); w.saturation = Double.parseDouble(p.getProperty("saturation", "0"));
            if (Double.isNaN(w.health + w.hunger + w.saturation)) w.hasVitals = false;
        } catch (RuntimeException e) { w.hasVitals = false; }
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
        p.setProperty("mode", mode.name());
        if (hasInv) { p.setProperty("hasInv", "true"); p.setProperty("invSelected", String.valueOf(invSelected)); for (int i = 0; i < Inventory.SIZE; i++) if (inv[i] != null) p.setProperty("inv." + i, inv[i]); }
        if (hasInv) for (int i = 0; i < 4; i++) if (armor[i] != null) p.setProperty("armor." + i, armor[i]);
        if (dragonDead) p.setProperty("dragonDead", "true");
        if (!records.isEmpty()) p.setProperty("records", String.join(",", records));
        if (!goals.isEmpty()) p.setProperty("goals", String.join(",", goals));
        if (creditsShown) p.setProperty("creditsShown", "true");
        if (hasBed) { p.setProperty("bedX", String.valueOf(bedX)); p.setProperty("bedY", String.valueOf(bedY)); p.setProperty("bedZ", String.valueOf(bedZ)); }
        if (eggChoice != EggChoice.NONE) p.setProperty("eggChoice", eggChoice.name());
        if (cataclysms != Cataclysms.ON) p.setProperty("cataclysms", cataclysms.name());
        if (infState != InfState.NONE) { p.setProperty("infState", infState.name()); p.setProperty("infX", String.valueOf(infX)); p.setProperty("infY", String.valueOf(infY)); p.setProperty("infZ", String.valueOf(infZ)); p.setProperty("infStart", String.valueOf(infStart)); p.setProperty("infOwed", String.valueOf(infOwed));
            if (infVillage) { p.setProperty("infVx", String.valueOf(infVx)); p.setProperty("infVz", String.valueOf(infVz)); if (infFrontSeeded) p.setProperty("infFront", "true"); } }
        if (ritualKills != 0) p.setProperty("ritualKills", String.valueOf(ritualKills));
        if (overInf != InfState.NONE) p.setProperty("overInf", overInf.name());
        if (!rifts.isEmpty()) { StringBuilder sb = new StringBuilder(); for (long[] r : rifts) { if (sb.length() > 0) sb.append(';'); sb.append(r[0]).append(',').append(r[1]).append(',').append(r[2]).append(',').append(r[3]); } p.setProperty("rifts", sb.toString()); }
        if (ritualStage != 0) p.setProperty("ritualStage", String.valueOf(ritualStage));
        if (hasReturn) { p.setProperty("returnX", String.valueOf(returnX)); p.setProperty("returnY", String.valueOf(returnY)); p.setProperty("returnZ", String.valueOf(returnZ)); }
        p.setProperty("time", String.valueOf(time));
        p.setProperty("dayMinutes", String.valueOf(dayMinutes));
        if (hasVitals) { p.setProperty("hasVitals", "true"); p.setProperty("health", String.valueOf(health)); p.setProperty("hunger", String.valueOf(hunger)); p.setProperty("saturation", String.valueOf(saturation)); }
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

    /** Перенести дані гравця (інвентар, броня, здоров'я/голод, режим) з іншого виміру. */
    public synchronized void copyPlayerFrom(WorldInfo o) {
        hasInv = o.hasInv; invSelected = o.invSelected;
        System.arraycopy(o.inv, 0, inv, 0, inv.length);
        System.arraycopy(o.armor, 0, armor, 0, armor.length);
        hasVitals = o.hasVitals; health = o.health; hunger = o.hunger; saturation = o.saturation;
        mode = o.mode;
        records.addAll(o.records);
        goals.addAll(o.goals);
        creditsShown |= o.creditsShown;
        if (o.eggChoice != EggChoice.NONE) eggChoice = o.eggChoice;
        cataclysms = o.cataclysms; // налаштування одне на всі виміри світу
        ritualStage = Math.max(ritualStage, o.ritualStage);
        if (o.dragonDead && type == Type.END) dragonDead = true;
        if (type == Type.BETWEEN && o.type != Type.END && o.type != Type.BETWEEN) overInf = o.infState;
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
