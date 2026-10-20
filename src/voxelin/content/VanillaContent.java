package voxelin.content;

import voxelin.api.*;
import voxelin.core.CommandRegistry;
import voxelin.core.DayCycle;
import voxelin.core.entity.Vitals;

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

    private static Vitality vit(Commands.Context k) throws Commands.CommandException {
        Vitality v = k.world().vitality(k.source());
        if (v == null) throw new Commands.CommandException("У цього джерела нема здоров'я");
        return v;
    }

    private static double amount(Commands.Context k) throws Commands.CommandException {
        if (k.args().size() != 1) throw new Commands.CommandException("Потрібне одне число");
        try {
            double d = Double.parseDouble(k.args().get(0));
            if (Double.isNaN(d) || Double.isInfinite(d) || d <= 0 || d > 1000) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) { throw new Commands.CommandException("Потрібне число від 0 до 1000: " + k.args().get(0)); }
    }

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
        tex("chest_top", ItemTextureGen.chestTop()); tex("chest_side", ItemTextureGen.chestSide());
        tex("furnace_top", ItemTextureGen.furnaceTop()); tex("furnace_side", ItemTextureGen.furnaceSide());
        tex("workbench_top", ItemTextureGen.workbenchTop()); tex("workbench_side", ItemTextureGen.workbenchSide());

        // --- блоки
        block("stone", new BlockSettings().texture("stone").color(cStone).tool(ToolKind.PICKAXE).minTier(1).drops("cobblestone").title("Камінь").hardness(1.5f));
        block("deep_stone", new BlockSettings().texture("deep_stone").color(cDeep).tool(ToolKind.PICKAXE).minTier(1).drops("cobblestone").title("Глибинний камінь").hardness(3f));
        block("cobblestone", new BlockSettings().texture("cobblestone").color(cCobble).tool(ToolKind.PICKAXE).minTier(1).title("Бруківка").hardness(2f));
        block("stone_bricks", new BlockSettings().texture("stone_bricks").color(cBricks).tool(ToolKind.PICKAXE).minTier(1).title("Кам'яна цегла").hardness(2f));
        block("bedrock", new BlockSettings().texture("bedrock").color(cBed).noItem().title("Корінна порода").hardness(-1f));
        block("dirt", new BlockSettings().texture("dirt").color(cDirt).tool(ToolKind.SHOVEL).title("Земля").hardness(0.5f));
        block("grass", new BlockSettings().textures("grass_top", "grass_side", "dirt").color(cGrassTop).tool(ToolKind.SHOVEL).drops("dirt").title("Дерен").hardness(0.6f));
        block("pale_grass", new BlockSettings().textures("pale_grass_top", "pale_grass_side", "dirt").color(cPaleTop).tool(ToolKind.SHOVEL).drops("dirt").title("Бліда трава").hardness(0.6f));
        block("mud", new BlockSettings().texture("mud").color(cMud).tool(ToolKind.SHOVEL).title("Мул").hardness(0.5f));
        block("sand", new BlockSettings().texture("sand").color(cSand).tool(ToolKind.SHOVEL).title("Пісок").hardness(0.5f));
        block("sandstone", new BlockSettings().texture("sandstone").color(cSandstone).tool(ToolKind.PICKAXE).minTier(1).title("Пісковик").hardness(0.8f));
        block("sintered_sand", new BlockSettings().texture("sintered_sand").color(cSinter).tool(ToolKind.PICKAXE).minTier(1).title("Спечений пісок").hardness(1f));
        block("gravel", new BlockSettings().texture("gravel").color(cGravel).tool(ToolKind.SHOVEL).title("Гравій").hardness(0.6f));
        block("clay", new BlockSettings().texture("clay").color(cClay).tool(ToolKind.SHOVEL).title("Глина").hardness(0.6f));
        block("snow", new BlockSettings().texture("snow").color(cSnow).tool(ToolKind.SHOVEL).title("Сніг").hardness(0.2f));
        block("ice", new BlockSettings().texture("ice").color(cIce).translucent(true).absorb(1).noDrops().title("Лід").hardness(0.5f));
        block("water", new BlockSettings().texture("water").color(cWater).translucent(true).liquid(true).noItem().noDrops().title("Вода").hardness(-1f));
        block("glass", new BlockSettings().texture("glass").color(cGlass).translucent(true).noDrops().title("Скло").hardness(0.3f));
        block("dune_glass", new BlockSettings().texture("dune_glass").color(cDune).translucent(true).noDrops().title("Скло дюн").hardness(0.4f));
        block("oak_log", new BlockSettings().textures("oak_log_top", "oak_log", "oak_log_top").color(cOakLog).tool(ToolKind.AXE).title("Дубова колода").hardness(2f));
        block("oak_planks", new BlockSettings().texture("oak_planks").color(cOakPlanks).tool(ToolKind.AXE).title("Дубові дошки").hardness(2f));
        block("oak_leaves", new BlockSettings().texture("oak_leaves").color(cOakLeaves).opaque(false).drops("apple", 1, 1, 0.05f).drops("stick", 1, 2, 0.1f).title("Дубове листя").hardness(0.2f));
        block("birch_log", new BlockSettings().textures("birch_log_top", "birch_log", "birch_log_top").color(cBirchLog).tool(ToolKind.AXE).title("Березова колода").hardness(2f));
        block("birch_planks", new BlockSettings().texture("birch_planks").color(cBirchPlanks).tool(ToolKind.AXE).title("Березові дошки").hardness(2f));
        block("birch_leaves", new BlockSettings().texture("birch_leaves").color(cBirchLeaves).opaque(false).drops("stick", 1, 2, 0.1f).title("Березове листя").hardness(0.2f));
        block("spruce_log", new BlockSettings().textures("spruce_log_top", "spruce_log", "spruce_log_top").color(cSpruceLog).tool(ToolKind.AXE).title("Ялинова колода").hardness(2f));
        block("spruce_planks", new BlockSettings().texture("spruce_planks").color(cSprucePlanks).tool(ToolKind.AXE).title("Ялинові дошки").hardness(2f));
        block("spruce_leaves", new BlockSettings().texture("spruce_leaves").color(cSpruceLeaves).opaque(false).drops("stick", 1, 2, 0.1f).title("Ялинове листя").hardness(0.2f));
        block("pale_log", new BlockSettings().textures("pale_log_top", "pale_log", "pale_log_top").color(cPaleLog).tool(ToolKind.AXE).title("Бліда колода").hardness(2f));
        block("cactus", new BlockSettings().textures("cactus_top", "cactus_side", "cactus_top").color(cCactus).tool(ToolKind.AXE).title("Кактус").hardness(0.4f));
        block("coal_ore", new BlockSettings().texture("coal_ore").color(cCoal).tool(ToolKind.PICKAXE).minTier(1).drops("coal").title("Вугільна руда").hardness(3f));
        block("iron_ore", new BlockSettings().texture("iron_ore").color(cIron).tool(ToolKind.PICKAXE).minTier(2).drops("raw_iron").title("Залізна руда").hardness(3f));
        block("copper_ore", new BlockSettings().texture("copper_ore").color(cCopper).tool(ToolKind.PICKAXE).minTier(2).drops("raw_copper", 2, 3, 1f).title("Мідна руда").hardness(3f));
        block("gold_ore", new BlockSettings().texture("gold_ore").color(cGold).tool(ToolKind.PICKAXE).minTier(3).drops("raw_gold").title("Золота руда").hardness(3f));
        block("diamond_ore", new BlockSettings().texture("diamond_ore").color(cDiamond).tool(ToolKind.PICKAXE).minTier(3).drops("diamond").title("Алмазна руда").hardness(4f));
        block("liminite_ore", new BlockSettings().texture("liminite_ore").color(cLim).light(4).tool(ToolKind.PICKAXE).minTier(4).drops("liminite_shard", 1, 2, 1f).title("Лімінітова руда").hardness(4f));
        block("monolith", new BlockSettings().texture("monolith").color(cMono).light(3).tool(ToolKind.PICKAXE).minTier(2).title("Моноліт").hardness(6f));
        block("lamp", new BlockSettings().texture("lamp").color(cLamp).light(15).tool(ToolKind.PICKAXE).title("Лампа").hardness(0.5f));
        block("workbench", new BlockSettings().textures("workbench_top", "workbench_side", "oak_planks").color(0xB08850).tool(ToolKind.AXE).interact("workbench").title("Верстак").hardness(2f));
        block("furnace", new BlockSettings().textures("furnace_top", "furnace_side", "furnace_top").color(0x777777).tool(ToolKind.PICKAXE).minTier(1).interact("furnace").title("Піч").hardness(3.5f));
        block("chest", new BlockSettings().textures("chest_top", "chest_side", "chest_top").color(0xA0763C).tool(ToolKind.AXE).interact("chest").title("Скриня").hardness(2.5f));
        // --- предмети (не блоки): матеріали, їжа, інструменти
        tex("stick", ItemTextureGen.stick()); tex("coal", ItemTextureGen.coal()); tex("raw_iron", ItemTextureGen.rawOre(0xE0B49A)); tex("raw_copper", ItemTextureGen.rawOre(0xE0803F));
        tex("raw_gold", ItemTextureGen.rawOre(0xF5D13A)); tex("diamond", ItemTextureGen.diamond()); tex("liminite_shard", ItemTextureGen.shard()); tex("apple", ItemTextureGen.apple());
        r.item("stick", new ItemSettings().color(0x8B6B3A).texture("stick").title("Палиця"));
        r.item("coal", new ItemSettings().color(0x2B2B2B).texture("coal").title("Вугілля"));
        r.item("raw_iron", new ItemSettings().color(0xC9A88F).texture("raw_iron").title("Сире залізо"));
        r.item("raw_copper", new ItemSettings().color(0xC8764A).texture("raw_copper").title("Сира мідь"));
        r.item("raw_gold", new ItemSettings().color(0xE6C34A).texture("raw_gold").title("Сире золото"));
        r.item("diamond", new ItemSettings().color(0x5DE6E0).texture("diamond").title("Алмаз"));
        r.item("liminite_shard", new ItemSettings().color(0x8F6BFF).texture("liminite_shard").title("Уламок лімініту"));
        r.item("apple", new ItemSettings().color(0xD62C2C).food(4).texture("apple").title("Яблуко"));
        String[] mat = {"wooden", "stone", "iron", "diamond"}, matTitle = {"Дерев'яна", "Кам'яна", "Залізна", "Алмазна"};
        int[] tier = {1, 2, 3, 4}, dur = {60, 132, 251, 1562}, col = {0xA9824B, 0x8A8A8A, 0xD8D8D8, 0x5DE6E0};
        float[] spd = {2f, 4f, 6f, 8f}, pickAtk = {2f, 3f, 4f, 5f}, axeAtk = {3f, 4f, 5f, 6f}, shovelAtk = {1.5f, 2.5f, 3.5f, 4.5f}, swordAtk = {4f, 5f, 6f, 7f};
        String[] matTitleM = {"Дерев'яний", "Кам'яний", "Залізний", "Алмазний"};
        for (int i = 0; i < 4; i++) {
            tex(mat[i] + "_pickaxe", ItemTextureGen.pickaxe(i)); tex(mat[i] + "_axe", ItemTextureGen.axe(i)); tex(mat[i] + "_shovel", ItemTextureGen.shovel(i));
            r.item(mat[i] + "_pickaxe", new ItemSettings().tool(ToolKind.PICKAXE, tier[i], spd[i]).durability(dur[i]).attack(pickAtk[i]).color(col[i]).texture(mat[i] + "_pickaxe").title(matTitle[i] + " кирка"));
            r.item(mat[i] + "_axe", new ItemSettings().tool(ToolKind.AXE, tier[i], spd[i]).durability(dur[i]).attack(axeAtk[i]).color(col[i]).texture(mat[i] + "_axe").title(matTitle[i] + " сокира"));
            tex(mat[i] + "_sword", ItemTextureGen.sword(i));
            r.item(mat[i] + "_sword", new ItemSettings().durability(dur[i]).attack(swordAtk[i]).color(col[i]).texture(mat[i] + "_sword").title(matTitleM[i] + " меч"));
            r.item(mat[i] + "_shovel", new ItemSettings().tool(ToolKind.SHOVEL, tier[i], spd[i]).durability(dur[i]).attack(shovelAtk[i]).color(col[i]).texture(mat[i] + "_shovel").title(matTitle[i] + " лопата"));
        }

        tex("iron_ingot", ItemTextureGen.ingot(0xD8D8D8)); tex("copper_ingot", ItemTextureGen.ingot(0xC8764A)); tex("gold_ingot", ItemTextureGen.ingot(0xF5D13A));
        r.item("iron_ingot", new ItemSettings().color(0xD8D8D8).texture("iron_ingot").title("Залізний злиток"));
        r.item("copper_ingot", new ItemSettings().color(0xC8764A).texture("copper_ingot").title("Мідний злиток"));
        r.item("gold_ingot", new ItemSettings().color(0xF5D13A).texture("gold_ingot").title("Золотий злиток"));

        // --- мітки й рецепти крафту (перша версія - чернетка, правимо разом зі Святославом; усе - дані)
        r.tag("planks", "oak_planks", "birch_planks", "spruce_planks");
        r.tag("logs", "oak_log", "birch_log", "spruce_log", "pale_log");
        r.recipe("oak_planks", RecipeSettings.shapeless("oak_log").result("oak_planks", 3));
        r.recipe("birch_planks", RecipeSettings.shapeless("birch_log").result("birch_planks", 3));
        r.recipe("spruce_planks", RecipeSettings.shapeless("spruce_log").result("spruce_planks", 3));
        r.recipe("sticks", RecipeSettings.shapeless("#planks").result("stick", 2));
        r.recipe("workbench", RecipeSettings.shaped("PP", "PP").key('P', "#planks").result("workbench", 1));
        r.recipe("stone_bricks", RecipeSettings.shaped("SS", "SS").key('S', "stone").result("stone_bricks", 4));
        r.recipe("lamp", RecipeSettings.shaped(".G.", "GCG", ".G.").key('G', "glass").key('C', "coal").result("lamp", 2));
        r.recipe("chest", RecipeSettings.shaped("PPP", "P.P", "PPP").key('P', "#planks").result("chest", 1));
        r.loot("ruins", new LootSettings().rolls(1, 3).entry("stick", 2, 6, 10).entry("coal", 1, 4, 8).entry("apple", 1, 2, 6).entry("cobblestone", 3, 8, 8)
                .entry("raw_iron", 1, 3, 5).entry("leather", 1, 2, 4).entry("string", 1, 3, 4).entry("iron_ingot", 1, 2, 2));
        r.loot("dungeon", new LootSettings().rolls(2, 4).entry("raw_iron", 2, 5, 8).entry("raw_gold", 1, 3, 5).entry("iron_ingot", 1, 3, 5).entry("steak", 2, 4, 6)
                .entry("leather", 1, 3, 5).entry("string", 2, 4, 5).entry("coal", 3, 6, 6).entry("iron_sword", 1, 1, 2).entry("diamond", 1, 1, 1));
        r.loot("temple", new LootSettings().rolls(2, 4).entry("gold_ingot", 1, 3, 8).entry("iron_ingot", 1, 3, 8).entry("diamond", 1, 2, 3).entry("leather", 1, 3, 5)
                .entry("string", 2, 4, 5).entry("raw_gold", 2, 4, 6).entry("steak", 1, 3, 5).entry("iron_pickaxe", 1, 1, 1));
        r.loot("fortress", new LootSettings().rolls(3, 5).entry("iron_ingot", 2, 5, 9).entry("gold_ingot", 1, 3, 6).entry("diamond", 1, 3, 4).entry("iron_sword", 1, 1, 3)
                .entry("steak", 2, 5, 7).entry("coal", 4, 10, 7).entry("raw_iron", 3, 6, 6).entry("diamond_pickaxe", 1, 1, 1).entry("cooked_chicken", 2, 4, 5));
        r.loot("village", new LootSettings().rolls(1, 3).entry("apple", 1, 3, 8).entry("cooked_chicken", 1, 2, 6).entry("steak", 1, 2, 5).entry("coal", 2, 5, 6)
                .entry("stick", 3, 8, 6).entry("iron_ingot", 1, 2, 3).entry("gold_ingot", 1, 1, 2).entry("leather", 1, 2, 4));
        r.loot("mine", new LootSettings().rolls(1, 3).entry("coal", 4, 9, 10).entry("raw_iron", 2, 4, 6).entry("raw_copper", 2, 5, 6).entry("stick", 3, 8, 8)
                .entry("stone_pickaxe", 1, 1, 2).entry("apple", 1, 2, 4).entry("cooked_chicken", 1, 2, 3));
        r.recipe("furnace", RecipeSettings.shaped("C.C", "CKC", "CCC").key('C', "cobblestone").key('K', "coal").result("furnace", 1));
        r.recipe("smelt_iron", RecipeSettings.smelting("raw_iron", 10).result("iron_ingot", 1));
        r.recipe("smelt_copper", RecipeSettings.smelting("raw_copper", 10).result("copper_ingot", 1));
        r.recipe("smelt_gold", RecipeSettings.smelting("raw_gold", 10).result("gold_ingot", 1));
        r.recipe("smelt_stone", RecipeSettings.smelting("cobblestone", 10).result("stone", 1));
        r.recipe("smelt_glass", RecipeSettings.smelting("sand", 10).result("glass", 1));
        r.recipe("smelt_charcoal", RecipeSettings.smelting("#logs", 15).result("coal", 1));
        r.fuel("coal", 80); r.fuel("oak_log", 15); r.fuel("birch_log", 15); r.fuel("spruce_log", 15); r.fuel("pale_log", 15);
        r.fuel("oak_planks", 8); r.fuel("birch_planks", 8); r.fuel("spruce_planks", 8); r.fuel("stick", 4);
        String[] headKey = {"#planks", "cobblestone", "iron_ingot", "diamond"};
        for (int i = 0; i < 4; i++) {
            r.recipe(mat[i] + "_pickaxe", RecipeSettings.shaped("MM", ".S", ".S").key('M', headKey[i]).key('S', "stick").result(mat[i] + "_pickaxe", 1));
            r.recipe(mat[i] + "_axe", RecipeSettings.shaped("M.", "MS", ".S").key('M', headKey[i]).key('S', "stick").result(mat[i] + "_axe", 1));
            r.recipe(mat[i] + "_sword", RecipeSettings.shaped("M", "M", "S").key('M', headKey[i]).key('S', "stick").result(mat[i] + "_sword", 1));
            r.recipe(mat[i] + "_shovel", RecipeSettings.shaped("M", "S", "S").key('M', headKey[i]).key('S', "stick").result(mat[i] + "_shovel", 1));
        }

        // --- їжа й здобич з істот
        tex("raw_chicken", ItemTextureGen.drumstick(0xE8A890)); tex("cooked_chicken", ItemTextureGen.drumstick(0xC98A4A)); tex("raw_beef", ItemTextureGen.steak(0xC0504A, false)); tex("steak", ItemTextureGen.steak(0x8A4F2A, true));
        tex("rotten_flesh", ItemTextureGen.rottenFlesh()); tex("leather", ItemTextureGen.leather()); tex("feather", ItemTextureGen.feather()); tex("string", ItemTextureGen.stringItem());
        r.item("raw_chicken", new ItemSettings().color(0xE8B8A0).food(2).texture("raw_chicken").title("Сире куряче м'ясо"));
        r.item("cooked_chicken", new ItemSettings().color(0xC98A4A).food(6).texture("cooked_chicken").title("Смажена курка"));
        r.item("raw_beef", new ItemSettings().color(0xC0504A).food(3).texture("raw_beef").title("Сира яловичина"));
        r.item("steak", new ItemSettings().color(0x7A4A2A).food(8).texture("steak").title("Стейк"));
        r.item("rotten_flesh", new ItemSettings().color(0x6B7A3A).food(1).texture("rotten_flesh").title("Гнила плоть"));
        r.item("leather", new ItemSettings().color(0x8A5A32).texture("leather").title("Шкіра"));
        r.item("feather", new ItemSettings().color(0xF0F0F0).texture("feather").title("Пір'я"));
        r.item("string", new ItemSettings().color(0xDDDDCC).texture("string").title("Нитка"));
        r.recipe("cook_chicken", RecipeSettings.smelting("raw_chicken", 10).result("cooked_chicken", 1));
        r.recipe("cook_beef", RecipeSettings.smelting("raw_beef", 10).result("steak", 1));

        // --- істоти (моделі - прості коробки; своє й текстуровані - пізніше)
        r.mob("chicken", new MobSettings().health(4).speed(1.6f).size(0.4f, 0.75f).behavior(MobSettings.Behavior.PASSIVE).spawnOn("grass").title("Курка")
                .drops("raw_chicken", 1, 1, 1f).drops("feather", 0, 2, 1f)
                .part(0, 0.25f, 0, 0.36f, 0.30f, 0.5f, 0xF2F2F2).part(0, 0.5f, -0.3f, 0.2f, 0.3f, 0.2f, 0xF2F2F2).part(0, 0.52f, -0.43f, 0.1f, 0.07f, 0.08f, 0xF0A030)
                .part(0, 0.78f, -0.3f, 0.06f, 0.07f, 0.1f, 0xD03030).part(0, 0.4f, 0.28f, 0.2f, 0.2f, 0.08f, 0xE6E6E6)
                .legPart(-0.09f, 0, 0, 0.06f, 0.25f, 0.06f, 0xF0A030, false).legPart(0.09f, 0, 0, 0.06f, 0.25f, 0.06f, 0xF0A030, true));
        r.mob("cow", new MobSettings().health(10).speed(1.5f).size(0.9f, 1.4f).behavior(MobSettings.Behavior.PASSIVE).spawnOn("grass").title("Корова")
                .drops("raw_beef", 1, 3, 1f).drops("leather", 0, 2, 1f)
                .part(0, 0.55f, 0, 0.8f, 0.7f, 1.2f, 0x6B4A2B).part(0, 1.0f, 0.1f, 0.5f, 0.06f, 0.6f, 0xE8E0D0)
                .part(0, 0.95f, -0.75f, 0.5f, 0.5f, 0.4f, 0x5C3E22).part(0, 0.9f, -0.97f, 0.3f, 0.2f, 0.08f, 0xE8B0B0)
                .part(-0.22f, 1.4f, -0.7f, 0.06f, 0.15f, 0.06f, 0xDDDDDD).part(0.22f, 1.4f, -0.7f, 0.06f, 0.15f, 0.06f, 0xDDDDDD)
                .legPart(-0.28f, 0, -0.4f, 0.2f, 0.55f, 0.2f, 0x4E3520, false).legPart(0.28f, 0, -0.4f, 0.2f, 0.55f, 0.2f, 0x4E3520, true)
                .legPart(-0.28f, 0, 0.4f, 0.2f, 0.55f, 0.2f, 0x4E3520, true).legPart(0.28f, 0, 0.4f, 0.2f, 0.55f, 0.2f, 0x4E3520, false));
        r.mob("zombie", new MobSettings().health(20).speed(2.4f).size(0.6f, 1.8f).behavior(MobSettings.Behavior.HOSTILE).attack(3, 1.2f, 1f).detect(16).burnsInSun(true).title("Зомбі")
                .drops("rotten_flesh", 0, 2, 1f)
                .legPart(-0.14f, 0, 0, 0.25f, 0.8f, 0.25f, 0x2B3F8F, false).legPart(0.14f, 0, 0, 0.25f, 0.8f, 0.25f, 0x2B3F8F, true)
                .part(0, 0.8f, 0, 0.5f, 0.7f, 0.25f, 0x2F8F8F)
                .part(-0.38f, 1.0f, -0.25f, 0.2f, 0.2f, 0.6f, 0x5B8F3A).part(0.38f, 1.0f, -0.25f, 0.2f, 0.2f, 0.6f, 0x5B8F3A)
                .part(0, 1.5f, 0, 0.5f, 0.5f, 0.5f, 0x5B8F3A));
        MobSettings spider = new MobSettings().health(16).speed(3.2f).size(1.0f, 0.6f).behavior(MobSettings.Behavior.NEUTRAL_DAY).attack(2, 1.3f, 0.8f).detect(12).title("Павук")
                .drops("string", 0, 2, 1f)
                .part(0, 0.25f, 0.15f, 0.6f, 0.35f, 0.6f, 0x2A2420).part(0, 0.28f, -0.4f, 0.4f, 0.3f, 0.35f, 0x3A302A)
                .part(-0.1f, 0.4f, -0.58f, 0.07f, 0.07f, 0.04f, 0xCC2020).part(0.1f, 0.4f, -0.58f, 0.07f, 0.07f, 0.04f, 0xCC2020);
        float[] legZ = {-0.25f, -0.05f, 0.15f, 0.35f};
        for (int i = 0; i < 4; i++) { spider.legPart(-0.55f, 0.2f, legZ[i], 0.5f, 0.08f, 0.08f, 0x1E1A17, i % 2 == 0); spider.legPart(0.55f, 0.2f, legZ[i], 0.5f, 0.08f, 0.08f, 0x1E1A17, i % 2 != 0); }
        r.mob("spider", spider);

        // --- жителі (S6): мирні, живуть біля дому, торгують за золотий злиток; модель - коробки, колір одягу за професією
        int[][] pal = {{0x8B5A2B, 0xE0C060}, {0x444A55, 0x2B2B30}, {0xE8E4D8, 0xC9C9C9}}; // одяг, капелюх/борода
        String[] vName = {"villager_farmer", "villager_smith", "villager_elder"}, vTitle = {"Житель-фермер", "Житель-коваль", "Старійшина"};
        for (int i = 0; i < 3; i++) {
            MobSettings v = new MobSettings().health(20).speed(1.4f).size(0.6f, 1.8f).behavior(MobSettings.Behavior.PASSIVE).homeRadius(24).persistent(true).title(vTitle[i])
                    .legPart(-0.13f, 0, 0, 0.24f, 0.8f, 0.24f, 0x5A4630, false).legPart(0.13f, 0, 0, 0.24f, 0.8f, 0.24f, 0x5A4630, true)
                    .part(0, 0.8f, 0, 0.5f, 0.75f, 0.3f, pal[i][0]).part(0, 1.05f, -0.2f, 0.5f, 0.2f, 0.18f, pal[i][0])
                    .part(0, 1.5f, 0, 0.45f, 0.5f, 0.45f, 0xD9A87A).part(0, 1.58f, -0.26f, 0.1f, 0.2f, 0.1f, 0xC4906A);
            if (i == 0) v.part(0, 1.95f, 0, 0.75f, 0.06f, 0.75f, pal[i][1]).part(0, 1.99f, 0, 0.42f, 0.14f, 0.42f, pal[i][1]);
            if (i == 1) v.part(0, 0.8f, -0.17f, 0.46f, 0.5f, 0.06f, pal[i][1]);
            if (i == 2) v.part(0, 1.2f, -0.24f, 0.32f, 0.35f, 0.08f, pal[i][1]);
            if (i == 0) v.greeting("Добрий день! Маю свіже м'ясо, якщо маєш чим розплатитись.", "Яблука з дерев - то найкращі, що я куштував.", "Золото любить тих, хто вміє торгувати.")
                    .trade("apple", 8, "gold_ingot", 1).trade("raw_beef", 6, "gold_ingot", 1).trade("raw_chicken", 6, "gold_ingot", 1)
                    .trade("gold_ingot", 1, "steak", 3).trade("gold_ingot", 1, "cooked_chicken", 3);
            if (i == 1) v.greeting("Залізо любить терпіння. Золото любить мене.", "Принеси злитки - викую щось путнє.", "Піч - перший друг мандрівника.")
                    .trade("iron_ingot", 4, "gold_ingot", 1).trade("gold_ingot", 3, "iron_pickaxe", 1).trade("gold_ingot", 3, "iron_sword", 1).trade("gold_ingot", 1, "coal", 8);
            if (i == 2) v.greeting("Тут колись було тихо... Кажуть, під лімінітом земля тонка, як папір.", "Не ходи ночами в печери без світла. Темрява тут - не просто відсутність світла.", "Мандрівники приносять дивні вісті про Шов. Я лише старий і не вірю в казки.", "Є місця, де тиша гучніша за грім. Обминай мовчазні пустища.");
            r.mob(vName[i], v);
        }
        r.structure("ruins", Structures.RUINS);
        r.structure("dungeon", Structures.DUNGEON);
        r.structure("mine", Structures.MINE);
        r.structure("temple", Structures.TEMPLE);
        r.structure("fortress", Structures.FORTRESS);
        r.structure("village_house_small", Structures.VILLAGE_HOUSE_SMALL);
        r.structure("village_house_big", Structures.VILLAGE_HOUSE_BIG);
        r.structure("village_hall", Structures.VILLAGE_HALL);
        r.structure("village_well", Structures.VILLAGE_WELL);
        r.structure("village_farm", Structures.VILLAGE_FARM);
        r.structure("village_lamp", Structures.VILLAGE_LAMP);

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
            final long fx0 = x0, fx1 = x1, fz0 = z0, fz1 = z1; final int fy0 = y0, fy1 = y1; final BlockRef fb = b; final long[] cnt = {0};
            k.world().batch(() -> { for (long x = fx0; x <= fx1; x++) for (int y = fy0; y <= fy1; y++) for (long z = fz0; z <= fz1; z++) if (k.world().setBlock(k.source(), x, y, z, fb)) cnt[0]++; });
            n = cnt[0];
            k.reply("Заповнено " + n + " з " + (long) vol + " блоків" + (n < (long) vol ? " (решта - чанки не завантажені або ліміт)" : ""));
        });
        c.register("time", "[set <день|ніч|вечір|світанок|північ|полудень|число> | add <число>]", 2, k -> {
            WorldAccess w = k.world();
            long dt = w.dayTicks(), t = w.time();
            if (k.args().isEmpty()) {
                double p = (double) Math.floorMod(t, dt) / dt;
                k.reply("День " + (Math.floorDiv(t, dt) + 1) + ", " + DayCycle.phaseName(p) + " (" + String.format(java.util.Locale.ROOT, "%.0f", p * 100) + "% доби; доба = " + dt / 1200.0 + " хв)");
                return;
            }
            if (k.args().size() != 2) throw new Commands.CommandException("Потрібно 2 аргументи");
            String sub = k.args().get(0).toLowerCase(java.util.Locale.ROOT), arg = k.args().get(1).toLowerCase(java.util.Locale.ROOT);
            if (sub.equals("set")) {
                long inDay;
                double ph = DayCycle.presetPhase(arg);
                if (ph >= 0) inDay = (long) (ph * dt);
                else try { inDay = Math.floorMod(Long.parseLong(arg), dt); } catch (NumberFormatException e) { throw new Commands.CommandException("Не час: " + arg); }
                w.setTime(Math.floorDiv(Math.max(0, t), dt) * dt + inDay);
            } else if (sub.equals("add")) {
                long n;
                try { n = Long.parseLong(arg); w.setTime(Math.addExact(t, n)); }
                catch (NumberFormatException | ArithmeticException e) { throw new Commands.CommandException("Не число (або надто велике): " + arg); }
            } else throw new Commands.CommandException("Невідома дія: " + sub);
            k.reply("Час: день " + (Math.floorDiv(w.time(), dt) + 1) + ", " + DayCycle.phaseName((double) Math.floorMod(w.time(), dt) / dt));
        });
        c.register("heal", "", 2, k -> { vit(k).heal(Vitals.MAX); k.reply("Здоров'я відновлено"); });
        c.register("hurt", "<шкода>", 2, k -> { vit(k).damage(amount(k)); k.reply("Шкода завдана (здоров'я " + vit(k).health() + ")"); });
        c.register("eat", "<їжа>", 2, k -> { vit(k).feed(amount(k)); k.reply("Голод " + vit(k).hunger()); });
        c.register("give", "<предмет> [кількість]", 2, k -> {
            ItemHolder h = k.world().items(k.source());
            if (h == null) throw new Commands.CommandException("У цього джерела нема інвентаря");
            if (k.args().isEmpty() || k.args().size() > 2) throw new Commands.CommandException("Потрібна назва предмета й (необов'язково) кількість");
            String name = k.args().get(0).toLowerCase(java.util.Locale.ROOT);
            if (name.indexOf(':') < 0) name = "voxelin:" + name;
            if (!(k.world() instanceof voxelin.core.World)) throw new Commands.CommandException("Недоступно");
            voxelin.core.ItemType t = ((voxelin.core.World) k.world()).itemTypes.get(name);
            if (t == null) throw new Commands.CommandException("Невідомий предмет: " + k.args().get(0));
            int n = 1;
            if (k.args().size() == 2) try { n = Integer.parseInt(k.args().get(1)); } catch (NumberFormatException e) { throw new Commands.CommandException("Не число: " + k.args().get(1)); }
            if (n < 1 || n > 2304) throw new Commands.CommandException("Кількість від 1 до 2304: " + n);
            int left = h.give(t.name, n);
            k.reply("Видано " + (n - left) + " x " + t.name + (left > 0 ? " (не влізло " + left + ")" : ""));
        });
        c.register("drop", "<предмет> [кількість]", 2, k -> {
            if (!(k.world() instanceof voxelin.core.World)) throw new Commands.CommandException("Недоступно");
            voxelin.core.World w = (voxelin.core.World) k.world();
            if (k.args().isEmpty() || k.args().size() > 2) throw new Commands.CommandException("Потрібна назва предмета й (необов'язково) кількість");
            voxelin.core.ItemType t = w.itemTypes.find(k.args().get(0));
            if (t == null) throw new Commands.CommandException("Невідомий предмет: " + k.args().get(0));
            int n = 1;
            if (k.args().size() == 2) try { n = Integer.parseInt(k.args().get(1)); } catch (NumberFormatException e) { throw new Commands.CommandException("Не число: " + k.args().get(1)); }
            if (n < 1 || n > 64) throw new Commands.CommandException("Кількість від 1 до 64: " + n);
            long[] o = k.origin();
            w.spawnItem(voxelin.core.Pos.ofBlock(o[0], o[1] + 1.2, o[2]), t.name, n, 0, true);
            k.reply("Викинуто " + n + " x " + t.name);
        });
        c.register("summon", "<вид> [кількість]", 2, k -> {
            if (!(k.world() instanceof voxelin.core.World)) throw new Commands.CommandException("Недоступно");
            voxelin.core.World w = (voxelin.core.World) k.world();
            if (k.args().isEmpty() || k.args().size() > 2) throw new Commands.CommandException("Потрібен вид істоти й (необов'язково) кількість");
            voxelin.core.MobType t = w.mobTypes.find(k.args().get(0));
            if (t == null) throw new Commands.CommandException("Невідомий вид: " + k.args().get(0));
            int n = 1;
            if (k.args().size() == 2) try { n = Integer.parseInt(k.args().get(1)); } catch (NumberFormatException e) { throw new Commands.CommandException("Не число: " + k.args().get(1)); }
            if (n < 1 || n > 32) throw new Commands.CommandException("Кількість від 1 до 32: " + n);
            long[] o = k.origin();
            int made = 0;
            for (int i = 0; i < n; i++) if (w.summon(t, o[0] + 2 + i % 4, (int) o[1], o[2] + i / 4) != null) made++;
            k.reply(made == 0 ? "Не вдалось (чанк не завантажено або забагато істот)" : "Створено " + made + " x " + t.name);
        });
        c.register("killall", "[вид]", 2, k -> {
            if (!(k.world() instanceof voxelin.core.World)) throw new Commands.CommandException("Недоступно");
            voxelin.core.World w = (voxelin.core.World) k.world();
            if (k.args().size() > 1) throw new Commands.CommandException("Потрібен щонайбільше один аргумент");
            voxelin.core.MobType t = null;
            if (k.args().size() == 1) { t = w.mobTypes.find(k.args().get(0)); if (t == null) throw new Commands.CommandException("Невідомий вид: " + k.args().get(0)); }
            k.reply("Прибрано істот: " + w.killAll(t));
        });
        c.register("locate", "<структура>", 2, k -> {
            if (k.args().size() != 1) throw new Commands.CommandException("Потрібна назва структури");
            if (!(k.world() instanceof voxelin.core.World)) throw new Commands.CommandException("Недоступно");
            voxelin.core.World w = (voxelin.core.World) k.world();
            long[] o = k.origin();
            long[] r = k.world().locate(k.args().get(0), o[0], o[2]);
            if (r == null) throw new Commands.CommandException("Не знайдено: " + k.args().get(0) + " (відомі: " + String.join(", ", w.structureNames()) + ")");
            k.reply(k.args().get(0) + ": x " + r[0] + "  y " + r[1] + "  z " + r[2] + "  (" + r[3] + " блоків)");
        });
        c.register("tp", "<x> <y> <z>", 2, k -> {
            if (k.args().size() != 3) throw new Commands.CommandException("Потрібні три координати");
            long[] o = k.origin();
            long x = CommandRegistry.coord(k.args().get(0), o[0]), y = CommandRegistry.coord(k.args().get(1), o[1]), z = CommandRegistry.coord(k.args().get(2), o[2]);
            if (y < 0 || y > 254) throw new Commands.CommandException("Висота від 0 до 254");
            if (!k.world().teleport(k.source(), x, (int) y, z)) throw new Commands.CommandException("Не вдалось перенести");
            k.reply("Перенесено в " + x + " " + y + " " + z);
        });
        c.register("clear", "", 2, k -> {
            ItemHolder h = k.world().items(k.source());
            if (h == null) throw new Commands.CommandException("У цього джерела нема інвентаря");
            h.clear(); k.reply("Інвентар очищено");
        });
        c.register("gamemode", "<survival|creative>", 2, k -> {
            if (k.args().size() != 1) throw new Commands.CommandException("Потрібен один аргумент");
            boolean was = k.world().gameMode().equals("creative");
            if (!k.world().setGameMode(k.args().get(0))) throw new Commands.CommandException("Невідомий режим: " + k.args().get(0));
            boolean now = k.world().gameMode().equals("creative");
            ItemHolder h = k.world().items(k.source());
            if (now && !was && h != null) for (String s : STARTER) h.give(s, 64);
            k.reply("Режим гри: " + k.world().gameMode());
        });
        c.register("help", "", 0, k -> k.reply("Команди: /setblock, /fill, /time, /heal, /hurt, /eat, /give, /drop, /clear, /gamemode, /summon, /killall, /locate, /tp, /help. Назви блоків: voxelin:stone, voxelin:grass, ..."));
    }

    /** Назва блока з аргументу команди: "stone" = "voxelin:stone". */
    static BlockRef block(Registries r, String arg) throws Commands.CommandException {
        BlockRef b = r.findBlock(arg.contains(":") ? arg : "voxelin:" + arg);
        if (b == null) throw new Commands.CommandException("Невідомий блок: " + arg);
        return b;
    }
}
