package voxelin.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * «Шлях» - сюжетні цілі від першого дерева до фіналу. Гравець завжди бачить наступний крок (HUD і журнал), а не губиться після верстака.
 * Досягнуті цілі пишуться у WorldInfo.goals (зберігаються, переносяться між вимірами) і не «відкочуються», коли предмет витрачено.
 * Пізніша досягнута ціль закриває всі ранніші (алмаз зі скрині - теж «знайшов алмази», навіть без залізної кирки).
 * Цілі гілок (ритуал / зараження) показуються лише на обраній гілці. Чисті функції над World + інвентар гравця.
 */
public final class Progress {
    private Progress() {}

    public enum Branch { ANY, RITUAL, INFECTION }

    public record Goal(String id, String title, String hint, Branch branch, BiPredicate<World, Inventory> done) {}

    /** Фінальна ціль: коли вона закривається вперше - гру пройдено (клієнт показує титри). */
    public static final String RITUAL_END = "ritual_end", INFECTION_END = "infection_end";

    private static final List<Goal> GOALS = new ArrayList<>();
    private static void g(String id, String title, String hint, BiPredicate<World, Inventory> done) { GOALS.add(new Goal(id, title, hint, Branch.ANY, done)); }
    private static void g(Branch b, String id, String title, String hint, BiPredicate<World, Inventory> done) { GOALS.add(new Goal(id, title, hint, b, done)); }

    static {
        g("wood", "Добудь дерево", "Тримай ЛКМ на стовбурі дерева. З колод - дошки (E, крафт 2x2).",
                (w, inv) -> hasAny(w, inv, "voxelin:oak_log", "voxelin:birch_log", "voxelin:spruce_log", "voxelin:pale_log", "voxelin:oak_planks", "voxelin:birch_planks", "voxelin:spruce_planks"));
        g("workbench", "Зроби верстак", "Чотири дошки квадратом. Постав верстак і натисни на нього ПКМ - там крафт 3x3.",
                (w, inv) -> hasAny(w, inv, "voxelin:workbench"));
        g("pickaxe", "Зроби кирку", "На верстаку: дві дошки зверху, під правою - дві палиці (палиці - з дошок).", (w, inv) -> pickTier(w, inv) >= 1);
        g("stone", "Кам'яна кирка", "Дерев'яною киркою добудь бруківку і зроби кам'яну кирку - нею береться залізо.", (w, inv) -> pickTier(w, inv) >= 2);
        g("iron", "Виплав залізо", "Залізна руда - бежеві цятки в камені. Зроби піч (бруківка підковою, вугілля в центрі) і переплав сире залізо на вугіллі.",
                (w, inv) -> hasAny(w, inv, "voxelin:iron_ingot"));
        g("iron_pickaxe", "Залізна кирка", "Залізна кирка бере золото й алмази.", (w, inv) -> pickTier(w, inv) >= 3);
        g("diamond", "Знайди алмази", "Алмази - глибоко, ближче до дна світу. Ще їх буває в скринях руїн, храмів і фортець.", (w, inv) -> hasAny(w, inv, "voxelin:diamond"));
        g("diamond_pickaxe", "Алмазна кирка", "Лише алмазна кирка бере лімінітову руду.", (w, inv) -> pickTier(w, inv) >= 4);
        g("liminite", "Добудь лімініт", "Лімінітова руда світиться фіолетовим глибоко під землею. З неї - уламки лімініту.", (w, inv) -> hasAny(w, inv, "voxelin:liminite_shard"));
        g("armor", "Повна алмазна броня", "Брама Шву відчиняється лише тому, хто вдягнений: шолом, нагрудник, поножі й черевики - алмазні й надіті.",
                (w, inv) -> inv != null && inv.fullArmor(w.itemTypes, EndPortal.REQUIRED_TIER));
        g("eyes", "Дванадцять очей Енду", "Око Енду: уламок лімініту, алмаз і золотий злиток. Потрібно 12.",
                (w, inv) -> inv != null && Trading.count(inv, w.itemTypes, EndPortal.EYE) >= 12);
        g("gate", "Відчини Браму Шву", "Брама - під землею. ПКМ Оком Енду в повітря: око покаже, куди йти. Встав 12 очей у рамки в повній алмазній броні.",
                (w, inv) -> w.info.records.contains("voxelin:gate"));
        g("end", "Увійди в Край", "Стрибни в портал Брами.", (w, inv) -> w.info.records.contains("voxelin:end_arrival"));
        g("dragon", "Здолай Дракона Краю", "Дракон кружляє над островом і пікірує. Бий мечем, коли він пікірує; май запас їжі.",
                (w, inv) -> w.info.dragonDead || w.info.records.contains("voxelin:dragon"));
        g("choice", "Поговори зі Сторожем Шва", "Він з'явиться біля яйця. ПКМ по ньому - і обери: Ритуал чи Зараження. Наслідки він назве наперед.",
                (w, inv) -> w.info.eggChoice != WorldInfo.EggChoice.NONE);
        // --- гілка Ритуалу
        g(Branch.RITUAL, "anchors", "Три якорі Шву", "Якір: два уламки лімініту, обсидіан і алмаз. Обсидіан - у вежах Краю, береться лише алмазною киркою.",
                (w, inv) -> w.info.ritualStage >= 1 || inv != null && Trading.count(inv, w.itemTypes, Keeper.ANCHOR) >= 3);
        g(Branch.RITUAL, "ritual", "Віддай якорі Сторожу", "ПКМ по Сторожу в Краї - «Віддати три якорі Шву».", (w, inv) -> w.info.ritualStage >= 1);
        g(Branch.RITUAL, RITUAL_END, "Здолай Ткача й Розплітача", "Обидва в Краї. Коли падає один, другий лютує - знижуй їм здоров'я порівну.", (w, inv) -> w.info.ritualStage >= 2);
        // --- гілка Зараження
        g(Branch.INFECTION, "egg", "Постав яйце", "Забери яйце з Краю (ЛКМ) і постав у звичайному світі. Місце обирай з розумом: почнеться відлік 30 днів.",
                (w, inv) -> w.info.infState != WorldInfo.InfState.NONE);
        g(Branch.INFECTION, INFECTION_END, "Зупини зараження", "ПКМ по ядру: Зілля Шву - вилікувати (квітка з міжсвіття, туди веде Ключ Порогу), Вогонь Шву - спалити, 8 уламків лімініту - приручити.",
                (w, inv) -> w.info.infState == WorldInfo.InfState.CURED || w.info.infState == WorldInfo.InfState.BURNED || w.info.infState == WorldInfo.InfState.TAMED);
    }

