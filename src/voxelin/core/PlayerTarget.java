package voxelin.core;

/** Гравець очима ШІ й спавнера: де він, чи живий і як йому завдати шкоди. Клієнт виставляє щотіку (World.setPlayerTarget). */
public interface PlayerTarget {
    /** Центр тіла (блоки). */
    Pos body();
    /** Очі (для променя). */
    boolean alive();
    /** У творчому режимі істоти не нападають. */
    boolean creative();
    /** Перенести гравця в точку (команда /tp): ноги на блоці (x,y,z). */
    default void teleport(long x, int y, long z) {}
    /** Удар: шкода й поштовх (kx,kz - напрям від нападника, нормований). */
    void hurt(double damage, double kx, double kz);
}
