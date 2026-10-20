package voxelin.client;

import voxelin.api.MobSettings;
import voxelin.client.render.IconAtlas;
import voxelin.client.ui.Gui;
import voxelin.core.*;
import voxelin.core.entity.Mob;

import java.util.List;
import java.util.Random;

/** Розмова з жителем: репліка, список угод «віддай - отримай» (клік виконує, якщо вистачає), закрити - Esc або кнопка. */
final class TradeScreen {
    final Mob mob;
    private final World world;
    private final Inventory inv;
    private final IconAtlas icons;
    private final List<MobSettings.Trade> offers;
    private final String line;
    private String message = "";

    TradeScreen(Mob mob, World world, Inventory inv, IconAtlas icons, Random rng) {
        this.mob = mob; this.world = world; this.inv = inv; this.icons = icons;
        offers = Trading.offers(mob.type, world.itemTypes);
        line = mob.type.greetings.isEmpty() ? "..." : mob.type.greetings.get(rng.nextInt(mob.type.greetings.size()));
    }

    private void item(String name, int count, float x, float y) {
        Gui.rect(x - 2, y - 2, 58, 58, 0.08f, 0.08f, 0.08f, 0.9f);
        Gui.rect(x, y, 54, 54, 0.3f, 0.3f, 0.33f, 0.92f);
        icons.draw(name, x + 3, y + 3, 48);
        if (count > 1) { String c = String.valueOf(count); Gui.text(c, x + 54 - Gui.textWidth(c, 1.8f) - 2, y + 54 - 15, 1.8f, 0, 0, 0); Gui.text(c, x + 54 - Gui.textWidth(c, 1.8f) - 3, y + 54 - 16, 1.8f, 1, 1, 1); }
    }

    /** Розбити репліку на рядки до width символів. */
    private static void wrap(String s, int width, List<String> out) {
        StringBuilder cur = new StringBuilder();
        for (String w : s.split(" ")) {
            if (cur.length() + w.length() + 1 > width && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
    }

    void draw(Gui g) {
        List<String> lines = new java.util.ArrayList<>();
        wrap(line, 44, lines);
        int rows = Math.min(6, offers.size());
        float w = 660, h = 70 + lines.size() * 28 + 20 + rows * 70 + 80, x = (Gui.W - w) / 2f, y = Math.max(10, (Gui.H - h) / 2f);
        Gui.rect(0, 0, Gui.W, Gui.H, 0, 0, 0, 0.5f);
        Gui.rect(x, y, w, h, 0.5f, 0.5f, 0.52f, 0.97f);
        Gui.frame(x, y, w, h, 3, 0.15f, 0.15f, 0.15f);
        Gui.text(mob.type.title.toUpperCase(java.util.Locale.ROOT), x + 20, y + 16, 3.2f, 0.15f, 0.15f, 0.15f);
        float ly = y + 62;
        for (String l : lines) { Gui.text(l, x + 20, ly, 2.2f, 0.12f, 0.12f, 0.2f); ly += 28; }
        ly += 12;
        for (int i = 0; i < rows; i++) {
            MobSettings.Trade t = offers.get(i);
            float ry = ly + i * 70;
            Gui.rect(x + 14, ry - 6, w - 28, 66, 0.4f, 0.4f, 0.44f, 0.6f);
            item(t.in, t.inCount, x + 28, ry);
            Gui.text("=>", x + 100, ry + 16, 3.2f, 0.15f, 0.15f, 0.15f);
            item(t.out, t.outCount, x + 160, ry);
            boolean can = Trading.canTrade(inv, world.itemTypes, t);
            Gui.text(InventoryScreen.titleOf(world, t.out), x + 232, ry + 18, 1.9f, 0.1f, 0.1f, 0.1f);
            if (g.button("ОБМІНЯТИ", x + w - 200, ry + 2, 170, 50, false, can)) {
                if (Trading.execute(inv, world.itemTypes, t)) message = "Угоду здійснено";
            }
        }
        if (!message.isEmpty()) Gui.textCentered(message, x + w / 2f, y + h - 74, 2.2f, 0.1f, 0.4f, 0.1f);
        if (offers.isEmpty()) Gui.textCentered("(тут нічим торгувати)", x + w / 2f, ly + 10, 2.2f, 0.3f, 0.3f, 0.3f);
        if (g.button("ЗАКРИТИ", x + w / 2f - 100, y + h - 62, 200, 48)) close = true;
    }

    boolean close;
}