    public static List<Goal> all() { return GOALS; }

    /** Цілі на шляху цього світу: загальні + обраної гілки (до вибору - лише загальні). */
    public static List<Goal> path(WorldInfo i) {
        List<Goal> r = new ArrayList<>();
        for (Goal g : GOALS) if (applies(g, i)) r.add(g);
        return r;
    }

    private static boolean applies(Goal g, WorldInfo i) {
        return switch (g.branch) {
            case ANY -> true;
            case RITUAL -> i.eggChoice == WorldInfo.EggChoice.RITUAL || i.goals.contains(g.id);
            case INFECTION -> i.eggChoice == WorldInfo.EggChoice.INFECTION || i.goals.contains(g.id);
        };
    }

    /** Наступна недосягнута ціль або null - гру пройдено (чи шлях ще не визначено: такого не буває, бо «choice» лишається до вибору). */
    public static Goal current(WorldInfo i) {
        for (Goal g : path(i)) if (!i.goals.contains(g.id)) return g;
        return null;
    }

    public static boolean finished(WorldInfo i) { return i.goals.contains(RITUAL_END) || i.goals.contains(INFECTION_END); }

    /**
     * Перевірити цілі (клієнт кличе раз на секунду). Повертає щойно досягнуті цілі (у порядку шляху);
     * пізніша досягнута закриває ранніші тієї ж гілки й загальні.
     */
    public static List<Goal> update(World w, Inventory inv) {
        WorldInfo i = w.info;
        List<Goal> path = path(i), fresh = new ArrayList<>();
        int last = -1;
        for (int k = path.size() - 1; k >= 0; k--) {
            Goal g = path.get(k);
            if (i.goals.contains(g.id) || g.done.test(w, inv)) { last = k; break; }
        }
        for (int k = 0; k <= last; k++) if (i.goals.add(path.get(k).id)) fresh.add(path.get(k));
        return fresh;
    }

    private static boolean hasAny(World w, Inventory inv, String... items) {
        if (inv == null) return false;
        for (int k = 0; k < Inventory.SIZE; k++) {
            Inventory.Stack s = inv.get(k);
            if (s != null) for (String it : items) if (s.item.equals(it)) return true;
        }
        return false;
    }

    /** Найвищий рівень кирки в інвентарі (0 - нема). */
    private static int pickTier(World w, Inventory inv) {
        if (inv == null) return 0;
        int t = 0;
        for (int k = 0; k < Inventory.SIZE; k++) {
            Inventory.Stack s = inv.get(k);
            ItemType it = s == null ? null : w.itemTypes.get(s.item);
            if (it != null && it.tool == voxelin.api.ToolKind.PICKAXE) t = Math.max(t, it.tier);
        }
        return t;
    }
}
