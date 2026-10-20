package voxelin.api;

/**
 * Хто виконує дію: гравець, мод, консоль або (пізніше) скрипт гравця. Усі зміни світу йдуть з джерелом, щоб ядро могло
 * перевірити права й обмежити кількість дій за тік (захист від нескінченного циклу в майбутніх скриптах NyxilumLang).
 */
public interface Source {
    enum Kind { PLAYER, MOD, CONSOLE, SCRIPT }

    String name();
    Kind kind();
    /** 0 - звичайний гравець, 2 - команди будування (/fill, /setblock), 4 - усе. */
    int permission();
    /** Скільки змін блоків дозволено за один тік (Integer.MAX_VALUE - без ліміту). */
    int blockBudgetPerTick();
}
