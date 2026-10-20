package voxelin.core;

import java.util.List;

/** Стан блока в світі (піч, скриня): зберігається разом зі світом, тікає, при зламі блока його вміст випадає. Позиція незмінна. */
public abstract class BlockEntity {
    public final long x, z;
    public final int y;

    protected BlockEntity(long x, int y, long z) { this.x = x; this.y = y; this.z = z; }

    /** Вид (збігається з BlockSettings.interact блока). */
    public abstract String kind();
    /** Один тік гри (лише якщо чанк завантажено). */
    public abstract void tick(World world);
    /** Текст без пробілів і переходів рядка для збереження. */
    public abstract String encode();
    /** Прочитати збережене; биті дані не повинні кидати винятків назовні (кидайте IllegalArgumentException - світ пропустить запис). */
    public abstract void decode(String data);
    /** Предмети, що випадають при зламі. */
    public abstract List<Inventory.Stack> contents();
}
