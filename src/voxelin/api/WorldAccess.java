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
    long seed();
    String name();
}
