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
    public final ItemRegistry items = new ItemRegistry();
    public final RecipeRegistry recipes = new RecipeRegistry();
    public final MobRegistry mobTypes = new MobRegistry();
    public final LootRegistry loot = new LootRegistry();
    public final StructureRegistry structures = new StructureRegistry();
    public final RecordRegistry records = new RecordRegistry();
    public final EventBus events = new EventBus();
    public final CommandRegistry commands = new CommandRegistry();
    /** Текстури з реєстру (назва -> {пікселі 16x16}); етап «Новий світ» збирає з них атлас. */
    public final Map<String, int[][]> textures = new LinkedHashMap<>();
    public final ModLoader mods = new ModLoader(blocks, items, recipes, mobTypes, loot, structures, records, events, commands, textures);
    public final File savesDir;

    public Game(File savesDir, File modsDir) {
        this.savesDir = savesDir;
        ModLoader.ModInfo v = mods.load(new VanillaContent(), "voxelin", "(вбудований)");
        if (v.status != ModLoader.Status.OK) throw new IllegalStateException("ванільний вміст не завантажився: " + v.message);
        if (modsDir != null && modsDir.isDirectory()) mods.loadDir(modsDir);
        blocks.freeze();
        items.freeze();
        recipes.freeze(items);
        mobTypes.freeze();
        loot.freeze(items);
        loadStructureFiles(modsDir);
        structures.freeze(blocks);
        records.freeze();
    }

    /** Власні шаблони: структури/*.vxs поруч із текою модів (файл перекриває ванільний шаблон з тією ж назвою). */
    private void loadStructureFiles(File modsDir) {
        if (modsDir == null) return;
        File dir = new File(modsDir.getAbsoluteFile().getParentFile(), "structures");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".vxs"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            String base = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
            if (!base.matches("[a-z0-9_]+")) { System.out.println("[Voxelin] ⚠️ структура " + f.getName() + ": погана назва файла"); continue; }
            try { structures.add("voxelin:" + base, new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8)); }
            catch (Exception e) { System.out.println("[Voxelin] ⚠️ не вдалось прочитати " + f + ": " + e); }
        }
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
        return openAt(d, WorldInfo.load(d));
    }

    /** Відкрити вимір світу (поки що «end»): окрема тека <світ>/dimensions/<вимір>/ із власним WorldInfo; створюється при першому відкритті. */
    public World openDimension(String worldName, String dimension) throws IOException {
        if (!dimension.equals("end")) throw new IllegalArgumentException("невідомий вимір: " + dimension);
        File base = worldDir(worldName), d = new File(base, "dimensions/" + dimension);
        WorldInfo info;
        if (WorldInfo.exists(d)) info = WorldInfo.load(d);
        else {
            WorldInfo over = WorldInfo.load(base);
            info = WorldInfo.create(d, worldName, over.seed, WorldInfo.Type.END);
        }
        return openAt(d, info);
    }

    private World openAt(File d, WorldInfo info) throws IOException {
        voxelin.core.gen.Generator gen = info.type == WorldInfo.Type.END
                ? new voxelin.core.gen.EndGenerator(info.seed, blocks)
                : new TerrainGenerator(info.seed, info.type, blocks, structures.templates());
        return new World(d, info, blocks, items, recipes, mobTypes, loot, records, events, gen);
    }
}
