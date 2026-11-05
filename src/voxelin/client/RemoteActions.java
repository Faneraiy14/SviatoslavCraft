package voxelin.client;

import voxelin.net.NetClient;

import java.util.List;

/** Гість мережевої гри: дії - запити хосту; він перевіряє й розсилає зміну всім (і нам). Дошки й команди в чужому світі - ні. Дроп (у виживанні) хост надсилає окремо. */
public final class RemoteActions implements PlayerActions {
    private final NetClient net;

    public RemoteActions(NetClient net) { this.net = net; }

    @Override public boolean breakBlock(long x, int y, long z, String tool, boolean drops) { net.breakBlock(x, y, z, tool); return true; }
    @Override public boolean placeBlock(long x, int y, long z, String block) { net.placeBlock(x, y, z, block); return true; }
    @Override public boolean breakBoard(long x, int y, long z, int bit, boolean drops) { net.breakBlock(x, y, z, null); return true; } // гість ламає всю клітинку дошок
    @Override public boolean placeBoard(long x, int y, long z, String block, int axis, int sx, int sy, int sz) { return false; }
    /** Команду виконує хост (лише команди модів для всіх; хост 1.2 відповість, що недоступно) - відповідь прийде в чат. */
    @Override public List<String> command(String line, long[] origin) { net.chat(line); return List.of(); }
}
