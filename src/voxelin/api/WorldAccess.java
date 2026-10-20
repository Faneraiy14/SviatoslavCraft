package voxelin.api;

/** Читання й зміна світу для модів і команд. X і Z - long (світ практично безмежний), Y - int (0..height-1). Координати - у блоках. */
public interface WorldAccess {
    /** Висота світу (блоки 0..height-1). */
    int height();
    /** Блок у точці (повітря - "voxelin:air"); незавантажений чанк - null. */
    BlockRef getBlock(long x, int y, long z);
    /** Поставити блок від імені джерела. false - нема прав, ліміт за тік вичерпано, поза світом або чанк не завантажений. */
    boolean setBlock(Source source, long x, int y, long z, BlockRef block);
    /** Виконати багато змін як одну: світло перераховується один раз у кінці (замість окремо на кожен блок). Типово - просто виконати. */
    default void batch(Runnable changes) { changes.run(); }
    /** Ігровий час у тіках (20/с) від початку світу. */
    default long time() { return 0; }
    default void setTime(long ticks) {}
    /** Довжина доби в тіках. */
    default long dayTicks() { return 24000; }
    /** Здоров'я/голод гравця-джерела; null - у цього джерела їх нема. */
    default Vitality vitality(Source source) { return null; }
    long seed();
    String name();
}
