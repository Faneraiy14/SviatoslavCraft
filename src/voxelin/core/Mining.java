package voxelin.core;

import voxelin.api.ToolKind;

import java.util.*;

/** Правила видобутку (чисті функції, як у Minecraft): час ламання від твердості й інструмента, чи є дроп, що випадає. */
public final class Mining {
    private Mining() {}

    public static final double HAND_FACTOR = 5.0, TOOL_FACTOR = 1.5;

    /** Інструмент підходить цьому блоку (кирка для каменю тощо). */
    public static boolean matches(BlockType b, ItemType tool) { return tool != null && b.tool != ToolKind.NONE && tool.tool == b.tool; }

    /** Чи дасть блок дроп цим інструментом (tool = null - рука). Незламний - ні. */
    public static boolean canHarvest(BlockType b, ItemType tool) {
        if (b.hardness < 0) return false;
        return b.minTier <= 0 || (matches(b, tool) && tool.tier >= b.minTier);
    }

    /** Секунди ламання; +Infinity - незламний. Креатив - 0. */
    public static double seconds(BlockType b, ItemType tool, boolean creative) {
        if (b.hardness < 0) return Double.POSITIVE_INFINITY;
        if (creative) return 0;
        double speed = matches(b, tool) ? tool.speed : 1.0;
        return b.hardness * (canHarvest(b, tool) ? TOOL_FACTOR : HAND_FACTOR) / speed;
    }

    /** Те, що випаде з блока (порожньо, якщо інструмент не годиться). Кількість - випадкова в min..max, шанс - на кожен запис окремо. */
    public static List<Inventory.Stack> drops(BlockType b, ItemType tool, Random rng) {
        if (!canHarvest(b, tool)) return List.of();
        List<Inventory.Stack> out = new ArrayList<>();
        for (voxelin.api.BlockSettings.Drop d : b.drops) {
            if (d.chance < 1f && rng.nextFloat() >= d.chance) continue;
            int n = d.min + (d.max > d.min ? rng.nextInt(d.max - d.min + 1) : 0);
            if (n > 0) out.add(new Inventory.Stack(d.item, n));
        }
        return out;
    }
}
