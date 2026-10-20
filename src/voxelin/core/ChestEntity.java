package voxelin.core;

import java.util.ArrayList;
import java.util.List;

/** Скриня: 27 слотів. Зберігається зі світом, вміст випадає при зламі. */
public final class ChestEntity extends BlockEntity {
    public static final int SIZE = 27;
    final Inventory.Stack[] slots = new Inventory.Stack[SIZE];

    public ChestEntity(long x, int y, long z) { super(x, y, z); }

    @Override public String kind() { return "chest"; }
    @Override public void tick(World world) {}

    public Inventory.Stack get(int i) { return i >= 0 && i < SIZE ? slots[i] : null; }
    public void set(int i, Inventory.Stack s) { if (i >= 0 && i < SIZE) slots[i] = s == null || s.count <= 0 ? null : s; }
    public boolean isEmpty() { for (Inventory.Stack s : slots) if (s != null) return false; return true; }

    @Override public String encode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SIZE; i++) {
            if (i > 0) sb.append(';');
            Inventory.Stack s = slots[i];
            sb.append(s == null ? "-" : s.item + "|" + s.count + "|" + s.damage);
        }
        return sb.toString();
    }

    @Override public void decode(String data) {
        String[] p = data.split(";", -1);
        if (p.length != SIZE) throw new IllegalArgumentException("скриня: потрібно " + SIZE + " слотів, є " + p.length);
        Inventory.Stack[] tmp = new Inventory.Stack[SIZE];
        for (int i = 0; i < SIZE; i++) {
            if (p[i].equals("-")) continue;
            String[] f = p[i].split("\\|");
            if (f.length != 3 || !f[0].matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("бита клітинка: " + p[i]);
            try {
                int c = Integer.parseInt(f[1]), d = Integer.parseInt(f[2]);
                if (c < 1 || c > 64 || d < 0) throw new IllegalArgumentException("бита кількість: " + p[i]);
                tmp[i] = new Inventory.Stack(f[0], c, d);
            } catch (NumberFormatException e) { throw new IllegalArgumentException("бите число: " + p[i]); }
        }
        System.arraycopy(tmp, 0, slots, 0, SIZE);
    }

    @Override public List<Inventory.Stack> contents() {
        List<Inventory.Stack> r = new ArrayList<>();
        for (Inventory.Stack s : slots) if (s != null) r.add(s);
        return r;
    }
}
