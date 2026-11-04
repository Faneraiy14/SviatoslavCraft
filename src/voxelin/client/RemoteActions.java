package voxelin.client;

import voxelin.net.NetClient;

import java.util.List;

/** Гість мережевої гри: дії - запити хосту; він перевіряє й розсилає зміну всім (і нам). Дошки й команди в чужому світі - ні. */
public final class RemoteActions implements PlayerActions {
    private final NetClient net;

    public RemoteActions(NetClient net) { this.net = net; }

    @Override public boolean breakBlock(long x, int y, long z, String tool, boolean drops) { net.breakBlock(x, y, z); return true; }
    @Override public boolean placeBlock(long x, int y, long z, String block) { net.placeBlock(x, y, z, block); return true; }
    @Override public boolean breakBoard(long x, int y, long z, int bit, boolean drops) { net.breakBlock(x, y, z); return true; } // гість ламає всю клітинку дошок
    @Override public boolean placeBoard(long x, int y, long z, String block, int axis, int sx, int sy, int sz) { return false; }
    @Override public List<String> command(String line, long[] origin) { return List.of("Команди в чужому світі недоступні."); }
}
