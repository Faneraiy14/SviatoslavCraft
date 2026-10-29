package voxelin.client;

import voxelin.client.ui.Gui;
import voxelin.core.*;

import java.util.ArrayList;
import java.util.List;

/** Фінал: текст кінцівки (за гілкою й способом зупинки зараження), підсумок і титри, що повільно пливуть угору. Після - гра триває. */
final class CreditsScreen {
    private final List<String> lines = new ArrayList<>();
    private long openedAt = System.currentTimeMillis();
    boolean close, toMenu;

    CreditsScreen(World world) {
        WorldInfo i = world.info;
        String title, text;
        if (i.goals.contains(Progress.RITUAL_END)) {
            title = "КІНЦІВКА: РИТУАЛ";
            text = "Ткач і Розплітач упали разом, як і жили - один зшивав, другий розпускав. Шов запечатано заново, і цього разу не замком, а рівновагою.\n"
                    + "Звичайний світ так і не дізнався, що був на краю. Сторож уперше за довгий час сів біля яйця й заплющив очі.";
        } else {
            title = "КІНЦІВКА: ЗАРАЖЕННЯ";
            text = switch (i.infState) {
                case CURED -> "Зілля Шву дійшло до самого ядра. Жили висохли, наріст розсипався землею, а яйце знову стало просто яйцем.\n"
                        + "Світ заживає повільно, але заживає. Міжсвіття лишилось відчиненим - для тих, хто знає дорогу.";
                case BURNED -> "Вогонь Шву випалив ядро разом із землею навколо. Зараження зникло за одну ніч.\n"
                        + "Кратер лишиться назавжди - як нагадування, що не кожну рану можна загоїти, але кожну можна зупинити.";
                default -> "Вісім уламків лімініту - і ядро затихло, наче звір, якого нагодували. Зараження більше не росте.\n"
                        + "Воно тепер твоє: сила, що могла з'їсти світ, сидить на ланцюгу. Але ланцюг доведеться пильнувати.";
            };
        }
        lines.add("#" + title);
        lines.add("");
        List<String> wrapped = new ArrayList<>();
        JournalScreen.wrap(text, 58, wrapped);
        lines.addAll(wrapped);
        lines.add("");
        lines.add("Пройдено за " + world.dayNumber() + " " + days(world.dayNumber()) + ".  Записів у журналі: " + i.records.size() + ".");
        lines.add("");
        lines.add("Світ не закінчується. Він тепер пам'ятає тебе.");
        lines.add(""); lines.add(""); lines.add("");
        lines.add("#VOXELIN");
        lines.add("");
        lines.add("Ідея й світ - Святослав");
        lines.add("Співавтор ідей - Нікіта");
        lines.add("Код - Святослав разом із Claude");
        lines.add("");
        lines.add("Дякуємо, що дійшов до кінця.");
    }

    /** Для знімків: ніби титри вже йдуть стільки секунд. */
    void skip(float seconds) { openedAt -= (long) (seconds * 1000); }

    private static String days(long n) {
        long a = n % 100, b = n % 10;
        if (a >= 11 && a <= 14) return "днів";
        return b == 1 ? "день" : (b >= 2 && b <= 4 ? "дні" : "днів");
    }

    void draw(Gui g) {
        Gui.rect(0, 0, Gui.W, Gui.H, 0.02f, 0.01f, 0.05f, 0.94f);
        float t = (System.currentTimeMillis() - openedAt) / 1000f;
        float total = 0;
        for (String l : lines) total += l.startsWith("#") ? 56 : 30;
        float y = Math.max(Math.min(90, Gui.H - 130 - total), Gui.H - 40 - t * 38); // титри підіймаються, доки останній рядок не стане над кнопками
        for (String l : lines) {
            if (l.startsWith("#")) { Gui.textCentered(l.substring(1), Gui.W / 2f, y, 4.2f, 1f, 0.85f, 0.4f); y += 56; }
            else { Gui.textCentered(l, Gui.W / 2f, y, 2.2f, 0.88f, 0.86f, 0.95f); y += 30; }
        }
        float by = Gui.H - 86;
        Gui.rect(0, by - 14, Gui.W, 100, 0.02f, 0.01f, 0.05f, 1f);
        if (g.button("ГРАТИ ДАЛІ", Gui.W / 2f - 430, by, 420, 56)) close = true;
        if (g.button("ЗБЕРЕГТИ Й ВИЙТИ В МЕНЮ", Gui.W / 2f + 10, by, 420, 56)) { close = true; toMenu = true; }
    }
}
