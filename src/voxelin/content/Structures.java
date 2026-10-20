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

    // --- частини села (розкладку робить StructurePlacer: колодязь у центрі, будинки по кільцю, двері до центру)
    public static final String VILLAGE_HOUSE_SMALL = """
village: house 3
sink: 1
legend:
  C = cobblestone
  P = oak_planks
  L = oak_log
  G = glass
  R = oak_planks
  M = lamp
  K = chest loot=village
  V = mob villager_farmer
  _ = air
layers:
CCCCCCC
CPPPPPC
CPPPPPC
CPPPPPC
CPPPPPC
CPPPPPC
CCCCCCC
---
LPPPPPL
PK____P
P__V__P
P_____P
P_____P
P_____P
LPP_PPL
---
LGPPGPL
G_____G
P_____P
P_____P
G_____G
P_____P
LGP_PPL
---
LPPPPPL
P_____P
P_____P
P_____P
P_____P
P_____P
LPPPPPL
---
RRRRRRR
RRRRRRR
RRRRRRR
RRRRRRR
RRRRRRR
RRRRRRR
RRRRRRR
""";

    public static final String VILLAGE_HOUSE_BIG = """
village: house 2
sink: 1
legend:
  C = cobblestone
  P = oak_planks
  L = oak_log
  G = glass
  R = oak_planks
  M = lamp
  K = chest loot=village
  V = mob villager_smith
  _ = air
layers:
CCCCCCCCC
CPPPPPPPC
CPPPPPPPC
CPPPPPPPC
CPPPPPPPC
CPPPPPPPC
CPPPPPPPC
CPPPPPPPC
CCCCCCCCC
---
LPPPPPPPL
PK______P
P_______P
P___V___P
P_______P
P_______P
P_______P
P_______P
LPPP_PPPL
---
LGPPGPPGL
G_______G
P_______P
P_______P
G_______G
P_______P
P_______P
G_______G
LGPP_PPGL
---
LGPPGPPGL
G_______G
P_______P
P_______P
G_______G
P_______P
P_______P
GM_____MG
LGPPPPPGL
---
LPPPPPPPL
P_______P
P_______P
P_______P
P_______P
P_______P
P_______P
P_______P
LPPPPPPPL
---
RRRRRRRRR
RRRRRRRRR
RRRRRRRRR
RRRRRRRRR
RRRRRRRRR
RRRRRRRRR
RRRRRRRRR
RRRRRRRRR
RRRRRRRRR
""";

    public static final String VILLAGE_HALL = """
village: hall 1
sink: 1
legend:
  C = cobblestone
  P = oak_planks
  L = oak_log
  G = glass
  R = oak_planks
  M = lamp
  K = chest loot=village
  V = mob villager_elder
  _ = air
layers:
CCCCCCCCCCC
CPPPPPPPPPC
CPPPPPPPPPC
CPPPPPPPPPC
CPPPPPPPPPC
CPPPPPPPPPC
CPPPPPPPPPC
CPPPPPPPPPC
CCCCCCCCCCC
---
LPPPPPPPPPL
PK________P
P_________P
P____V____P
P_________P
P_________P
P_________P
P_________P
LPPP___PPPL
---
LGPPGPPGPPL
G_________G
P_________P
P_________P
G_________G
P_________P
P_________P
G_________G
LGPP___GPPL
---
LGPPGPPGPPL
GM_______MG
P_________P
P_________P
G_________G
P_________P
P_________P
GM_______MG
LGPPPPPGPPL
---
LPPPPPPPPPL
P_________P
P_________P
P_________P
P_________P
P_________P
P_________P
P_________P
LPPPPPPPPPL
---
LPPPPPPPPPL
P_________P
P_________P
P_________P
P_________P
P_________P
P_________P
P_________P
LPPPPPPPPPL
---
RRRRRRRRRRR
RRRRRRRRRRR
RRRRRRRRRRR
RRRRRRRRRRR
RRRRRRRRRRR
RRRRRRRRRRR
RRRRRRRRRRR
RRRRRRRRRRR
RRRRRRRRRRR
""";

    public static final String VILLAGE_WELL = """
village: well 1
sink: 2
legend:
  C = cobblestone
  W = water
  L = oak_log
  R = oak_planks
  _ = air
layers:
CCCCC
CCCCC
CCCCC
CCCCC
CCCCC
---
CCCCC
CWWWC
CWWWC
CWWWC
CCCCC
---
L...L
.___.
.___.
.___.
L...L
---
L...L
.___.
.___.
.___.
L...L
---
RRRRR
RRRRR
RRRRR
RRRRR
RRRRR
""";

    public static final String VILLAGE_FARM = """
village: farm 1
sink: 1
legend:
  D = dirt
  L = oak_log
  c = mob cow
  h = mob chicken
  _ = air
layers:
DDDDDDDDDDD
D.........D
D.........D
D.........D
D.........D
D.........D
D.........D
D.........D
DDDDDDDDDDD
---
LLLLLLLLLLL
L_________L
L____h____L
L__c___c__L
L_________L
L___h_h___L
L_________L
L_________L
LLLL___LLLL
---
LLLLLLLLLLL
L.........L
L.........L
L.........L
L.........L
L.........L
L.........L
L.........L
LLLL...LLLL
""";

    public static final String VILLAGE_LAMP = """
village: lamp 1
sink: 1
legend:
  L = oak_log
  M = lamp
layers:
L
---
L
---
L
---
M

""";

    // --- пустельний храм і фортеця
    public static final String TEMPLE = """
place: surface
spacing: 192
chance: 0.45
sink: 1
biomes: desert glass_dunes
legend:
  T = sandstone
  K = chest loot=temple
  M = lamp
  _ = air
layers:
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
TTTTTTTTTTTTTTT
---
...............
.TTTTTTTTTTTTT.
.TTTTTTTTTTTTT.
.TTTTTTTTTTTTT.
.TTTTTTTTTTTTT.
.TTTTK___KTTTT.
.TTTT_____TTTT.
.TTTT__M__TTTT.
.TTTT_____TTTT.
.TTTT_____TTTT.
.TTTTTT_TTTTTT.
.TTTTTT_TTTTTT.
.TTTTTT_TTTTTT.
.TTTTTT_TTTTTT.
...............
---
...............
...............
..TTTTTTTTTTT..
..TTTTTTTTTTT..
..TTTTTTTTTTT..
..TTT_____TTT..
..TTT_____TTT..
..TTT_____TTT..
..TTT_____TTT..
..TTT_____TTT..
..TTTTT_TTTTT..
..TTTTT_TTTTT..
..TTTTT_TTTTT..
...............
...............
---
...............
...............
...............
...TTTTTTTTT...
...TTTTTTTTT...
...TT_____TT...
...TT_____TT...
...TT_____TT...
...TT_____TT...
...TT_____TT...
...TTTTTTTTT...
...TTTTTTTTT...
...............
...............
...............
---
...............
...............
...............
...............
....TTTTTTT....
....TTTTTTT....
....TTTTTTT....
....TTTTTTT....
....TTTTTTT....
....TTTTTTT....
....TTTTTTT....
...............
...............
...............
...............
---
...............
...............
...............
...............
...............
.....TTTTT.....
.....TTTTT.....
.....TTTTT.....
.....TTTTT.....
.....TTTTT.....
...............
...............
...............
...............
...............
---
...............
...............
...............
...............
...............
...............
......TTT......
......TTT......
......TTT......
...............
...............
...............
...............
...............
...............
""";

    public static final String FORTRESS = """
place: surface
spacing: 320
chance: 0.3
sink: 1
legend:
  B = stone_bricks
  C = cobblestone
  K = chest loot=fortress
  M = lamp
  Z = mob zombie
  _ = air
layers:
BBBBBCBBBBBBBBBBBBBBBCBBB
BBBBCBBBBBBBBBBBBBBBBBBCB
BCBBBBBBBBBBBBBBCBBCBBBBC
BBBBBBBCBCBBBBCBBCBBBCBBB
CBCBBBBBCCBBBCBBBBBBBBBCB
BBBBBBBBCCBBBBBBBBBBBBCBB
BBBCBBBBBBBCBBBCBBBBBBCCB
BBBBBBBBCBBBCBBBCCBCBBBBB
BBBBBBBBBCBBBCBBBBBBBBBBB
BBBBBBBBBBBBBBBBBBBBCBBCB
BBBBCBCBBBBBBBBBBBBBBBCBB
BBBBBBBBBBBBBBBBBBBBBBBBB
CBBBBBBBBBCBBBBBBBBCCBBBB
BBBBBBBBCBCCBBBBBCBBBCBBB
BBBCBBBBBCBBBCBCBBCBBBBBB
BBCBBCBBBCBBCBBBBBBCBBBBB
BBBBBCBBBCBBBBCBBBBBBCBBC
BBBBBBBBBBBCBBBBBBBBBBCBB
BBBBBBBBBBBBBBBBBBBBBBBBB
BBBBBBCBCBBBBCBBBBBCBBBBC
BBBBBBBBBBBBBBBCBBCBBBCCB
BBBBCBBBBBCBBBBBBBCBBBBBB
BBBBBBBBBBBBBBBBCBCBBBBBB
BBBBCBBBBCBBBBBBBBBBBCBBB
BBBBBBBBBCBBBBBBBBBBBBBBB
---
BBBBBBBCBBBBBBCBBBBBBBBBB
B___B_______________B___B
C_K_B_______________B_Z_B
B___C_______________B___B
BBCBB_______________BBBCB
B_______________________B
B_______________________B
B_______________________B
B_______BBBBBBBBB_______B
B_______B_______B_______B
B_______B_K___K_B_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_Z_____B_______B
B_______B___Z___B_______B
B_______B_______B_______B
B_______BBBB_BBBB_______B
C_______________________B
B_______________________C
B_______________________B
BBBCB_______________BCBBB
B___B_______________B___B
B___B_______________B___B
B___B_______________B___B
BBBBBBBBBBB___BBBBBCBBBCB
---
BBBBBCBBBBBCBBBBBBBBBBBBC
C___B_______________B___B
B___B_______________B___B
B___B_______________B___B
BBBCB_______________BBBBB
B_______________________B
B_______________________B
B_______________________B
B_______BBBBBBBBB_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______B
C_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______B
B_______BBBB_BBBB_______B
B_______________________B
C_______________________B
C_______________________B
CBBBB_______________BBBBB
C_M_B_______________BM__B
B___B_______________B___B
B___B_______________C___B
CBBBBBBBBBB___BBBBBBBBBBB
---
CBBBBBBBBCCBBBCBBBBBBBBBB
B___B_______________B___B
B___B_______________B___B
B___B_______________B___B
BBBBB_______________CBBBB
B_______________________B
B_______________________B
B_______________________B
B_______BBBBBBBBB_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______B
B_______BBBBBBBBB_______B
B_______________________B
B_______________________B
B_______________________C
BCBCB_______________BBBBB
B___B_______________B___B
B___B_______________B___B
B___B_______________B___B
BBBCBBBBBBB___BBBCBBBCBBB
---
BCBCBBCBBBBCBBCBBBBBBBBBB
B___B_______________B___B
B___B_______________B___B
B___B_______________B___B
BBBBB_______________BBBBB
B_______________________C
B_______________________B
B_______________________B
B_______BBBBBBBBB_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______B
B_______B_______B_______C
B_______B_______B_______B
B_______BBBBBBBBB_______C
B_______________________B
B_______________________B
B_______________________B
BBBBB_______________BBBBB
B___B_______________B___B
B___B_______________B___B
B___B_______________B___B
BBBBCBBBBBBBBBBCBBBBBBBBB
---
BBBBBBBBBBBBBBBBBBBBBBBBB
B___B...............B___B
B___B...............B___B
B___B...............B___B
BBCBB...............BBBBB
B.......................C
B.......................B
B.......................B
C.......BBBBBBBBB.......B
B.......B_______B.......B
B.......B_______B.......B
B.......B_______B.......B
B.......B_______B.......C
B.......B_______B.......B
B.......B_______B.......B
B.......B_______B.......B
B.......BBBBBBBBB.......B
B.......................B
B.......................B
B.......................B
BBBBB...............BBBBB
C___B...............B___B
B___B...............B___B
B___B...............B___B
BBBBBBBBBBBBBBBBBBBBBBBBB
---
BBBBBBBBBBBBBBBBCCBBBBBBB
B___B...............C___B
B___C...............B___C
B___B...............B___B
BBBBB...............BBBBC
B.......................B
B.......................B
B.......................B
C.......BBBBBBBBB.......B
C.......B_______B.......B
B.......B_______B.......B
B.......B_______B.......B
B.......B_______B.......B
B.......B_______B.......B
B.......B_______B.......B
B.......B_______B.......B
B.......BBBBBBBBB.......B
C.......................B
B.......................B
B.......................B
BBBBB...............CBBBB
B___B...............B___B
B___C...............B___B
B___B...............B___B
BBBBCBBBBBBBBBBBBBBBBBBBB
---
BBBBBBBBBCBBBBBBBBBBBBBCB
C___B...............B___B
B___B...............B___B
B___B...............B___B
BBBBB...............BBBBB
B.......................B
B.......................B
C.......................B
B.......BBBBBBBBB.......B
B.......BBBBBBBBB.......B
B.......BBBBBBBBB.......B
B.......BBBBBBBBB.......B
B.......BBBBBBBBB.......B
C.......BBBBBBBBB.......B
B.......BBBBBBBBB.......B
B.......BBBBBBBBB.......B
B.......BBBBBBBBB.......B
B.......................B
B.......................B
B.......................B
BBBBC...............BBBBB
B___B...............B___B
B___B...............B___B
B___B...............B___B
BBBCBBBBCBBBBBBBBBBBBBBBB
---
BBBBC.B.B.B.B.B.B.B.BBCBB
B___B...............B___B
B___B...............B___C
B___B...............B___B
BBBBB...............CBBBB
.........................
B.......................B
.........................
B.......................B
.........................
B.......................B
.........................
B.......................B
.........................
B.......................B
.........................
B.......................B
.........................
B.......................B
.........................
BBBBB...............BBBBB
B___B...............B___B
B___B...............B___B
B___B...............B___B
BBBBB.B.B.B.B.B.B.B.BCBBB
---
B.B.B...............B.B.C
.........................
B...B...............B...B
.........................
B.B.B...............B.B.B
.........................
.........................
.........................
.........................
.........................
.........................
.........................
.........................
.........................
.........................
.........................
.........................
.........................
.........................
.........................
B.B.B...............B.B.B
.........................
B...B...............C...B
.........................
B.B.B...............B.B.B
""";
}
