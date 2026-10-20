package voxelin.content;

import voxelin.api.*;
import voxelin.core.CommandRegistry;

/**
 * Ванільний вміст Voxelin - звичайний «мод» з id "voxelin", що реєструється через той самий API, що й сторонні моди.
 * Етап «Новий світ» додасть текстури, руди, біоми й решту блоків.
 */
public final class VanillaContent implements VoxelinMod {
    /** Блоки, які гравець отримує в інвентар у новому світі (поки немає видобутку й крафту): 27 стопок по 64. */
    public static final String[] STARTER = {
            "voxelin:grass", "voxelin:dirt", "voxelin:stone", "voxelin:cobblestone", "voxelin:stone_bricks", "voxelin:oak_planks", "voxelin:oak_log", "voxelin:glass", "voxelin:lamp",
            "voxelin:sand", "voxelin:sandstone", "voxelin:gravel", "voxelin:clay", "voxelin:snow", "voxelin:ice", "voxelin:birch_planks", "voxelin:spruce_planks",
            "voxelin:oak_leaves", "voxelin:birch_log", "voxelin:spruce_log", "voxelin:cactus", "voxelin:deep_stone", "voxelin:mud", "voxelin:pale_grass", "voxelin:sintered_sand",
            "voxelin:dune_glass", "voxelin:monolith"};

    public static final int MAX_FILL = 32768 * 8;

    private Registries r;

    /** Зареєструвати текстуру й повернути її середній колір (для іконок). */
    private int tex(String name, int[] px) { r.texture(name, px); return TextureGen.average(px); }

    private void block(String name, BlockSettings s) { r.block(name, s); }

