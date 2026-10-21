package voxelin.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Діалог Сторожа Шва після смерті дракона: стан залежить від вибору (eggChoice) і ходу ритуалу. Гравець бачить наслідки ДО вибору (принцип дизайну);
 * вибір «Ритуал» чи «Зараження» робиться раз і остаточно. Чисті функції над World (+ інвентар гравця).
 */
public final class Keeper {
    private Keeper() {}

    public record Choice(String action, String label, boolean enabled) {}
    public record Dialog(String text, List<Choice> choices) {}

    public static final String ANCHOR = "voxelin:seam_anchor";

    public static Dialog dialog(World w, Inventory inv) {
        WorldInfo i = w.info;
        List<Choice> c = new ArrayList<>();
        if (!i.dragonDead) return new Dialog("...", c);
        switch (i.eggChoice) {
            case NONE:
                c.add(new Choice("ritual", "Ритуал: залишити яйце в Краї", true));
                c.add(new Choice("infection", "Зараження: забрати яйце", true));
                return new Dialog("Дракон упав. Яйце - насіння міжсвіття, замок на Шві, що тримав світи. Ти можеш обрати. "
                        + "РИТУАЛ: залишити яйце тут, зібрати три якорі Шву й провести обряд. Край зміниться, і з нього вийдуть двоє, що тримали Дракона: Ткач і Розплітач. Світ лишиться цілим, але бої будуть найважчими. "
                        + "ЗАРАЖЕННЯ: забрати яйце й поставити, де хочеш. За тридцять днів Шов прорветься, і міжсвіт проросте крізь світ. Це можна зупинити: вилікувати, спалити чи приручити, але ціна - час, ресурси й шрами. "
                        + "Обидва шляхи чесні. Вибір остаточний.", c);
            case RITUAL:
                if (i.ritualStage == 0) {
                    boolean has = inv != null && Trading.count(inv, w.itemTypes, ANCHOR) >= 3;
                    c.add(new Choice("give_anchors", "Віддати три якорі Шву", has));
                    return new Dialog("Ти обрав Ритуал. Принеси мені три якорі Шву: кожен - два уламки лімініту, обсидіан і алмаз. Коли віддаси їх, Край відкриє тих, хто тримав Дракона. Здолай обох - і Шов запечатається заново.", c);
                }
                if (i.ritualStage == 1) return new Dialog("Ткач і Розплітач у Краї. Тримай їх у рівновазі: коли падає один, другий лютує. Здолай обох.", c);
                return new Dialog("Ритуал звершено. Шов запечатано. Дякую, мандрівнику. Край тепер твій, а світ - цілий.", c);
            default:
                return new Dialog("Ти обрав Зараження. Забери яйце й постав його там, де вирішиш: воно пустить корені, і почнеться тридцять днів. Тиха пора - перші десять, активна - наступні десять, а потім криза. "
                        + "Вилікувати: зілля шву. Спалити: швидко, але на місці лишиться кратер назавжди. Приручити: вісім уламків лімініту - і розростання зупиниться. Поки воно активне, ядро не зламати.", c);
        }
    }

    /** Виконати дію з діалогу. Повертає повідомлення або null, якщо дія недоступна. */
    public static String act(World w, Inventory inv, String action) {
        WorldInfo i = w.info;
        if (!i.dragonDead) return null;
        switch (action) {
            case "ritual":
                if (i.eggChoice != WorldInfo.EggChoice.NONE) return null;
                i.eggChoice = WorldInfo.EggChoice.RITUAL;
                w.unlockRecord("voxelin:keeper"); w.unlockRecord("voxelin:choice_ritual");
                return "Ти обрав Ритуал.";
            case "infection":
                if (i.eggChoice != WorldInfo.EggChoice.NONE) return null;
                i.eggChoice = WorldInfo.EggChoice.INFECTION;
                w.unlockRecord("voxelin:keeper"); w.unlockRecord("voxelin:choice_infection");
                return "Ти обрав Зараження. Забери яйце.";
            case "give_anchors":
                if (i.eggChoice != WorldInfo.EggChoice.RITUAL || i.ritualStage != 0 || inv == null || Trading.count(inv, w.itemTypes, ANCHOR) < 3) return null;
                int left = 3;
                for (int k = 0; k < Inventory.SIZE && left > 0; k++) {
                    Inventory.Stack s = inv.get(k);
                    if (s == null || !s.item.equals(ANCHOR)) continue;
                    int m = Math.min(left, s.count); s.count -= m; left -= m;
                    if (s.count <= 0) inv.set(k, null);
                }
                i.ritualStage = 1;
                w.unlockRecord("voxelin:ritual_twins");
                w.spawnRitualBossesIfNeeded();
                return "Якорі прийнято. Край розступається: Ткач і Розплітач прокинулись.";
            default:
                return null;
        }
    }
}
