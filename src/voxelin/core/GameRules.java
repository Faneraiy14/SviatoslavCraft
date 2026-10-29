package voxelin.core;

import java.util.List;

/**
 * Правила гри (як gamerule у Minecraft). Задаються на екрані «Новий світ»; у створеному світі змінюються лише командою /gamerule,
 * а вона, як і решта команд-читів, доступна тільки якщо при створенні ввімкнено «Чити» (WorldInfo.cheats).
 */
public final class GameRules {
    private GameRules() {}

    public record Rule(String id, String title, boolean def) {}

    public static final String KEEP_INVENTORY = "keepInventory", DAYLIGHT = "doDaylightCycle", MOB_SPAWNING = "doMobSpawning",
            REGENERATION = "naturalRegeneration", FALL_DAMAGE = "fallDamage", MOB_LOOT = "doMobLoot";

    public static final List<Rule> ALL = List.of(
            new Rule(KEEP_INVENTORY, "Зберігати речі після смерті", false),
            new Rule(DAYLIGHT, "Зміна дня й ночі", true),
            new Rule(MOB_SPAWNING, "Істоти з'являються самі", true),
            new Rule(REGENERATION, "Здоров'я відновлюється від ситості", true),
            new Rule(FALL_DAMAGE, "Шкода від падіння", true),
            new Rule(MOB_LOOT, "Здобич з істот", true));

    public static Rule find(String id) {
        for (Rule r : ALL) if (r.id.equalsIgnoreCase(id)) return r;
        return null;
    }
}
