package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.world.Block;
import com.sviatoslav.craft.engine.world.Chunk;
import com.sviatoslav.craft.engine.world.World;

// РЕАЛЬНИЙ БАГ (знайдений Sviatoslav'ом живцем, підтверджено L-подібним
// розташуванням 3 блоків): попередня версія йшла ФІКСОВАНИМ кроком 0.05
// і на кожному кроці перевіряла лише ОДНУ семпл-точку (floor(px),floor(py),
// floor(pz)). Коли два блоки дотикаються лише РЕБРОМ (по діагоналі, не
// цілою гранню), між ними математично є нескінченно тонка щілина - і якщо
// промінь проходив саме крізь цей кут, послідовність семпл-точок могла
// щоразу потрапляти в ПОРОЖНІ діагональні клітинки навколо кута, жодного
// разу не влучивши всередину жодного із двох суцільних блоків, хоча
// візуально з погляду людини кут виглядає перекритим.
//
// Виправлено переходом на СПРАВЖНІЙ DDA (Digital Differential Analyzer,
// той самий метод, що й "A Fast Voxel Traversal Algorithm" Amanatidis-Woo,
// стандарт у voxel-рейкастингу): замість довільних семпл-точок через
// фіксований крок, обхід рахує ТОЧНИЙ момент (tMaxX/Y/Z), коли промінь
// перетинає НАЙБЛИЖЧУ межу сітки по кожній осі, і щоразу переходить РІВНО
// в одну сусідню клітинку вздовж тієї осі, чия межа ближча. Це гарантує
// відвідування КОЖНОЇ клітинки на шляху променя без пропусків - кут між
// двома блоками більше неможливо "проскочити" непоміченим.
public final class BlockRayCast {
    public static final float MAX_REACH = 6.0f * Chunk.BLOCK_SIZE;

    public record Hit(int hitX, int hitY, int hitZ, int placeX, int placeY, int placeZ) {}

    public static Hit cast(float ox, float oy, float oz, float yawDeg, float pitchDeg, World world) {
        float yaw = (float) Math.toRadians(yawDeg);
        float pitch = (float) Math.toRadians(pitchDeg);
        // Узгоджено з Camera.moveForward (sin(yaw), 0, -cos(yaw)) при pitch=0;
        // вертикальна складова - "миша вгору = дивимось вгору".
        double dx = Math.sin(yaw) * Math.cos(pitch);
        double dy = -Math.sin(pitch);
        double dz = -Math.cos(yaw) * Math.cos(pitch);

        // Світова float-позиція ока -> grid-простір (те саме ділення на
        // Chunk.BLOCK_SIZE, що й скрізь у Player/World) - увесь обхід DDA
        // рахується в grid-одиницях (одна клітинка = 1.0), так найпростіше.
        double gox = ox / Chunk.BLOCK_SIZE, goy = oy / Chunk.BLOCK_SIZE, goz = oz / Chunk.BLOCK_SIZE;
        double maxReachGrid = MAX_REACH / Chunk.BLOCK_SIZE;

        int gx = (int) Math.floor(gox), gy = (int) Math.floor(goy), gz = (int) Math.floor(goz);
        int stepX = sign(dx), stepY = sign(dy), stepZ = sign(dz);

        double tMaxX = firstBoundary(gox, gx, stepX, dx);
        double tMaxY = firstBoundary(goy, gy, stepY, dy);
        double tMaxZ = firstBoundary(goz, gz, stepZ, dz);
        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

        int lastAirX = gx, lastAirY = gy, lastAirZ = gz;
        double t = 0;

        while (t < maxReachGrid) {
            Block b = world.getBlock(gx, gy, gz);
            if (b != null && b.getType() != Block.Type.AIR) {
                return new Hit(gx, gy, gz, lastAirX, lastAirY, lastAirZ);
            }
            lastAirX = gx; lastAirY = gy; lastAirZ = gz;

            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                gx += stepX; t = tMaxX; tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                gy += stepY; t = tMaxY; tMaxY += tDeltaY;
            } else {
                gz += stepZ; t = tMaxZ; tMaxZ += tDeltaZ;
            }
        }
        return null; // нічого в межах досяжності
    }

    private static int sign(double v) { return v > 1e-9 ? 1 : (v < -1e-9 ? -1 : 0); }

    // t (у grid-одиницях уздовж променя), за якого координата вперше
    // перетинає межу клітинки в напрямку руху по цій осі.
    private static double firstBoundary(double origin, int cell, int step, double d) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double boundary = step > 0 ? (cell + 1) : cell;
        return (boundary - origin) / d;
    }
}
