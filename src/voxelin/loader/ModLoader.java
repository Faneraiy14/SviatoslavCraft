package voxelin.loader;

import voxelin.api.*;
import voxelin.core.*;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * Завантажувач модів: mods/*.jar з рядками маніфесту Voxelin-Mod (клас), Voxelin-Mod-Id, Voxelin-Api і необов'язковим
 * Voxelin-Api-Requires (можливості через кому, VoxelinApi.FEATURES) - без них мод не вантажиться з поясненням «онови гру».
 * Завжди частина гри: нема модів у mods/ - нічого не робить.
 * Кожен мод - окремий ClassLoader. Мод з новішим API, без потрібних рядків або з винятком у init() позначається й пропускається:
 * гра стартує в будь-якому разі. Ванільний вміст проходить через той самий ModContext (load(mod, id)).
 */
public final class ModLoader {
    public enum Status { OK, FAILED, INCOMPATIBLE }

    public static final class ModInfo {
        public final String id, file; public final Status status; public final String message;
        ModInfo(String id, String file, Status status, String message) { this.id = id; this.file = file; this.status = status; this.message = message; }
        @Override public String toString() { return id + " [" + status + "]" + (message == null ? "" : " " + message); }
    }

    private final BlockRegistry blocks;
    private final ItemRegistry items;
    private final RecipeRegistry recipes;
    private final MobRegistry mobTypes;
    private final LootRegistry loot;
    private final StructureRegistry structures;
    private final RecordRegistry records;
    private final EventBus events;
    private final CommandRegistry commands;
    private final Map<String, int[][]> textures;
    private final List<ModInfo> mods = new ArrayList<>();
    /**
     * Що зареєстрували моди (крім вбудованого voxelin), по порядку: сервер надсилає це гостям (S_CONTENT), і гостю не треба
     * мати мод, щоб бачити його блоки, предмети, текстури й істоти та крафтити за його рецептами. Код мода лишається на сервері.
     */
    public record ContentOp(String mod, String kind, String name, Object data) {}
    public static final String VANILLA = "voxelin";
    private final List<ContentOp> content = new ArrayList<>();
    public List<ContentOp> content() { synchronized (content) { return List.copyOf(content); } }
    private void log(String mod, String kind, String name, Object data) { if (!VANILLA.equals(mod)) synchronized (content) { content.add(new ContentOp(mod, kind, name, data)); } }

    /** Сервіси API (ctx.api) - Game реєструє їх до завантаження модів. */
    public final ServiceRegistry services = new ServiceRegistry();

    public ModLoader(BlockRegistry blocks, ItemRegistry items, RecipeRegistry recipes, MobRegistry mobTypes, LootRegistry loot, StructureRegistry structures, RecordRegistry records, EventBus events, CommandRegistry commands, Map<String, int[][]> textures) {
        this.blocks = blocks; this.items = items; this.recipes = recipes; this.mobTypes = mobTypes; this.loot = loot; this.structures = structures; this.records = records; this.events = events; this.commands = commands; this.textures = textures;
    }

    public List<ModInfo> mods() { return Collections.unmodifiableList(mods); }

    /** Пакет головного класу мода -> id (запасний спосіб знайти мод у стеку, якщо ім'я завантажувача загубилось). */
    private final Map<String, String> packages = new HashMap<>();

    /** Чий це рядок стеку: id мода або null (гра, Java, бібліотеки). */
    public String modOf(StackTraceElement e) {
        String ln = e.getClassLoaderName();
        if (ln != null && ln.startsWith("mod:")) return ln.substring(4);
        String c = e.getClassName();
        if (c.startsWith("voxelin.") || c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("org.lwjgl.")) return null;
        synchronized (packages) { for (Map.Entry<String, String> p : packages.entrySet()) if (c.startsWith(p.getKey())) return p.getValue(); }
        return null;
    }

    /** Запустити init() мода з контекстом. Виняток не валить гру. */
    public ModInfo load(VoxelinMod mod, String id, String file) {
        if (!id.matches("[a-z0-9_]{1,32}")) return add(new ModInfo(id, file, Status.FAILED, "погане Voxelin-Mod-Id (треба малі латинські літери, цифри, _)"));
        for (ModInfo m : mods) if (m.id.equals(id) && m.status == Status.OK) return add(new ModInfo(id, file, Status.FAILED, "мод з таким id уже завантажено"));
        try {
            mod.init(context(id));
            return add(new ModInfo(id, file, Status.OK, null));
        } catch (Throwable t) {
            return add(new ModInfo(id, file, Status.FAILED, "помилка init(): " + t));
        }
    }

