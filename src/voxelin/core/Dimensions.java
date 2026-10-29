package voxelin.core;

import voxelin.core.entity.PlayerEntity;
import voxelin.core.gen.BetweenGenerator;
import voxelin.core.gen.EndGenerator;

/** Виміри: назва <-> тип світу і точка прибуття (ноги гравця на платформі). */
public final class Dimensions {
    private Dimensions() {}

    public static final String OVERWORLD = "overworld", END = "end", BETWEEN = "between", NETHER = "nether";

    public static String of(WorldInfo.Type t) { return t == WorldInfo.Type.END ? END : t == WorldInfo.Type.BETWEEN ? BETWEEN : t == WorldInfo.Type.NETHER ? NETHER : OVERWORLD; }

    /** Тип для виміру-теки або null (звичайний світ / невідомий). */
    public static WorldInfo.Type type(String dim) { return END.equals(dim) ? WorldInfo.Type.END : BETWEEN.equals(dim) ? WorldInfo.Type.BETWEEN : NETHER.equals(dim) ? WorldInfo.Type.NETHER : null; }

    /** Позиція очей гравця при прибутті у вимір. */
    public static Pos arrival(String dim) {
        if (NETHER.equals(dim)) return Pos.ofBlock(0, 70 + PlayerEntity.EYE, 0);
        if (BETWEEN.equals(dim)) return Pos.ofBlock(BetweenGenerator.PLATFORM_X, BetweenGenerator.TOP + 1 + PlayerEntity.EYE + 0.02, BetweenGenerator.ARRIVAL_Z);
        return Pos.ofBlock(EndGenerator.PLATFORM_X, EndGenerator.TOP + 1 + PlayerEntity.EYE + 0.02, EndGenerator.ARRIVAL_Z);
    }

    /** Куди веде постійний портал з цього виміру: з Енду й міжсвіття - додому, зі звичайного світу - в Енд. */
    public static String portalTarget(String from) { return OVERWORLD.equals(from) ? END : OVERWORLD; }
}
