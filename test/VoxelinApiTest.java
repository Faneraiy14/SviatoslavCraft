import voxelin.api.*;
import voxelin.api.event.*;
import voxelin.core.*;
import voxelin.core.entity.Mob;
import voxelin.loader.Game;
import voxelin.loader.ModLoader;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * API модів: (1) знімок публічних сигнатур voxelin.api (docs/api-signatures.txt) - тест падає, якщо щось зникло чи змінилось
 * (зламало б готові моди) або з'явилось без оновлення знімка (нове API має бути свідомим: ./build.sh api-snapshot);
 * (2) сервіси ctx.api, можливості ctx.has і Voxelin-Api-Requires; (3) подія смерті істоти.
 */
public class VoxelinApiTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    public static void main(String[] a) throws Exception {
        File out = new File(a[0]);
        List<String> sig = signatures(new File(out, "voxelin/api"));
        if (a.length > 2 && a[1].equals("--write")) {
            Files.write(Paths.get(a[2]), sig);
            System.out.println("знімок API записано: " + a[2] + " (" + sig.size() + " рядків)");
            return;
        }
        File snap = new File(out.getParentFile(), "docs/api-signatures.txt");
        check(snap.isFile(), "є знімок API docs/api-signatures.txt");
        Set<String> old = new LinkedHashSet<>(Files.readAllLines(snap.toPath()));
        List<String> gone = new ArrayList<>(), added = new ArrayList<>();
        for (String s : old) if (!sig.contains(s)) gone.add(s);
        for (String s : sig) if (!old.contains(s)) added.add(s);
        if (!gone.isEmpty()) System.out.println("Зникло або змінилось у voxelin.api (зламає готові моди - поверни, або підніми VoxelinApi.VERSION і онови знімок):\n  " + String.join("\n  ", gone));
        if (!added.isEmpty()) System.out.println("Нове у voxelin.api без оновлення знімка (додай можливість у VoxelinApi.FEATURES і запусти ./build.sh api-snapshot):\n  " + String.join("\n  ", added));
        check(gone.isEmpty(), "нічого не зникло з API");
        check(added.isEmpty(), "нове API внесено в знімок");

        // можливості й вимоги мода
        check(VoxelinApi.has("events.chat") && VoxelinApi.has(" service.config ") && !VoxelinApi.has("events.dragon_dance") && !VoxelinApi.has(null), "VoxelinApi.has");
        File root = Files.createTempDirectory("vx-api").toFile();
        File mods = new File(root, "mods");
        mods.mkdirs();
        jar(new File(mods, "a-future.jar"), "future", "events.chat, events.dragon_dance");
        jar(new File(mods, "b-ok.jar"), "okmod", "events.chat,service.config");
        Game g = new Game(new File(root, "saves"), mods);
        ModLoader.ModInfo future = g.mods.mods().stream().filter(m -> m.id.equals("future")).findFirst().orElse(null);
        check(future != null && future.status == ModLoader.Status.INCOMPATIBLE && future.message.contains("events.dragon_dance") && !future.message.contains("events.chat,"), "мод з невідомою можливістю - «онови гру»: " + future);
        ModLoader.ModInfo ok = g.mods.mods().stream().filter(m -> m.id.equals("okmod")).findFirst().orElse(null);
        check(ok != null && ok.status != ModLoader.Status.INCOMPATIBLE, "мод з наявними можливостями проходить перевірку вимог: " + ok);

        // сервіси: свій екземпляр на мод, файл config/<мод>.properties
        ModConfig c1 = g.mods.services.get(ModConfig.class, "tester"), c2 = g.mods.services.get(ModConfig.class, "tester");
        check(c1 != null && c1 == c2 && g.mods.services.get(ModConfig.class, "other") != c1, "ModConfig: один на мод");
        check(c1.getInt("radius", 7) == 7 && c1.getBool("loud", true) && c1.get("name", "x").equals("x"), "типові значення");
        c1.set("radius", "12");
        c1.save();
        File cf = new File(root, "config/tester.properties");
        check(cf.isFile() && Files.readString(cf.toPath()).contains("radius=12") && Files.readString(cf.toPath()).contains("loud=true"), "файл налаштувань мода з типовими й зміненими");
        Files.writeString(cf.toPath(), "radius=oops\n");
        check(new Game(new File(root, "saves2"), mods).mods.services.get(ModConfig.class, "tester").getInt("radius", 3) == 3, "зіпсоване число - типове значення");
        check(g.mods.services.get(Registries.class, "x") == null, "невідомий сервіс - null");
        boolean threw = false;
        try { g.mods.services.provide(Runnable.class, id -> () -> {}); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "сервіс лише з voxelin.api");

