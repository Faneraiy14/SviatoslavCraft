package voxelin.content;

/** Ванільні шаблони структур (формат .vxs - див. core/gen/StructureTemplate). Правляться як звичайний текст. */
public final class Structures {
    private Structures() {}

    public static final String RUINS = """
place: surface
spacing: 128
chance: 0.45
sink: 1
legend:
  B = stone_bricks
  C = cobblestone
  G = gravel
  K = chest loot=ruins
  _ = air
layers:
CCCCCCCCC
CBBBBBBGC
CBBBBBBBC
CBGBBBBBC
CGBBBBBBC
CBBBBBBBC
CGBBGBBGC
CBBBBBBBC
CCCCCCCCC
---
BBBBBBCCB
B_______C
B_____K_C
B_______B
B_______B
B_______B
B_______B
C_______B
BBC___BBC
---
.BCCBBBCB
C_______B
B_______B
._______C
B_______B
B_______B
C_______B
B_______B
CBB___.CB
---
BBB.BBC..
._______C
._______.
C_______.
._______.
B_______B
B_______.
B_______B
BB.___B..
---
B.CBB....
B.......B
.........
.........
.........
.........
........B
.........
.........
---
.B...C...
.........
........B
B........
.........
B........
.........
.........
B.....B..
""";

    public static final String DUNGEON = """
place: underground
spacing: 80
chance: 0.7
depth: 14 42
legend:
  B = stone_bricks
  C = cobblestone
  K = chest loot=dungeon
  _ = air
layers:
CCCCCCCCC
CCCCCCCCC
CCCCCCCCC
CCCCCCCCC
CCCCCCCCC
CCCCCCCCC
CCCCCCCCC
CCCCCCCCC
CCCCCCCCC
---
BBCBCBBCB
BK______B
B_______B
B_______B
C_______B
C_______B
C_______C
C______KB
CCBCBBBBB
---
BBBBBBCCC
C_______B
B_______B
B_______B
B_______B
B_______C
B_______B
C_______B
BCBCBBBBB
---
BBBBBBBBB
B_______C
B_______B
B_______B
B_______B
C_______C
C_______C
B_______B
BBBCBCBBB
---
BBBBBBBBB
BBBBBBBBB
BBBBBBBBB
BBBBBBBBB
BBBBBBBBB
BBBBBBBBB
BBBBBBBBB
BBBBBBBBB
BBBBBBBBB
""";

    public static final String MINE = """
place: underground
spacing: 100
chance: 0.55
depth: 22 46
legend:
  L = oak_log
  P = oak_planks
  K = chest loot=mine
  _ = air
layers:
.........................
.........................
.........................
---
L___L___L___L___L___L___L
______________________K__
L___L___L___L___L___L___L
---
L___L___L___L___L___L___L
_________________________
L___L___L___L___L___L___L
---
P___P___P___P___P___P___P
P___P___P___P___P___P___P
P___P___P___P___P___P___P
---
.........................
.........................
.........................
""";
}
