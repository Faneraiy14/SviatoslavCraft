package com.sviatoslav.craft.game;

/**
 * Перехідник для старих лаунчерів: FL Launcher досі запускає саме це ім'я класу
 * (java -cp out:lib/* com.sviatoslav.craft.game.SviatoslavCraft). Уся гра тепер у voxelin.client.Voxelin.
 * Прибрати, коли лаунчер навчиться читати launch.json із релізу.
 */
public final class SviatoslavCraft {
    private SviatoslavCraft() {}
    public static void main(String[] args) { voxelin.client.Voxelin.main(args); }
}
