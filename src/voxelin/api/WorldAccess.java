package voxelin.api;

/** Читання й зміна світу для модів і команд. Координати - у блоках. */
public interface WorldAccess {
    /** Висота світу (блоки 0..height-1). */
    int height();
    /** Блок у точці (повітря - "voxelin:air"); незавантажений чанк - null. */
    BlockRef getBlock(int x, int y, int z);
    /** Поставити блок від імені джерела. false - нема прав, ліміт за тік вичерпано, поза світом або чанк не завантажений. */
    boolean setBlock(Source source, int x, int y, int z, BlockRef block);
    long seed();
    String name();
}