        // ctx.api і ctx.has з мода
        Object[] seen = new Object[2];
        g.mods.load(new VoxelinMod() { public void init(ModContext ctx) { seen[0] = ctx.api(ModConfig.class); seen[1] = ctx.has("events.mob_death"); } }, "probe", "(тест)");
        check(seen[0] instanceof ModConfig && Boolean.TRUE.equals(seen[1]), "мод отримує ModConfig і бачить можливості");

        // смерть істоти - одна подія
        g.createWorld("w", 1, WorldInfo.Type.FLAT);
        World w = g.openWorld("w");
        w.naturalSpawning = false;
        for (int i = 0; i < 600 && w.chunk(0, 0) == null; i++) { w.update(0, 0, 1); Thread.sleep(5); }
        List<MobDeathEvent> deaths = new ArrayList<>();
        g.events.on("test", MobDeathEvent.class, deaths::add);
        int y = 1; while (w.blockId(3, y, 3) != 0) y++;
        Mob m = w.spawnMob(w.mobTypes.get("voxelin:zombie"), Pos.ofBlock(3, y + 0.01, 3));
        check(m != null, "зомбі з'явився");
        w.hurtMob(m, 10_000, 0, 0, m.pos);
        for (int i = 0; i < 60; i++) w.tick();
        check(deaths.size() == 1 && deaths.get(0).mob.equals("voxelin:zombie") && deaths.get(0).x == 3 && deaths.get(0).z == 3 && !deaths.get(0).boss, "MobDeathEvent один раз, вид і місце: " + deaths.size());
        w.close();
        System.out.println("VoxelinApiTest: " + n + " перевірок OK (сигнатур API: " + sig.size() + ")");
        System.exit(0);
    }

    /** Мод-заглушка: лише маніфест (вимоги перевіряються до завантаження класу). */
    static void jar(File f, String id, String requires) throws IOException {
        Manifest mf = new Manifest();
        Attributes at = mf.getMainAttributes();
        at.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        at.putValue("Voxelin-Mod", "nope.Missing");
        at.putValue("Voxelin-Mod-Id", id);
        at.putValue("Voxelin-Api", String.valueOf(VoxelinApi.VERSION));
        at.putValue("Voxelin-Api-Requires", requires);
        try (JarOutputStream j = new JarOutputStream(new FileOutputStream(f), mf)) { }
    }

    /** Публічні класи, поля, конструктори й методи voxelin.api - по рядку, відсортовано. */
    static List<String> signatures(File dir) throws Exception {
        List<String> names = new ArrayList<>();
        try (var s = Files.walk(dir.toPath())) { s.filter(p -> p.toString().endsWith(".class")).forEach(p -> names.add(dir.toPath().getParent().getParent().relativize(p).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", ""))); }
        TreeSet<String> out = new TreeSet<>();
        for (String name : names) {
            Class<?> c = Class.forName(name, false, VoxelinApiTest.class.getClassLoader());
            if (!Modifier.isPublic(c.getModifiers()) || c.isAnonymousClass() || c.isSynthetic()) continue;
            String cn = c.getName();
            out.add("C " + cn + " " + (c.isInterface() ? "interface" : c.isEnum() ? "enum" : c.isAnnotation() ? "annotation" : c.isRecord() ? "record" : "class")
                    + (Modifier.isFinal(c.getModifiers()) && !c.isEnum() && !c.isRecord() ? " final" : "") + (c.getSuperclass() != null && c.getSuperclass() != Object.class && !c.isEnum() && !c.isRecord() ? " extends " + c.getSuperclass().getName() : "")
                    + (c.getInterfaces().length > 0 ? " implements " + String.join(",", Arrays.stream(c.getInterfaces()).map(Class::getName).sorted().toList()) : ""));
            for (Field f : c.getDeclaredFields()) if (Modifier.isPublic(f.getModifiers()) && !f.isSynthetic()) out.add("F " + cn + "." + f.getName() + " : " + f.getGenericType().getTypeName() + (Modifier.isStatic(f.getModifiers()) ? " static" : "") + (Modifier.isFinal(f.getModifiers()) ? " final" : ""));
            for (Constructor<?> k : c.getDeclaredConstructors()) if (Modifier.isPublic(k.getModifiers()) && !k.isSynthetic()) out.add("K " + cn + "(" + params(k.getGenericParameterTypes()) + ")");
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic() || m.isBridge()) continue;
                out.add("M " + cn + "." + m.getName() + "(" + params(m.getGenericParameterTypes()) + ") : " + m.getGenericReturnType().getTypeName() + (Modifier.isStatic(m.getModifiers()) ? " static" : "") + (m.isDefault() ? " default" : ""));
            }
        }
        return new ArrayList<>(out);
    }

    static String params(Type[] t) { return String.join(", ", Arrays.stream(t).map(Type::getTypeName).toList()); }
}
