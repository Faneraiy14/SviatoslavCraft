package voxelin.content;

import voxelin.api.*;
import voxelin.core.CommandRegistry;

/**
 * Ванільний вміст Voxelin - звичайний «мод» з id "voxelin", що реєструється через той самий API, що й сторонні моди.
 * Етап «Новий світ» додасть текстури, руди, біоми й решту блоків.
 */
public final class VanillaContent implements VoxelinMod {
    /** Блоки, які гравець отримує в хотбар у новому світі (поки немає видобутку й крафту). */
    public static final String[] STARTER = {"voxelin:grass", "voxelin:dirt", "voxelin:stone", "voxelin:oak_log", "voxelin:oak_leaves", "voxelin:oak_planks", "voxelin:glass"};

    public static final int MAX_FILL = 32768 * 8;

    @Override public void init(ModContext ctx) {
        Registries r = ctx.registries();
        r.block("grass", new BlockSettings().color(0x339A1A).hardness(0.6f));
        r.block("dirt", new BlockSettings().color(0x804D1A).hardness(0.5f));
        r.block("stone", new BlockSettings().color(0x808080).hardness(1.5f));
        r.block("bedrock", new BlockSettings().color(0x2E2E33).hardness(-1f));
        r.block("oak_log", new BlockSettings().color(0x66330D).hardness(2f));
        r.block("oak_leaves", new BlockSettings().color(0x1F7A1F).opaque(false).hardness(0.2f));
        r.block("oak_planks", new BlockSettings().color(0xA8834D).hardness(2f));
        r.block("glass", new BlockSettings().color(0xCFEFF5).translucent(true).hardness(0.3f));

        Commands c = ctx.commands();
        c.register("setblock", "<x> <y> <z> <блок>", 2, k -> {
            if (k.args().size() != 4) throw new Commands.CommandException("Потрібно 4 аргументи");
            int[] o = k.origin();
            BlockRef b = block(r, k.args().get(3));
            int x = CommandRegistry.coord(k.args().get(0), o[0]), y = CommandRegistry.coord(k.args().get(1), o[1]), z = CommandRegistry.coord(k.args().get(2), o[2]);
            k.reply(k.world().setBlock(k.source(), x, y, z, b) ? "Поставлено " + b.name() + " у " + x + " " + y + " " + z : "Не вдалось (поза світом або чанк не завантажено)");
        });
        c.register("fill", "<x1> <y1> <z1> <x2> <y2> <z2> <блок>", 2, k -> {
            if (k.args().size() != 7) throw new Commands.CommandException("Потрібно 7 аргументів");
            int[] o = k.origin(), p = new int[6];
            for (int i = 0; i < 6; i++) p[i] = CommandRegistry.coord(k.args().get(i), o[i % 3]);
            BlockRef b = block(r, k.args().get(6));
            int x0 = Math.min(p[0], p[3]), x1 = Math.max(p[0], p[3]), y0 = Math.max(0, Math.min(p[1], p[4])), y1 = Math.min(k.world().height() - 1, Math.max(p[1], p[4])),
                    z0 = Math.min(p[2], p[5]), z1 = Math.max(p[2], p[5]);
            long vol = (long) (x1 - x0 + 1) * Math.max(0, y1 - y0 + 1) * (z1 - z0 + 1);
            if (vol > MAX_FILL) throw new Commands.CommandException("Забагато блоків: " + vol + " (максимум " + MAX_FILL + ")");
            int n = 0;
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) if (k.world().setBlock(k.source(), x, y, z, b)) n++;
            k.reply("Заповнено " + n + " з " + vol + " блоків" + (n < vol ? " (решта - поза завантаженими чанками або ліміт)" : ""));
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
