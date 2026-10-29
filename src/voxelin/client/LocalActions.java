package voxelin.client;

import voxelin.api.Source;
import voxelin.core.*;

import java.util.List;

/** Одиночна гра: дії гравця напряму в ядро (без мережі й затримок). */
public final class LocalActions implements PlayerActions {
    private final World world;
    private final CommandRegistry commands;
    private final Source player;

    public LocalActions(World world, CommandRegistry commands, Source player) { this.world = world; this.commands = commands; this.player = player; }

    @Override public boolean breakBlock(long x, int y, long z, String tool, boolean drops) {
        return world.harvest(player, x, y, z, tool == null ? null : world.itemTypes.get(tool), drops);
    }

    @Override public boolean placeBlock(long x, int y, long z, String block) {
        BlockType t = world.blocks.byName(block);
        int cur = world.blockId(x, y, z);
        if (t == null || cur < 0 || (cur != 0 && !world.blocks.get(cur).liquid)) return false;
        return world.setBlock(player, x, y, z, t);
    }

    @Override public boolean breakBoard(long x, int y, long z, int bit, boolean drops) { return world.harvestBoard(player, x, y, z, bit, drops); }

    @Override public boolean placeBoard(long x, int y, long z, String block, int axis, int sx, int sy, int sz) {
        BlockType t = world.blocks.byName(block);
        return t != null && world.boards.place(player, x, y, z, t, axis, sx, sy, sz);
    }

    @Override public List<String> command(String line, long[] origin) { return commands.execute(player, world, origin, line); }
}
