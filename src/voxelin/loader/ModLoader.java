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
 * Завантажувач модів: mods/*.jar з рядками маніфесту Voxelin-Mod (клас), Voxelin-Mod-Id, Voxelin-Api.
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
    private final EventBus events;
    private final CommandRegistry commands;
    private final Map<String, int[][]> textures;
    private final List<ModInfo> mods = new ArrayList<>();

    public ModLoader(BlockRegistry blocks, EventBus events, CommandRegistry commands, Map<String, int[][]> textures) {
        this.blocks = blocks; this.events = events; this.commands = commands; this.textures = textures;
    }

    public List<ModInfo> mods() { return Collections.unmodifiableList(mods); }

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
        try (JarFile jf = new JarFile(jar)) {
            Attributes a = jf.getManifest() == null ? null : jf.getManifest().getMainAttributes();
            if (a == null || a.getValue("Voxelin-Mod") == null || a.getValue("Voxelin-Mod-Id") == null)
                return add(new ModInfo(id, jar.getName(), Status.INCOMPATIBLE, "не мод Voxelin (нема Voxelin-Mod / Voxelin-Mod-Id у маніфесті)"));
            cls = a.getValue("Voxelin-Mod").trim();
            id = a.getValue("Voxelin-Mod-Id").trim();
            try { api = Integer.parseInt(String.valueOf(a.getValue("Voxelin-Api")).trim()); } catch (NumberFormatException e) { api = -1; }
        } catch (Exception e) {
            return add(new ModInfo(id, jar.getName(), Status.FAILED, "не вдалось прочитати jar: " + e));
        }
        if (api < 1 || api > VoxelinApi.VERSION)
            return add(new ModInfo(id, jar.getName(), Status.INCOMPATIBLE, "потрібен API " + api + ", гра надає " + VoxelinApi.VERSION + " - онови гру або мод"));
        try {
            URLClassLoader cl = new URLClassLoader(new URL[]{jar.toURI().toURL()}, ModLoader.class.getClassLoader());
            Object o = Class.forName(cls, true, cl).getDeclaredConstructor().newInstance();
            if (!(o instanceof VoxelinMod)) return add(new ModInfo(id, jar.getName(), Status.FAILED, cls + " не реалізує voxelin.api.VoxelinMod"));
            return load((VoxelinMod) o, id, jar.getName());
        } catch (Throwable t) {
            return add(new ModInfo(id, jar.getName(), Status.FAILED, "не вдалось створити " + cls + ": " + t));
        }
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
                return blocks.register(name.contains(":") ? name : modId + ":" + name, s);
            }
            public BlockRef findBlock(String full) { return blocks.byName(full); }
            public void texture(String name, int[] px) {
                if (px == null || px.length != 256) throw new IllegalArgumentException("текстура має бути 16x16 = 256 пікселів");
                if (blocks.isFrozen()) throw new IllegalStateException("реєстрація закрита");
                synchronized (textures) { textures.put(name.contains(":") ? name : modId + ":" + name, new int[][]{px.clone()}); }
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
        };
    }
}
