package voxelin.client;

import voxelin.client.ui.Gui;
import voxelin.core.*;

import java.util.ArrayList;
import java.util.List;

/** Розмова зі Сторожем Шва: текст і вибір; вибір «Ритуал/Зараження» потребує підтвердження (він остаточний). */
final class KeeperScreen {
    private final World world;
    private final Inventory inv;
    private String pending, message = "";
    boolean close;
    final java.util.function.Consumer<String> say;

    KeeperScreen(World world, Inventory inv, java.util.function.Consumer<String> say) { this.world = world; this.inv = inv; this.say = say; }

    void draw(Gui g) {
        Keeper.Dialog d = Keeper.dialog(world, inv);
        List<String> lines = new ArrayList<>();
        JournalScreen.wrap(d.text(), 50, lines);
        float w = 760, h = 150 + lines.size() * 28 + 80 + (pending != null ? 70 : d.choices().size() * 58), x = (Gui.W - w) / 2f, y = Math.max(8, (Gui.H - h) / 2f);
        Gui.rect(0, 0, Gui.W, Gui.H, 0, 0, 0, 0.55f);
        Gui.rect(x, y, w, h, 0.14f, 0.09f, 0.22f, 0.97f);
        Gui.frame(x, y, w, h, 3, 0.6f, 0.3f, 0.85f);
        Gui.text("СТОРОЖ ШВА", x + 20, y + 16, 3.2f, 0.9f, 0.75f, 1f);
        float ly = y + 70;
        for (String l : lines) { Gui.text(l, x + 20, ly, 2.1f, 0.88f, 0.84f, 0.95f); ly += 28; }
        ly += 16;
        if (pending != null) {
            Gui.text("Вибір остаточний. Підтвердити?", x + 20, ly, 2.4f, 1f, 0.8f, 0.4f);
            if (g.button("ТАК, ПІДТВЕРДЖУЮ", x + 20, ly + 34, 340, 48)) { String m = Keeper.act(world, inv, pending); if (m != null) say.accept(m); pending = null; }
            if (g.button("СКАСУВАТИ", x + 380, ly + 34, 200, 48)) pending = null;
        } else {
            for (Keeper.Choice c : d.choices()) {
                if (g.button(c.label(), x + 20, ly, w - 40, 48, false, c.enabled())) {
                    if (c.action().equals("ritual") || c.action().equals("infection")) pending = c.action();
                    else { String m = Keeper.act(world, inv, c.action()); if (m != null) say.accept(m); }
                }
                ly += 58;
            }
        }
        if (g.button("ЗАКРИТИ", x + w - 220, y + h - 64, 200, 48)) close = true;
    }
}