    @Override public void init(ModContext ctx) {
        r = ctx.registries();
        // --- текстури (малюються кодом; власні PNG у textures/voxelin/<назва>.png перекривають)
        int cStone = tex("stone", TextureGen.stone()), cDeep = tex("deep_stone", TextureGen.deepStone()), cCobble = tex("cobblestone", TextureGen.cobblestone()),
                cBricks = tex("stone_bricks", TextureGen.stoneBricks()), cDirt = tex("dirt", TextureGen.dirt()), cMud = tex("mud", TextureGen.mud()),
                cSand = tex("sand", TextureGen.sand()), cSandstone = tex("sandstone", TextureGen.sandstone()), cSinter = tex("sintered_sand", TextureGen.sinteredSand()),
                cGravel = tex("gravel", TextureGen.gravel()), cClay = tex("clay", TextureGen.clay()), cSnow = tex("snow", TextureGen.snow()), cBed = tex("bedrock", TextureGen.bedrock()),
                cGrassTop = tex("grass_top", TextureGen.grassTop()), cPaleTop = tex("pale_grass_top", TextureGen.paleGrassTop()),
                cWater = tex("water", TextureGen.water()), cIce = tex("ice", TextureGen.ice()), cGlass = tex("glass", TextureGen.glass()), cDune = tex("dune_glass", TextureGen.duneGlass()),
                cOakLog = tex("oak_log", TextureGen.oakLog()), cOakPlanks = tex("oak_planks", TextureGen.oakPlanks()), cOakLeaves = tex("oak_leaves", TextureGen.oakLeaves()),
                cBirchLog = tex("birch_log", TextureGen.birchLog()), cBirchPlanks = tex("birch_planks", TextureGen.birchPlanks()), cBirchLeaves = tex("birch_leaves", TextureGen.birchLeaves()),
                cSpruceLog = tex("spruce_log", TextureGen.spruceLog()), cSprucePlanks = tex("spruce_planks", TextureGen.sprucePlanks()), cSpruceLeaves = tex("spruce_leaves", TextureGen.spruceLeaves()),
                cPaleLog = tex("pale_log", TextureGen.paleLog()), cCactus = tex("cactus_side", TextureGen.cactusSide()),
                cCoal = tex("coal_ore", TextureGen.coalOre()), cIron = tex("iron_ore", TextureGen.ironOre()), cCopper = tex("copper_ore", TextureGen.copperOre()),
                cGold = tex("gold_ore", TextureGen.goldOre()), cDiamond = tex("diamond_ore", TextureGen.diamondOre()), cLim = tex("liminite_ore", TextureGen.liminiteOre()),
                cMono = tex("monolith", TextureGen.monolith()), cLamp = tex("lamp", TextureGen.lamp());
        tex("grass_side", TextureGen.grassSide()); tex("pale_grass_side", TextureGen.paleGrassSide());
        tex("oak_log_top", TextureGen.oakLogTop()); tex("birch_log_top", TextureGen.birchLogTop()); tex("spruce_log_top", TextureGen.spruceLogTop()); tex("pale_log_top", TextureGen.paleLogTop());
        tex("cactus_top", TextureGen.cactusTop());

        // --- блоки
        block("stone", new BlockSettings().texture("stone").color(cStone).hardness(1.5f));
        block("deep_stone", new BlockSettings().texture("deep_stone").color(cDeep).hardness(3f));
        block("cobblestone", new BlockSettings().texture("cobblestone").color(cCobble).hardness(2f));
        block("stone_bricks", new BlockSettings().texture("stone_bricks").color(cBricks).hardness(2f));
        block("bedrock", new BlockSettings().texture("bedrock").color(cBed).hardness(-1f));
        block("dirt", new BlockSettings().texture("dirt").color(cDirt).hardness(0.5f));
        block("grass", new BlockSettings().textures("grass_top", "grass_side", "dirt").color(cGrassTop).hardness(0.6f));
        block("pale_grass", new BlockSettings().textures("pale_grass_top", "pale_grass_side", "dirt").color(cPaleTop).hardness(0.6f));
        block("mud", new BlockSettings().texture("mud").color(cMud).hardness(0.5f));
        block("sand", new BlockSettings().texture("sand").color(cSand).hardness(0.5f));
        block("sandstone", new BlockSettings().texture("sandstone").color(cSandstone).hardness(0.8f));
        block("sintered_sand", new BlockSettings().texture("sintered_sand").color(cSinter).hardness(1f));
        block("gravel", new BlockSettings().texture("gravel").color(cGravel).hardness(0.6f));
        block("clay", new BlockSettings().texture("clay").color(cClay).hardness(0.6f));
        block("snow", new BlockSettings().texture("snow").color(cSnow).hardness(0.2f));
        block("ice", new BlockSettings().texture("ice").color(cIce).translucent(true).hardness(0.5f));
        block("water", new BlockSettings().texture("water").color(cWater).translucent(true).liquid(true).hardness(-1f));
        block("glass", new BlockSettings().texture("glass").color(cGlass).translucent(true).hardness(0.3f));
        block("dune_glass", new BlockSettings().texture("dune_glass").color(cDune).translucent(true).hardness(0.4f));
        block("oak_log", new BlockSettings().textures("oak_log_top", "oak_log", "oak_log_top").color(cOakLog).hardness(2f));
        block("oak_planks", new BlockSettings().texture("oak_planks").color(cOakPlanks).hardness(2f));
        block("oak_leaves", new BlockSettings().texture("oak_leaves").color(cOakLeaves).opaque(false).hardness(0.2f));
        block("birch_log", new BlockSettings().textures("birch_log_top", "birch_log", "birch_log_top").color(cBirchLog).hardness(2f));
        block("birch_planks", new BlockSettings().texture("birch_planks").color(cBirchPlanks).hardness(2f));
        block("birch_leaves", new BlockSettings().texture("birch_leaves").color(cBirchLeaves).opaque(false).hardness(0.2f));
        block("spruce_log", new BlockSettings().textures("spruce_log_top", "spruce_log", "spruce_log_top").color(cSpruceLog).hardness(2f));
        block("spruce_planks", new BlockSettings().texture("spruce_planks").color(cSprucePlanks).hardness(2f));
        block("spruce_leaves", new BlockSettings().texture("spruce_leaves").color(cSpruceLeaves).opaque(false).hardness(0.2f));
        block("pale_log", new BlockSettings().textures("pale_log_top", "pale_log", "pale_log_top").color(cPaleLog).hardness(2f));
        block("cactus", new BlockSettings().textures("cactus_top", "cactus_side", "cactus_top").color(cCactus).hardness(0.4f));
        block("coal_ore", new BlockSettings().texture("coal_ore").color(cCoal).hardness(3f));
        block("iron_ore", new BlockSettings().texture("iron_ore").color(cIron).hardness(3f));
        block("copper_ore", new BlockSettings().texture("copper_ore").color(cCopper).hardness(3f));
        block("gold_ore", new BlockSettings().texture("gold_ore").color(cGold).hardness(3f));
        block("diamond_ore", new BlockSettings().texture("diamond_ore").color(cDiamond).hardness(4f));
        block("liminite_ore", new BlockSettings().texture("liminite_ore").color(cLim).light(4).hardness(4f));
        block("monolith", new BlockSettings().texture("monolith").color(cMono).light(3).hardness(6f));
        block("lamp", new BlockSettings().texture("lamp").color(cLamp).light(15).hardness(0.5f));

        Commands c = ctx.commands();
        c.register("setblock", "<x> <y> <z> <блок>", 2, k -> {
            if (k.args().size() != 4) throw new Commands.CommandException("Потрібно 4 аргументи");
            long[] o = k.origin();
            BlockRef b = block(r, k.args().get(3));
            long x = CommandRegistry.coord(k.args().get(0), o[0]), y = CommandRegistry.coord(k.args().get(1), o[1]), z = CommandRegistry.coord(k.args().get(2), o[2]);
            if (y < 0 || y >= k.world().height()) throw new Commands.CommandException("Висота має бути від 0 до " + (k.world().height() - 1));
            k.reply(k.world().setBlock(k.source(), x, (int) y, z, b) ? "Поставлено " + b.name() + " у " + x + " " + y + " " + z : "Не вдалось (чанк не завантажено або ліміт)");
        });
        c.register("fill", "<x1> <y1> <z1> <x2> <y2> <z2> <блок>", 2, k -> {
            if (k.args().size() != 7) throw new Commands.CommandException("Потрібно 7 аргументів");
            long[] o = k.origin(), p = new long[6];
            for (int i = 0; i < 6; i++) p[i] = CommandRegistry.coord(k.args().get(i), o[i % 3]);
            BlockRef b = block(r, k.args().get(6));
            long x0 = Math.min(p[0], p[3]), x1 = Math.max(p[0], p[3]), z0 = Math.min(p[2], p[5]), z1 = Math.max(p[2], p[5]);
            int y0 = (int) Math.max(0, Math.min(Math.min(p[1], p[4]), k.world().height())), y1 = (int) Math.min(k.world().height() - 1, Math.max(Math.max(p[1], p[4]), -1));
            // різниця точно в long (double на 10^18 втрачає точність і давав би хибно малий обсяг), у double лише для порівняння з лімітом
            double spanX, spanZ;
            try { spanX = (double) Math.subtractExact(x1, x0) + 1; spanZ = (double) Math.subtractExact(z1, z0) + 1; }
            catch (ArithmeticException e) { throw new Commands.CommandException("Забагато блоків (проміжок більший за діапазон long)"); }
            double vol = spanX * Math.max(0, y1 - y0 + 1) * spanZ;
            if (vol > MAX_FILL) throw new Commands.CommandException("Забагато блоків: " + String.format(java.util.Locale.ROOT, "%.0f", vol) + " (максимум " + MAX_FILL + ")");
            long n = 0;
            for (long x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (long z = z0; z <= z1; z++) if (k.world().setBlock(k.source(), x, y, z, b)) n++;
            k.reply("Заповнено " + n + " з " + (long) vol + " блоків" + (n < (long) vol ? " (решта - чанки не завантажені або ліміт)" : ""));
        });
        c.register("help", "", 0, k -> k.reply("Команди: /setblock, /fill, /help. Назви блоків: voxelin:stone, voxelin:grass, ..."));
    }

    /** Назва блока з аргументу команди: "stone" = "voxelin:stone". */
    static BlockRef block(Registries r, String arg) throws Commands.CommandException {
        BlockRef b = r.findBlock(arg.contains(":") ? arg : "voxelin:" + arg);
        if (b == null) throw new Commands.CommandException("Невідомий блок: " + arg);
        return b;
    }
}
