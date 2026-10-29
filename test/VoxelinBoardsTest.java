import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;

/** Справжні дошки: до 16 у блоці (X, Z, стоячи - упереміш, без перетинів), ламання по одній, фізика (стоять, сходинка), приціл, збереження; стіл. */
public class VoxelinBoardsTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final Source P = Sources.player("P");

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-boards").toFile(), "saves"), null);
        masks(); place(); physics(); aim(); save();
        System.out.println("VoxelinBoardsTest: " + n + " перевірок OK");
    }

    static World open(String name) throws Exception {
        g.createWorld(name, 3, WorldInfo.Type.FLAT);
        World w = g.openWorld(name);
        w.naturalSpawning = false;
        for (int i = 0; i < 900 && w.chunk(0, 0) == null; i++) { w.update(0, 0, 2); Thread.sleep(10); }
        return w;
    }

    static int surface(World w, long x, long z) { for (int y = 200; y > 0; y--) if (w.blockId(x, y, z) > 0) return y; return 0; }

    static void masks() {
        check(Long.bitCount(Boards.occupancy(1L << Boards.bit(Boards.X, 0, 0))) == 4, "дошка займає 4 клітинки сітки");
        long all = 0;
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) all |= 1L << Boards.bit(Boards.Z, a, b);
        check(Boards.occupancy(all) == -1L, "16 дошок уздовж Z - повний блок");
        check(Boards.boxes(all).length == 16 * 6, "16 коробок");
        double[] bx = Boards.boxes(1L << Boards.bit(Boards.Y, 1, 2));
        check(bx[0] == 0.25 && bx[1] == 0 && bx[2] == 0.5 && bx[4] == 1, "стояча дошка: x 1/4..2/4, на всю висоту, z 2/4..3/4");
        check(Boards.partAt(1L << Boards.bit(Boards.X, 0, 3), 0.6, 0.1, 0.9) == Boards.bit(Boards.X, 0, 3), "влучання в дошку");
        check(Boards.partAt(1L << Boards.bit(Boards.X, 0, 3), 0.6, 0.6, 0.9) == -1, "повз дошку");
    }

    static void place() throws Exception {
        World w = open("p");
        BlockType oak = w.blocks.byName("voxelin:oak_boards"), birch = w.blocks.byName("voxelin:birch_boards");
        check(oak != null && w.boards.isBoards(oak) && w.itemTypes.get("voxelin:oak_board").placesBlock.equals(oak.name), "вміст: клітинка дощок і предмет дошки");
        int y = surface(w, 0, 0) + 1;
        check(w.boards.place(P, 0, y, 0, oak, Boards.X, 2, 0, 0), "дошка вздовж X на підлогу");
        check(w.blockId(0, y, 0) == oak.id && Long.bitCount(w.boards.mask(0, y, 0)) == 1, "клітинка стала дошками");
        check(!w.boards.place(P, 0, y, 0, oak, Boards.Z, 0, 0, 0), "уздовж Z через ту саму клітинку - перетин");
        check(w.boards.place(P, 0, y, 0, oak, Boards.Z, 0, 1, 0), "уздовж Z поверх - можна (напрямки упереміш)");
        check(w.boards.place(P, 0, y, 0, oak, Boards.Y, 3, 2, 3), "стояча в кутку - теж");
        check(!w.boards.place(P, 0, y, 0, oak, Boards.Y, 0, 3, 0), "стояча крізь дошку Z - ні");
        check(!w.boards.place(P, 0, y, 0, birch, Boards.X, 0, 3, 0), "інша порода в ту саму клітинку - ні");
        check(!w.boards.place(P, 0, y - 1, 0, oak, Boards.X, 0, 0, 0), "у суцільний блок - ні");
        int ox = 3;
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) check(w.boards.place(P, ox, y, 0, oak, Boards.X, 0, a, b), "16 дошок у блок");
        check(!w.boards.place(P, ox, y, 0, oak, Boards.Y, 0, 0, 0), "17-та не влазить");
        // ламання по одній: остання - клітинка порожніє
        long m = w.boards.mask(0, y, 0);
        check(w.harvestBoard(P, 0, y, 0, Boards.bit(Boards.X, 0, 0), true) && w.boards.mask(0, y, 0) == (m & ~(1L << Boards.bit(Boards.X, 0, 0))), "зламано одну дошку");
        check(count(w, "voxelin:oak_board") == 1, "випала одна дошка");
        w.harvestBoard(P, 0, y, 0, Boards.bit(Boards.Z, 1, 0), true);
        w.harvestBoard(P, 0, y, 0, Boards.bit(Boards.Y, 3, 3), true);
        check(w.blockId(0, y, 0) == 0 && w.boards.mask(0, y, 0) == 0, "остання дошка - клітинка стала повітрям");
        // уся клітинка разом (вибух, команда, старий шлях) - випадають усі
        check(w.harvest(P, ox, y, 0, null, true) && count(w, "voxelin:oak_board") == 3 + 16, "уся клітинка - 16 дошок: " + count(w, "voxelin:oak_board"));
        check(w.boards.mask(ox, y, 0) == 0, "маску прибрано");
        // заміна командою теж чистить маску
        w.boards.place(P, 6, y, 0, oak, Boards.X, 0, 0, 0);
        w.setBlock(P, 6, y, 0, w.blocks.byName("voxelin:stone"));
        check(w.boards.mask(6, y, 0) == 0, "замінено блоком - маски нема");
        // стіл
        BlockType bench = w.blocks.byName("voxelin:workbench");
        check("table".equals(bench.shape) && !bench.opaque && w.boxes(0, 0, 0) == null, "верстак - стіл (фігурний, не затуляє сусідів)");
        w.setBlock(P, 9, y, 0, bench);
        check(w.boxes(9, y, 0).length == 5 * 6, "стіл: стільниця + 4 ніжки");
    }

    static int count(World w, String item) { int c = 0; for (ItemEntity e : w.itemEntities()) if (e.item.equals(item)) c += e.count; return c; }

    static void physics() throws Exception {
        World w = open("f");
        BlockType oak = w.blocks.byName("voxelin:oak_boards");
        int y = surface(w, 0, 0) + 1;
        // настил із дощок на рівні y (нижній шар) на 3x3 клітинки
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int b = 0; b < 4; b++) w.boards.place(P, x, y, z, oak, Boards.X, 0, 0, b);
        PlayerEntity p = new PlayerEntity(w.solidView, new Pos(0, 0, 0.5, y + 2, 0.5));
        p.teleport(new Pos(0, 0, 0.5, y + 2, 0.5));
        for (int i = 0; i < 60; i++) p.tick(0.05, false, false, false, false, false, false, false);
        double feet = p.pos.y - PlayerEntity.EYE;
        check(p.onGround() && Math.abs(feet - (y + 0.25)) < 0.05, "стоїть на настилі (ноги на y+1/4): " + feet);
        // крок на дошку без стрибка: гравець на землі в x=3, дошка-настил в x=1..-1; іде на -X
        p.teleport(new Pos(0, 0, 3.5, y + PlayerEntity.EYE + 0.05, 0.5));
        p.yaw = -90; // дивиться на -X
        for (int i = 0; i < 20; i++) p.tick(0.05, false, false, false, false, false, false, false);
        for (int i = 0; i < 40; i++) p.tick(0.05, true, false, false, false, false, false, false);
        double px = p.pos.relX(0);
        feet = p.pos.y - PlayerEntity.EYE;
        check(px < 1.5 && feet > y + 0.2, "зайшов на настил без стрибка: x=" + px + " ноги=" + feet);
    }

    static void aim() throws Exception {
        World w = open("a");
        BlockType oak = w.blocks.byName("voxelin:oak_boards");
        int y = surface(w, 0, 0) + 1;
        w.boards.place(P, 0, y, 0, oak, Boards.X, 0, 0, 2); // одна дошка на підлозі, z 0.5..0.75
        Raycast.Solid view = new Raycast.Solid() {
            public boolean at(long x, int yy, long z) { return w.blockId(x, yy, z) > 0; }
            public double[] boxes(long x, int yy, long z) { return w.boxes(x, yy, z); }
        };
        Raycast.Hit h = Raycast.cast(new Pos(0, 0, 0.5, y + 1.5, 0.6), 0, 89.9f, 5, view);
        check(h != null && h.x() == 0 && h.y() == y && h.face() == 2 && Math.abs(h.fy() - 0.25) < 1e-6, "промінь згори влучає у верх дошки");
        check(Boards.partAt(w.boards.mask(0, y, 0), h.fx(), h.fy() - 0.01, h.fz()) == Boards.bit(Boards.X, 0, 2), "і саме в цю дошку");
        h = Raycast.cast(new Pos(0, 0, 0.5, y + 1.5, 0.2), 0, 89.9f, 5, view);
        check(h != null && h.y() == y - 1, "поруч із дошкою промінь іде до землі");
    }

    static void save() throws Exception {
        World w = open("s");
        BlockType oak = w.blocks.byName("voxelin:spruce_boards");
        int y = surface(w, 0, 0) + 1;
        w.boards.place(P, 5, y, -7, oak, Boards.Y, 1, 0, 1);
        w.boards.place(P, 5, y, -7, oak, Boards.Z, 3, 2, 0);
        long m = w.boards.mask(5, y, -7);
        w.close();
        World w2 = g.openWorld("s");
        for (int i = 0; i < 900 && w2.chunk(0, -1) == null; i++) { w2.update(0, -1, 2); Thread.sleep(10); }
        check(w2.boards.mask(5, y, -7) == m && w2.blockId(5, y, -7) == oak.id, "дошки збереглись");
    }
}
