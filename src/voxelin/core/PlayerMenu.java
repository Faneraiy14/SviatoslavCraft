package voxelin.core;

/** Меню звичайного інвентарю гравця (поки без сітки крафту; вона додається в S3b). */
public final class PlayerMenu extends Menu {
    public PlayerMenu(ItemRegistry items, Inventory inv) { super(items); addInventory(inv); }
}
