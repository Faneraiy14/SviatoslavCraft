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
        float[] spd = {2f, 4f, 6f, 8f};
        for (int i = 0; i < 4; i++) {
            tex(mat[i] + "_pickaxe", ItemTextureGen.pickaxe(i)); tex(mat[i] + "_axe", ItemTextureGen.axe(i)); tex(mat[i] + "_shovel", ItemTextureGen.shovel(i));
            r.item(mat[i] + "_pickaxe", new ItemSettings().tool(ToolKind.PICKAXE, tier[i], spd[i]).durability(dur[i]).color(col[i]).texture(mat[i] + "_pickaxe").title(matTitle[i] + " кирка"));
            r.item(mat[i] + "_axe", new ItemSettings().tool(ToolKind.AXE, tier[i], spd[i]).durability(dur[i]).color(col[i]).texture(mat[i] + "_axe").title(matTitle[i] + " сокира"));
            r.item(mat[i] + "_shovel", new ItemSettings().tool(ToolKind.SHOVEL, tier[i], spd[i]).durability(dur[i]).color(col[i]).texture(mat[i] + "_shovel").title(matTitle[i] + " лопата"));
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
            r.recipe(mat[i] + "_shovel", RecipeSettings.shaped("M", "S", "S").key('M', headKey[i]).key('S', "stick").result(mat[i] + "_shovel", 1));
        }

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
        c.register("help", "", 0, k -> k.reply("Команди: /setblock, /fill, /time, /heal, /hurt, /eat, /give, /drop, /clear, /gamemode, /help. Назви блоків: voxelin:stone, voxelin:grass, ..."));
    }

    /** Назва блока з аргументу команди: "stone" = "voxelin:stone". */
    static BlockRef block(Registries r, String arg) throws Commands.CommandException {
        BlockRef b = r.findBlock(arg.contains(":") ? arg : "voxelin:" + arg);
        if (b == null) throw new Commands.CommandException("Невідомий блок: " + arg);
        return b;
    }
}
