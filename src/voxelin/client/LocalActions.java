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

    @Override public boolean breakBlock(int x, int y, int z) {
        int id = world.blockId(x, y, z);
        if (id <= 0 || world.blocks.get(id).hardness < 0) return false;
        return world.setBlock(player, x, y, z, world.blocks.air());
    }

    @Override public boolean placeBlock(int x, int y, int z, String block) {
        BlockType t = world.blocks.byName(block);
        int cur = world.blockId(x, y, z);
        if (t == null || cur < 0 || (cur != 0 && !world.blocks.get(cur).liquid)) return false;
        return world.setBlock(player, x, y, z, t);
    }

    @Override public List<String> command(String line, int[] origin) { return commands.execute(player, world, origin, line); }
}
