package voxelin.loader;

import voxelin.content.VanillaContent;
import voxelin.core.*;
import voxelin.core.gen.TerrainGenerator;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Запуск гри без вікна: ванільний вміст -> моди з mods/ -> закриття реєстрів. Далі відкриття/створення світів у теці saves.
 * Клієнт (вікно) і тести користуються тим самим класом.
 */
public final class Game {
    public final BlockRegistry blocks = new BlockRegistry();
    public final EventBus events = new EventBus();
    public final CommandRegistry commands = new CommandRegistry();
    /** Текстури з реєстру (назва -> {пікселі 16x16}); етап «Новий світ» збирає з них атлас. */
    public final Map<String, int[][]> textures = new LinkedHashMap<>();
    public final ModLoader mods = new ModLoader(blocks, events, commands, textures);
    public final File savesDir;

    public Game(File savesDir, File modsDir) {
        this.savesDir = savesDir;
        ModLoader.ModInfo v = mods.load(new VanillaContent(), "voxelin", "(вбудований)");
        if (v.status != ModLoader.Status.OK) throw new IllegalStateException("ванільний вміст не завантажився: " + v.message);
        if (modsDir != null && modsDir.isDirectory()) mods.loadDir(modsDir);
        blocks.freeze();
    }

    public File worldDir(String name) {
        if (!WorldInfo.isValidName(name)) throw new IllegalArgumentException("недопустима назва світу: " + name);
        return new File(savesDir, name);
    }

    /** Світи нового формату (тека з voxelin.world). Старі світи v0.0.3 сюди не потрапляють - їхні файли лишаються на диску. */
    public List<String> listWorlds() {
        List<String> r = new ArrayList<>();
        File[] ds = savesDir.listFiles(File::isDirectory);
        if (ds != null) for (File d : ds) if (WorldInfo.isValidName(d.getName()) && WorldInfo.exists(d)) r.add(d.getName());
        r.sort(String.CASE_INSENSITIVE_ORDER);
        return r;
    }

    public void createWorld(String name, long seed, WorldInfo.Type type) throws IOException {
        File d = worldDir(name);
        if (d.exists()) throw new IOException("тека світу вже існує: " + d);
        WorldInfo.create(d, name, seed, type);
    }

    public World openWorld(String name) throws IOException {
        File d = worldDir(name);
        WorldInfo info = WorldInfo.load(d);
        return new World(d, info, blocks, events, new TerrainGenerator(info.seed, info.type, blocks));
    }
}
