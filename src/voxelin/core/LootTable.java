package voxelin.core;

import voxelin.api.LootSettings;

import java.util.*;

/** Таблиця здобичі: кілька кидків жеребу за вагами. */
public final class LootTable {
    public final String id;
    private final int minRolls, maxRolls;
    private final List<LootSettings.Entry> entries;
    private final int totalWeight;

    LootTable(String id, LootSettings s, List<LootSettings.Entry> valid) {
        this.id = id; minRolls = s.minRollsValue(); maxRolls = s.maxRollsValue(); entries = List.copyOf(valid);
        int t = 0; for (LootSettings.Entry e : entries) t += e.weight;
        totalWeight = t;
    }

    public List<LootSettings.Entry> entries() { return entries; }

    /** Випадкові стеки (один кидок = один запис; однакові об'єднуються до розміру стопки). */
    public List<Inventory.Stack> roll(Random rng, ItemRegistry items) {
        List<Inventory.Stack> out = new ArrayList<>();
        int rolls = minRolls + (maxRolls > minRolls ? rng.nextInt(maxRolls - minRolls + 1) : 0);
        for (int i = 0; i < rolls; i++) {
            int w = rng.nextInt(totalWeight);
            LootSettings.Entry pick = entries.get(entries.size() - 1);
            for (LootSettings.Entry e : entries) { if (w < e.weight) { pick = e; break; } w -= e.weight; }
            int n = Math.min(items.maxStack(pick.item), pick.min + (pick.max > pick.min ? rng.nextInt(pick.max - pick.min + 1) : 0));
            out.add(new Inventory.Stack(pick.item, n));
        }
        return out;
    }

    /** Розкласти здобич у випадкові вільні слоти скрині. */
    public void fill(ChestEntity chest, Random rng, ItemRegistry items) {
        for (Inventory.Stack st : roll(rng, items)) {
            for (int tries = 0; tries < 60; tries++) {
                int slot = rng.nextInt(ChestEntity.SIZE);
                if (chest.slots[slot] == null) { chest.slots[slot] = st; break; }
            }
        }
    }
}
