package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.world.Block;

public class Inventory {
    private static final int SLOTS = 5;
    private Item[] items;
    private int selectedSlot = 0;

    public Inventory() {
        items = new Item[SLOTS];
        items[0] = new Item(Block.Type.GRASS, 64);
        items[1] = new Item(Block.Type.DIRT, 64);
        items[2] = new Item(Block.Type.STONE, 32);
        items[3] = new Item(Block.Type.WOOD, 16);
    }

    public Item getSelectedItem() { return items[selectedSlot]; }
    public Block.Type getSelectedBlockType() { Item item = getSelectedItem(); return item != null ? item.getBlockType() : null; }
    public void selectSlot(int index) { if (index >= 0 && index < SLOTS) selectedSlot = index; }
    public void nextSlot() { selectedSlot = (selectedSlot + 1) % SLOTS; }
    public void previousSlot() { selectedSlot = (selectedSlot - 1 + SLOTS) % SLOTS; }
    public boolean addItem(Block.Type type, int count) {
        for (int i = 0; i < SLOTS; i++) {
            if (items[i] != null && items[i].getBlockType() == type) { items[i].addCount(count); return true; }
        }
        for (int i = 0; i < SLOTS; i++) {
            if (items[i] == null) { items[i] = new Item(type, count); return true; }
        }
        return false;
    }
    public void removeSelectedItem() { Item item = items[selectedSlot]; if (item != null) { item.removeOne(); if (item.getCount() <= 0) items[selectedSlot] = null; } }
    public Item[] getItems() { return items; }
    public int getSelectedSlot() { return selectedSlot; }

    public static class Item {
        private Block.Type blockType; private int count;
        public Item(Block.Type blockType, int count) { this.blockType = blockType; this.count = count; }
        public Block.Type getBlockType() { return blockType; }
        public int getCount() { return count; }
        public void addCount(int amount) { count += amount; }
        public void removeOne() { count--; }
    }
}
