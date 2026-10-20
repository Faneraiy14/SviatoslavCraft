package voxelin.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Піч: вхід, паливо, вихід, пальне (тіки), прогрес плавки. Правила як у Minecraft: паливо береться, коли є що плавити; прогрес росте лише поки горить;
 * без вогню чи при заповненому виході прогрес повільно спадає.
 */
public final class FurnaceEntity extends BlockEntity {
    public Inventory.Stack input, fuel, output;
    public int burnLeft, burnTotal, progress, cookTotal;

    public FurnaceEntity(long x, int y, long z) { super(x, y, z); }

    @Override public String kind() { return "furnace"; }

    public boolean lit() { return burnLeft > 0; }

    @Override public void tick(World w) { tick(w.itemTypes, w.recipes); }

    /** Окремо від світу - щоб тестувати без нього. */
    public void tick(ItemRegistry items, RecipeRegistry recipes) {
        RecipeRegistry.Smelt m = input == null ? null : recipes.smeltFor(input.item);
        boolean can = m != null && (output == null || (output.item.equals(m.result) && output.damage == 0 && output.count + m.count <= items.maxStack(m.result)));
        if (burnLeft > 0) burnLeft--;
        if (can) {
            cookTotal = m.ticks;
            if (burnLeft == 0 && fuel != null) {
                int ft = recipes.fuelTicks(fuel.item);
                if (ft > 0) { burnLeft = burnTotal = ft; if (--fuel.count <= 0) fuel = null; }
            }
            if (burnLeft > 0) {
                if (++progress >= m.ticks) {
                    progress = 0;
                    if (--input.count <= 0) input = null;
                    if (output == null) output = new Inventory.Stack(m.result, m.count); else output.count += m.count;
                }
            } else progress = Math.max(0, progress - 2);
        } else progress = Math.max(0, progress - 2);
    }

    private static String enc(Inventory.Stack s) { return s == null ? "-" : s.item + "|" + s.count + "|" + s.damage; }

    private static Inventory.Stack dec(String t) {
        if (t.equals("-")) return null;
        String[] p = t.split("\\|");
        if (p.length != 3 || !p[0].matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("бита клітинка: " + t);
        int c = Integer.parseInt(p[1]), d = Integer.parseInt(p[2]);
        if (c < 1 || c > 64 || d < 0) throw new IllegalArgumentException("бита кількість: " + t);
        return new Inventory.Stack(p[0], c, d);
    }

    @Override public String encode() { return enc(input) + "," + enc(fuel) + "," + enc(output) + "," + burnLeft + "," + burnTotal + "," + progress; }

    @Override public void decode(String data) {
        String[] p = data.split(",");
        if (p.length != 6) throw new IllegalArgumentException("піч: потрібно 6 полів");
        try {
            input = dec(p[0]); fuel = dec(p[1]); output = dec(p[2]);
            burnLeft = Math.max(0, Math.min(1_000_000, Integer.parseInt(p[3]))); burnTotal = Math.max(burnLeft, Math.min(1_000_000, Integer.parseInt(p[4]))); progress = Math.max(0, Math.min(1_000_000, Integer.parseInt(p[5])));
        } catch (NumberFormatException e) { throw new IllegalArgumentException("піч: бите число"); }
    }

    @Override public List<Inventory.Stack> contents() {
        List<Inventory.Stack> r = new ArrayList<>();
        if (input != null) r.add(input);
        if (fuel != null) r.add(fuel);
        if (output != null) r.add(output);
        return r;
    }
}