    /** Завантажити всі jar-и з теки (у алфавітному порядку - однаково на всіх ОС). */
    public void loadDir(File dir) {
        File[] jars = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) return;
        Arrays.sort(jars, Comparator.comparing(File::getName));
        for (File jar : jars) loadJar(jar);
    }

    public ModInfo loadJar(File jar) {
        String id = jar.getName(), cls;
        int api;
        String requires;
        try (JarFile jf = new JarFile(jar)) {
            Attributes a = jf.getManifest() == null ? null : jf.getManifest().getMainAttributes();
            if (a == null || a.getValue("Voxelin-Mod") == null || a.getValue("Voxelin-Mod-Id") == null)
                return add(new ModInfo(id, jar.getName(), Status.INCOMPATIBLE, "не мод Voxelin (нема Voxelin-Mod / Voxelin-Mod-Id у маніфесті)"));
            cls = a.getValue("Voxelin-Mod").trim();
            id = a.getValue("Voxelin-Mod-Id").trim();
            try { api = Integer.parseInt(String.valueOf(a.getValue("Voxelin-Api")).trim()); } catch (NumberFormatException e) { api = -1; }
            requires = a.getValue("Voxelin-Api-Requires");
        } catch (Exception e) {
            return add(new ModInfo(id, jar.getName(), Status.FAILED, "не вдалось прочитати jar: " + e));
        }
        if (api < VoxelinApi.MIN_COMPATIBLE)
            return add(new ModInfo(id, jar.getName(), Status.INCOMPATIBLE, "мод зібрано під застарілий API " + api + " (гра підтримує " + VoxelinApi.MIN_COMPATIBLE + ".." + VoxelinApi.VERSION + ", координати стали long) - онови мод"));
        if (api > VoxelinApi.VERSION)
            return add(new ModInfo(id, jar.getName(), Status.INCOMPATIBLE, "потрібен API " + api + ", гра надає " + VoxelinApi.VERSION + " - онови гру"));
        List<String> missing = missingFeatures(requires);
        if (!missing.isEmpty())
            return add(new ModInfo(id, jar.getName(), Status.INCOMPATIBLE, "цій версії гри бракує можливостей " + String.join(", ", missing) + " - онови гру"));
        try {
            // ім'я завантажувача видно в стеку помилки (StackTraceElement.getClassLoaderName) - звіт про падіння назве винний мод
            URLClassLoader cl = new URLClassLoader("mod:" + id, new URL[]{jar.toURI().toURL()}, ModLoader.class.getClassLoader());
            int dot = cls.lastIndexOf('.');
            if (dot > 0) synchronized (packages) { packages.putIfAbsent(cls.substring(0, dot + 1), id); }
            Object o = Class.forName(cls, true, cl).getDeclaredConstructor().newInstance();
            if (!(o instanceof VoxelinMod)) return add(new ModInfo(id, jar.getName(), Status.FAILED, cls + " не реалізує voxelin.api.VoxelinMod"));
            return load((VoxelinMod) o, id, jar.getName());
        } catch (Throwable t) {
            return add(new ModInfo(id, jar.getName(), Status.FAILED, "не вдалось створити " + cls + ": " + t));
        }
    }

    /** Можливості з рядка «a, b, c», яких гра не має. */
    static List<String> missingFeatures(String requires) {
        List<String> out = new ArrayList<>();
        if (requires == null) return out;
        for (String f : requires.split(",")) if (!f.isBlank() && !VoxelinApi.has(f)) out.add(f.trim());
        return out;
    }

    private ModInfo add(ModInfo m) {
        mods.add(m);
        System.out.println("[Voxelin] мод " + m);
        return m;
    }

    private ModContext context(String modId) {
        Registries reg = new Registries() {
            public BlockRef block(String name, BlockSettings s) {
                if (name.contains(":") && !name.startsWith(modId + ":")) throw new IllegalArgumentException("мод «" + modId + "» може реєструвати лише " + modId + ":... (а не " + name + ")");
                BlockSettings q = s.qualified(modId);
                String full = name.contains(":") ? name : modId + ":" + name;
                BlockType b = blocks.register(full, q);
                log(modId, "block", full, q);
                if (b.hasItem) items.register(full, new ItemSettings().placesBlock(full).color(b.color).texture(b.texSide != null ? b.texSide : b.texTop).title(q.titleText()));
                return b;
            }
            public ItemRef item(String name, ItemSettings s) {
                if (name.contains(":") && !name.startsWith(modId + ":")) throw new IllegalArgumentException("мод «" + modId + "» може реєструвати лише " + modId + ":... (а не " + name + ")");
                String full = name.contains(":") ? name : modId + ":" + name;
                ItemSettings q = s.qualified(modId);
                ItemRef r = items.register(full, q);
                log(modId, "item", full, q);
                return r;
            }
            public void recipe(String id, RecipeSettings s) {
                if (id.contains(":") && !id.startsWith(modId + ":")) throw new IllegalArgumentException("мод «" + modId + "» може реєструвати лише " + modId + ":... (а не " + id + ")");
                String full = id.contains(":") ? id : modId + ":" + id;
                RecipeSettings q = s.qualified(modId);
                recipes.add(full, q);
                log(modId, "recipe", full, q);
            }
            public MobRef mob(String name, MobSettings s) {
                if (name.contains(":") && !name.startsWith(modId + ":")) throw new IllegalArgumentException("мод «" + modId + "» може реєструвати лише " + modId + ":... (а не " + name + ")");
                String full = name.contains(":") ? name : modId + ":" + name;
                MobSettings q = s.qualified(modId);
                MobRef r = mobTypes.register(full, q);
                log(modId, "mob", full, q);
                return r;
            }
            public MobRef findMob(String full) { return mobTypes.get(full); }
            public void loot(String id, LootSettings s) {
                if (id.contains(":") && !id.startsWith(modId + ":")) throw new IllegalArgumentException("мод «" + modId + "» може реєструвати лише " + modId + ":... (а не " + id + ")");
                loot.add(id.contains(":") ? id : modId + ":" + id, s.qualified(modId));
            }
            public void structure(String name, String text) {
                if (name.contains(":") && !name.startsWith(modId + ":")) throw new IllegalArgumentException("мод «" + modId + "» може реєструвати лише " + modId + ":... (а не " + name + ")");
                structures.add(name.contains(":") ? name : modId + ":" + name, text);
            }
            public void record(String id, String title, String text) {
                if (id.contains(":") && !id.startsWith(modId + ":")) throw new IllegalArgumentException("мод «" + modId + "» може реєструвати лише " + modId + ":... (а не " + id + ")");
                records.add(id.contains(":") ? id : modId + ":" + id, title, text);
            }
            public void fuel(String item, float seconds) { String full = item.contains(":") ? item : modId + ":" + item; recipes.fuel(full, seconds); log(modId, "fuel", full, seconds); }
            public void tag(String tag, String... names) {
                String[] q = new String[names.length];
                for (int i = 0; i < names.length; i++) q[i] = names[i].contains(":") ? names[i] : modId + ":" + names[i];
                items.tag(tag, q);
                log(modId, "tag", tag, q.clone());
            }
            public ItemRef findItem(String full) { return items.get(full); }
            public BlockRef findBlock(String full) { return blocks.byName(full); }
            public void texture(String name, int[] px) {
                if (px == null || px.length != 256) throw new IllegalArgumentException("текстура має бути 16x16 = 256 пікселів");
                if (blocks.isFrozen()) throw new IllegalStateException("реєстрація закрита");
                String full = name.contains(":") ? name : modId + ":" + name;
                synchronized (textures) { textures.put(full, new int[][]{px.clone()}); }
                log(modId, "texture", full, px.clone());
            }
        };
        Events ev = events.forMod(modId);
        Commands cmd = commands.forMod(modId);
        return new ModContext() {
            public int apiVersion() { return VoxelinApi.VERSION; }
            public String modId() { return modId; }
            public Registries registries() { return reg; }
            public Events events() { return ev; }
            public Commands commands() { return cmd; }
            public void log(String m) { System.out.println("[" + modId + "] " + m); }
            public <T> T api(Class<T> service) { return service == null ? null : services.get(service, modId); }
        };
    }
}
