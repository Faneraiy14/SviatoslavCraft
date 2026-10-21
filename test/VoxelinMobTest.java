import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Істоти: реєстр, фізика, ШІ, бій, спавнер, збереження, команди, швидкодія. */
public class VoxelinMobTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static File root;

    /** Фіктивний гравець: де стоїть, чи живий/творчий, скільки разів і на скільки його вдарили. */
    static final class Fake implements PlayerTarget {
        Pos pos; boolean alive = true, creative; int hits; double total, lastKx, lastKz;
        Fake(Pos p) { pos = p; }
        public Pos body() { return pos; }
        public boolean alive() { return alive; }
        public boolean creative() { return creative; }
        public void hurt(double d, double kx, double kz) { hits++; total += d; lastKx = kx; lastKz = kz; }
    }

    static World world(String name, long seed, WorldInfo.Type type) throws Exception {
        g.createWorld(name, seed, type);
        World w = g.openWorld(name);
        w.naturalSpawning = false;
        return w;
    }

    static void load(World w, long cx, long cz, int r) throws Exception {
        for (int i = 0; i < 600; i++) {
            w.update(cx, cz, r);
            boolean all = true;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (dx * dx + dz * dz <= r * r + r && w.chunk(cx + dx, cz + dz) == null) all = false;
            if (all) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("чанки не завантажились");
    }

    static void night(World w) { w.setTime((long) (w.dayTicks() * 0.78)); }
    static void day(World w) { w.setTime((long) (w.dayTicks() * 0.1)); }
    static void run(World w, int ticks) { for (int i = 0; i < ticks; i++) w.tick(); }
    static Pos at(long x, double y, long z) { return Pos.ofBlock(x, y, z); }
    static double dist(Mob m, Pos p) { return Math.hypot(m.pos.relX(p.cx) - p.x, m.pos.relZ(p.cz) - p.z); }

    public static void main(String[] a) throws Exception {
        g = new Game(new File("nope"), null);
        root = Files.createTempDirectory("vx-mob").toFile();
        g = new Game(new File(root, "saves"), null);
        registry(); physics(); ai(); combat(); spawner(); persistence(); commands(); perf();
        System.out.println("VoxelinMobTest: " + n + " перевірок OK");
    }

    static void registry() {
        for (String m : new String[]{"chicken", "cow", "zombie", "spider"}) check(g.mobTypes.get("voxelin:" + m) != null && g.mobTypes.find(m) != null, "істота " + m);
        MobType z = g.mobTypes.find("zombie"), c = g.mobTypes.find("chicken"), s = g.mobTypes.find("spider");
        check(z.hostile() && z.burnsInSun && z.health == 20 && z.attackDamage == 3 && z.cooldownTicks == 20, "зомбі");
        check(c.passive() && c.spawnOn.contains("voxelin:grass") && c.title.equals("Курка"), "курка мирна й спавниться на траві");
        check(s.behavior == MobSettings.Behavior.NEUTRAL_DAY, "павук вдень нейтральний");
        for (MobType t : g.mobTypes.all()) {
            check(!t.parts.isEmpty() && t.width > 0 && t.height > 0 && t.health > 0, "модель і розміри " + t.name);
            for (BlockSettings.Drop d : t.drops) check(g.items.get(d.item) != null, "дроп існує: " + t.name + " -> " + d.item);
        }
        check(g.mobTypes.find("nonsense") == null && g.mobTypes.find(null) == null && g.mobTypes.find("VOXELIN:COW") != null, "пошук");
        boolean threw = false;
        try { g.mobTypes.register("x:y", new MobSettings()); } catch (IllegalStateException e) { threw = true; }
        check(threw, "реєстрація після закриття заборонена");
        MobRegistry r = new MobRegistry();
        r.register("m:a", new MobSettings());
        threw = false; try { r.register("m:a", new MobSettings()); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "повтор відхиляється");
        threw = false; try { r.register("Bad", new MobSettings()); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "погана назва відхиляється");
        MobSettings w = new MobSettings().health(Float.NaN).speed(-5).size(-1, 1e9f).attack(Float.NaN, -2, 0);
        check(w.healthValue() == 10 && w.speedValue() > 0 && w.widthValue() > 0 && w.heightValue() <= 6 && w.reachValue() > 0 && w.cooldownValue() > 0, "межі налаштувань");
        check(g.items.get("voxelin:iron_sword").attack == 6 && g.items.get("voxelin:wooden_sword").attack == 4 && g.items.get("voxelin:stone").attack == 1, "шкода предметів");
    }

    static void physics() throws Exception {
        World w = world("phys", 1, WorldInfo.Type.FLAT);
        load(w, 0, 0, 2);
        int top = w.surfaceY(0, 0);
        MobType zt = g.mobTypes.find("zombie");
        Mob m = w.spawnMob(zt, at(0, top + 12, 0));
        for (int i = 0; i < 100 && !m.onGround; i++) m.tick(w, null, new Random(1));
        check(m.onGround && Math.abs(m.pos.y - (top + 1)) < 0.2, "падає й стоїть на землі: y=" + m.pos.y);
        // води нема - перевіримо плавання: поставити воду
        Source src = Sources.player("T");
        for (int x = 8; x <= 10; x++) for (int z = 8; z <= 10; z++) for (int y = top - 2; y <= top + 3; y++) w.setBlock(src, x, y, z, g.blocks.byName(y <= top ? "voxelin:water" : "voxelin:water"));
        Mob sw = w.spawnMob(g.mobTypes.find("cow"), at(9, top + 0.5, 9));
        for (int i = 0; i < 100; i++) sw.tick(w, null, new Random(2));
        check(sw.pos.y > top - 2 && sw.inWater || sw.pos.y > top, "у воді не тоне: y=" + sw.pos.y);
        // стіна 1 блок: перестрибує; стіна 2 блоки: ні
        for (int y = top + 1; y <= top + 1; y++) for (int z = -3; z <= 3; z++) w.setBlock(src, 20, y, z, g.blocks.byName("voxelin:stone"));
        Fake tgt = new Fake(at(26, top + 1, 0));
        tgt.creative = false;
        night(w);
        Mob jumper = w.spawnMob(zt, at(16, top + 1, 0));
        Random rng = new Random(3);
        for (int i = 0; i < 400; i++) { w.setTime((long) (w.dayTicks() * 0.78)); jumper.tick(w, tgt, rng); }
        check(jumper.pos.relX(0) > 20.5, "перестрибнув стіну на 1 блок: x=" + jumper.pos.relX(0));
        for (int z = -3; z <= 3; z++) w.setBlock(src, 21, top + 2, z, g.blocks.byName("voxelin:stone")); // тепер 2 блоки заввишки
        for (int z = -3; z <= 3; z++) w.setBlock(src, 21, top + 1, z, g.blocks.byName("voxelin:stone"));
        Fake tgt2 = new Fake(at(-12, top + 1, 0));
        Mob blocked = w.spawnMob(zt, at(26, top + 1, 0));
        // обійти не може: стіна довжиною z -3..3, зомбі йде прямо до гравця (z=0)
        for (int i = 0; i < 400; i++) { w.setTime((long) (w.dayTicks() * 0.78)); blocked.tick(w, tgt2, rng); }
        check(blocked.pos.relX(0) > 21.4, "стіна на 2 блоки - не перестрибнути: x=" + blocked.pos.relX(0));
        w.close();
    }

    static void ai() throws Exception {
        World w = world("ai", 2, WorldInfo.Type.FLAT);
        load(w, 0, 0, 6);
        int top = w.surfaceY(0, 0);
        MobType zt = g.mobTypes.find("zombie"), ct = g.mobTypes.find("chicken"), st = g.mobTypes.find("spider");
        Random rng = new Random(5);
        // переслідування й удар
        night(w);
        Fake p = new Fake(at(0, top + 1, 0));
        Mob z = w.spawnMob(zt, at(10, top + 1, 0));
        double d0 = dist(z, p.pos);
        for (int i = 0; i < 40; i++) z.tick(w, p, rng);
        check(dist(z, p.pos) < d0 - 3, "зомбі йде до гравця: " + d0 + " -> " + dist(z, p.pos));
        for (int i = 0; i < 200; i++) z.tick(w, p, rng);
        check(p.hits >= 8 && p.hits <= 12, "раз на секунду: ударів за ~10 с " + p.hits);
        check(Math.abs(p.total - 3.0 * p.hits) < 1e-9, "шкода 3 за удар");
        check(Math.abs(Math.hypot(p.lastKx, p.lastKz) - 1) < 1e-6, "напрям поштовху нормований");
        // поза радіусом виявлення - не переслідує
        Mob far = w.spawnMob(zt, at(40, top + 1, 0));
        double fd = dist(far, p.pos);
        for (int i = 0; i < 30; i++) far.tick(w, p, rng);
        check(Math.abs(dist(far, p.pos) - fd) < 4, "поза 16 блоками не переслідує: " + fd + " -> " + dist(far, p.pos));
        // творчий гравець: не атакують
        Fake cr = new Fake(at(0, top + 1, 20)); cr.creative = true;
        Mob z2 = w.spawnMob(zt, at(2, top + 1, 20));
        for (int i = 0; i < 100; i++) z2.tick(w, cr, rng);
        check(cr.hits == 0, "творчого не б'ють");
        Fake dead = new Fake(at(0, top + 1, 40)); dead.alive = false;
        Mob z3 = w.spawnMob(zt, at(2, top + 1, 40));
        for (int i = 0; i < 100; i++) z3.tick(w, dead, rng);
        check(dead.hits == 0, "мертвого не б'ють");
        // зомбі горить на сонці
        day(w);
        Mob burner = w.spawnMob(zt, at(-30, top + 1, -30));
        for (int i = 0; i < 100; i++) burner.tick(w, null, rng);
        check(burner.burning && burner.health <= 20 - 4, "на сонці горить: " + burner.health);
        // у тіні - ні
        Source src = Sources.player("T");
        for (int x = -62; x <= -58; x++) for (int zz = -62; zz <= -58; zz++) for (int y = top + 3; y <= top + 3; y++) w.setBlock(src, x, y, zz, g.blocks.byName("voxelin:stone"));
        Mob shade = w.spawnMob(zt, at(-60, top + 1, -60));
        for (int i = 0; i < 100; i++) shade.tick(w, null, rng);
        check(shade.health == 20 && !shade.burning, "в тіні не горить: " + shade.health);
        // вночі не горить
        night(w);
        Mob nightMob = w.spawnMob(zt, at(-30, top + 1, -50));
        for (int i = 0; i < 100; i++) nightMob.tick(w, null, rng);
        check(nightMob.health == 20, "вночі не горить");
        // павук: вдень нейтральний, поки не вдарили
        day(w);
        Fake sp = new Fake(at(0, top + 1, 60));
        Mob spider = w.spawnMob(st, at(3, top + 1, 60));
        for (int i = 0; i < 100; i++) spider.tick(w, sp, rng);
        check(sp.hits == 0, "павук вдень не нападає");
        w.hurtMob(spider, 1, 1, 0, at(0, top + 1, 60));
        spider.invuln = 0;
        for (int i = 0; i < 100; i++) spider.tick(w, sp, rng);
        check(sp.hits > 0, "після удару павук нападає");
        night(w);
        Fake sp2 = new Fake(at(0, top + 1, 80));
        Mob spider2 = w.spawnMob(st, at(3, top + 1, 80));
        for (int i = 0; i < 100; i++) spider2.tick(w, sp2, rng);
        check(sp2.hits > 0, "вночі павук нападає");
        // мирні блукають і не тікають безпричинно; після удару тікають
        Mob chicken = w.spawnMob(ct, at(0, top + 1, -80));
        double moved = 0; Pos last = chicken.pos.copy();
        for (int i = 0; i < 400; i++) { chicken.tick(w, null, rng); moved += Math.hypot(chicken.pos.relX(last.cx) - last.x, chicken.pos.relZ(last.cz) - last.z); last = chicken.pos.copy(); }
        check(moved > 2 && moved < 400 * 0.05 * 3, "курка блукає: пройшла " + moved);
        Pos from = at(chicken.pos.blockX() + 2, top + 1, chicken.pos.blockZ());
        double bd = dist(chicken, from);
        w.hurtMob(chicken, 1, -1, 0, from);
        for (int i = 0; i < 60; i++) chicken.tick(w, null, rng);
        check(chicken.alive() && dist(chicken, from) > bd + 2, "після удару тікає від нападника: " + bd + " -> " + dist(chicken, from));
        check(chicken.fleeTicks <= 100 && chicken.fleeTicks > 0 || chicken.fleeTicks == 0, "втеча обмежена в часі");
        w.close();
    }

    static void combat() throws Exception {
        World w = world("combat", 3, WorldInfo.Type.FLAT);
        load(w, 0, 0, 2);
        int top = w.surfaceY(0, 0);
        Source src = Sources.player("T");
        MobType cow = g.mobTypes.find("cow");
        Mob m = w.spawnMob(cow, at(0, top + 1, -3));
        for (int i = 0; i < 5; i++) m.tick(w, null, new Random(1));
        Pos eye = at(0, top + 1.6, 1); eye.x = 0.5; eye.z = 8.5; // трохи в чанку 0: z 8.5; дивимось у -z
        eye = new Pos(0, 0, 0.5, top + 1.2, 1.0);
        Mob hit = Combat.raycast(w, eye, 0f, 0f, 5.0, 100);
        // точна геометрія: істота прямо перед очима на відстані 3
        Mob target = w.spawnMob(g.mobTypes.find("zombie"), new Pos(0, 0, 8.5, top + 1, 5.0)); // пряма на -z з z=8.5
        Pos e2 = new Pos(0, 0, 8.5, top + 1.5, 8.5 + 0.0);
        e2 = new Pos(0, 0, 8.5, top + 1.5, 9.0);
        check(Combat.raycast(w, e2, 0f, 0f, 5.0, 100) == target, "промінь влучає в істоту попереду");
        check(Combat.raycast(w, e2, 0f, 0f, 3.0, 100) == null, "задалеко - не влучає (4 блоки > 3)");
        check(Combat.raycast(w, e2, 0f, 0f, 5.0, 2.0) == null, "блок ближче за істоту перекриває");
        check(Combat.raycast(w, e2, 180f, 0f, 5.0, 100) == null, "позаду нічого");
        check(Combat.raycast(w, e2, 0f, 60f, 5.0, 100) == null, "промінь униз повз істоту");
        // дві істоти на лінії - найближча
        Mob near = w.spawnMob(g.mobTypes.find("chicken"), new Pos(0, 0, 8.5, top + 1, 7.0));
        check(Combat.raycast(w, e2, 0f, 0f, 5.0, 100) == near, "найближча на промені");
        w.killAll(g.mobTypes.find("chicken"));
        // удар: шкода, відкидання, невразливість
        double hp = target.health;
        check(Combat.attack(w, target, 6, e2) && target.health == hp - 6, "удар знімає здоров'я");
        check(target.vz < 0 && target.vy > 0, "відкидання від нападника (нападник на +z, істота летить у -z): vz=" + target.vz);
        check(!Combat.attack(w, target, 6, e2) && target.health == hp - 6, "невразливість після удару");
        run(w, 12);
        check(target.invuln == 0 || target.invuln < 10, "невразливість спливає");
        for (int i = 0; i < 12; i++) target.tick(w, null, new Random(1));
        check(Combat.attack(w, target, 6, e2), "знову можна вдарити");
        // смерть: дроп, анімація, зникнення
        Mob d = w.spawnMob(g.mobTypes.find("zombie"), new Pos(0, 0, 4.5, top + 1, 4.5));
        int itemsBefore = w.itemEntities().size();
        d.invuln = 0;
        check(Combat.attack(w, d, 100, e2) && d.dying, "смертельний удар");
        check(!d.removable(), "анімація смерті ще йде");
        for (int i = 0; i < 20; i++) d.tick(w, null, new Random(1));
        check(d.removable(), "після анімації можна прибрати");
        Mob cw = w.spawnMob(cow, new Pos(0, 0, 2.5, top + 1, 2.5));
        for (int i = 0; i < 40; i++) { cw.invuln = 0; w.hurtMob(cw, 1000, 1, 0, e2); if (cw.dying) break; }
        int beef = 0; for (var it : w.itemEntities()) if (it.item.endsWith("raw_beef")) beef += it.count;
        check(beef >= 1 && beef <= 3, "корова дала яловичину 1..3: " + beef);
        check(!w.hurtMob(cw, 5, 1, 0, e2), "мертву не б'ють");
        // дроп лише раз
        run(w, 30);
        int beef2 = 0; for (var it : w.itemEntities()) if (it.item.endsWith("raw_beef")) beef2 += it.count;
        check(beef2 == beef && !w.mobs().contains(cw), "дроп не повторюється, мертва корова зникла");
        // ймовірність дропу пір'я 0..2 і сире м'ясо завжди
        int meat = 0, feathers = 0;
        for (int i = 0; i < 200; i++) {
            Mob ch = w.spawnMob(g.mobTypes.find("chicken"), new Pos(0, 0, 3.5, top + 1, 3.5));
            ch.invuln = 0; int b = w.itemEntities().size();
            w.hurtMob(ch, 100, 0, 1, e2);
            for (int k = b; k < w.itemEntities().size(); k++) { var it = w.itemEntities().get(k); if (it.item.endsWith("raw_chicken")) meat += it.count; if (it.item.endsWith("feather")) { feathers += it.count; check(it.count <= 2, "пір'я не більше 2"); } }
            w.killAll(null);
        }
        check(meat == 200 && feathers > 100 && feathers < 300, "курка: 200 м'яса, пір'я ~200: " + feathers);
        w.close();
    }

    static void spawner() throws Exception {
        World w = world("spawn", 4, WorldInfo.Type.FLAT);
        w.naturalSpawning = true;
        load(w, 0, 0, 5);
        int top = w.surfaceY(0, 0);
        Fake p = new Fake(at(0, top + 1, 0));
        w.setPlayerTarget(p);
        night(w);
        for (int i = 0; i < 20 * 60; i++) { w.setTime((long) (w.dayTicks() * 0.78)); w.tick(); }
        int hostile = 0, passive = 0;
        for (Mob m : w.mobs()) { if (m.type.hostile() || m.type.behavior == MobSettings.Behavior.NEUTRAL_DAY) hostile++; else passive++; }
        check(hostile > 0 && hostile <= MobSpawner.HOSTILE_CAP, "уночі з'явились вороги в межах ліміту: " + hostile);
        for (Mob m : w.mobs()) check(!m.type.boss && !m.type.persistent && m.type.dimension.equals("overworld"), "боси, постійні й чужі для виміру (Відлуння) природно не з'являються: " + m.type.name);
        for (Mob m : w.mobs()) {
            double d = Math.hypot(m.pos.relX(0) - p.pos.x, m.pos.relZ(0) - p.pos.z);
            check(!w.isSolid(m.pos.blockX(), m.pos.blockY(), m.pos.blockZ()) && !w.isSolid(m.pos.blockX(), m.pos.blockY() + 1, m.pos.blockZ()), "істота не в блоці: " + m.type.name + " " + m.pos.blockX() + "," + m.pos.blockY() + "," + m.pos.blockZ() + " id=" + w.blockId(m.pos.blockX(), m.pos.blockY(), m.pos.blockZ()) + "/" + w.blockId(m.pos.blockX(), m.pos.blockY() + 1, m.pos.blockZ()));
        }
        check(passive == 0, "уночі мирні не з'являються (початкові - за ліміту дня): " + passive);
        // у момент появи ворог далеко від гравця (потім може підійти)
        w.killAll(null);
        Random sr = new Random(77);
        int spawned = 0;
        for (int i = 0; i < 300; i++) {
            int before = w.mobs().size();
            w.spawner().attempt(w, p, sr, true);
            if (w.mobs().size() > before) { Mob nm = w.mobs().get(w.mobs().size() - 1); spawned++; check(Math.hypot(nm.pos.relX(0) - p.pos.x, nm.pos.relZ(0) - p.pos.z) >= 23, "ворог з'являється не ближче 24 блоків"); }
            if (w.mobs().size() >= MobSpawner.HOSTILE_CAP) break;
        }
        check(spawned > 0, "спроби спавну дали ворогів: " + spawned);
        w.killAll(null);
        day(w);
        for (int i = 0; i < 20 * 90; i++) { w.setTime((long) (w.dayTicks() * 0.1)); w.tick(); }
        passive = 0; hostile = 0;
        for (Mob m : w.mobs()) { if (m.type.passive()) { passive++; check(w.blocks.get(w.blockId(m.pos.blockX(), m.pos.blockY() - 1, m.pos.blockZ())).name.equals("voxelin:grass") || m.pos.y > top, "мирні на траві"); } else hostile++; }
        check(passive > 0 && passive <= MobSpawner.PASSIVE_CAP + 3, "вдень з'явились мирні, ліміт тримається: " + passive);
        check(hostile == 0, "вдень вороги не з'являються: " + hostile);
        // творчий/мертвий гравець: спавну нема
        w.killAll(null);
        p.alive = false;
        for (int i = 0; i < 20 * 20; i++) w.tick();
        check(w.mobs().isEmpty(), "мертвому гравцю спавн не потрібен");
        w.close();
        // великі координати
        World far = world("spawnfar", 5, WorldInfo.Type.FLAT);
        far.naturalSpawning = true;
        long fx = 1_000_000_000_000_000L;
        load(far, fx >> 4, fx >> 4, 4);
        Fake fp = new Fake(at(fx, 65, fx));
        far.setPlayerTarget(fp);
        for (int i = 0; i < 20 * 60; i++) { far.setTime((long) (far.dayTicks() * 0.78)); far.tick(); }
        int cnt = 0;
        for (Mob m : far.mobs()) { cnt++; check(Math.abs(m.pos.cx * 16 - fx) < 1000, "істоти біля гравця на 1e15"); }
        check(cnt > 0, "спавн працює на 1e15: " + cnt);
        far.close();
    }

    static void persistence() throws Exception {
        World w = world("pers", 6, WorldInfo.Type.FLAT);
        load(w, 0, 0, 2);
        int top = w.surfaceY(0, 0);
        Mob c = w.spawnMob(g.mobTypes.find("cow"), at(3, top + 1, 3));
        c.health = 7;
        w.spawnMob(g.mobTypes.find("zombie"), at(5, top + 1, 5));
        Mob dead = w.spawnMob(g.mobTypes.find("chicken"), at(6, top + 1, 6));
        dead.invuln = 0; w.hurtMob(dead, 100, 1, 0, at(0, top + 1, 0));
        w.close();
        check(new File(root, "saves/pers/mobs.dat").isFile(), "mobs.dat записано");
        World w2 = new Game(new File(root, "saves"), null).openWorld("pers");
        check(w2.mobs().size() == 1 && w2.mobs().get(0).type.name.equals("voxelin:cow") && w2.mobs().get(0).health == 7, "мирна корова відновлена зі здоров'ям, ворог і мертва - ні: " + w2.mobs().size());
        w2.close();
        Files.write(new File(root, "saves/pers/mobs.dat").toPath(), ("garbage\n1 2\nvoxelin:cow 0 0 99 5 5 5 0\nvoxelin:cow 0 0 5 5 5 0 0\nvoxelin:cow 0 0 5 NaN 5 5 0\ngone:mod 0 0 5 5 5 5 0\nvoxelin:cow 0 0 5 70 5 10 45\n").getBytes());
        World w3 = new Game(new File(root, "saves"), null).openWorld("pers");
        check(w3.mobs().size() == 1 && w3.mobs().get(0).health == 10, "битий mobs.dat: лишається лише хороший запис: " + w3.mobs().size());
        w3.close();
    }

    static void commands() throws Exception {
        World w = world("cmd", 7, WorldInfo.Type.FLAT);
        load(w, 0, 0, 2);
        int top = w.surfaceY(0, 0);
        Source p = Sources.player("P");
        long[] o = {0, top + 1, 0};
        List<String> out = g.commands.execute(p, w, o, "/summon cow 3");
        check(out.get(0).startsWith("Створено 3") && w.mobs().size() == 3, "/summon cow 3: " + out);
        out = g.commands.execute(p, w, o, "/summon voxelin:zombie");
        check(w.mobs().size() == 4, "/summon з повною назвою");
        for (String bad : new String[]{"/summon", "/summon nope", "/summon cow 0", "/summon cow -2", "/summon cow 99", "/summon cow x", "/summon cow 1 2"}) {
            int before = w.mobs().size();
            out = g.commands.execute(p, w, o, bad);
            check(w.mobs().size() == before && !out.isEmpty() && !out.get(0).startsWith("Створено"), "погана команда " + bad + ": " + out);
        }
        out = g.commands.execute(p, w, o, "/killall cow");
        check(out.get(0).contains("3") && w.mobs().size() == 1, "/killall cow: " + out);
        check(g.commands.execute(p, w, o, "/killall nope").get(0).contains("Невідомий"), "/killall невідомий вид");
        check(g.commands.execute(p, w, o, "/killall a b").get(0).contains("щонайбільше"), "/killall забагато аргументів");
        out = g.commands.execute(p, w, o, "/killall");
        check(w.mobs().isEmpty(), "/killall усіх: " + out);
        check(g.commands.execute(Sources.of("g", Source.Kind.PLAYER, 0, 1), w, o, "/summon cow").get(0).contains("прав"), "/summon потребує прав");
        long[] far = {5000, 70, 5000};
        out = g.commands.execute(p, w, far, "/summon cow");
        check(out.get(0).contains("Не вдалось") && w.mobs().isEmpty(), "у незавантаженому чанку - відмова: " + out);
        for (int i = 0; i < MobLimit(); i++) w.spawnMob(g.mobTypes.find("chicken"), at(i % 16, top + 1, 0));
        check(w.mobs().size() == World.MAX_MOBS && w.spawnMob(g.mobTypes.find("cow"), at(0, top + 1, 0)) == null, "ліміт істот у світі");
        w.close();
    }

    static int MobLimit() { return World.MAX_MOBS + 20; }

    static void perf() throws Exception {
        World w = world("perf", 8, WorldInfo.Type.FLAT);
        load(w, 0, 0, 3);
        int top = w.surfaceY(0, 0);
        Fake p = new Fake(at(0, top + 1, 0));
        w.setPlayerTarget(p);
        night(w);
        for (int i = 0; i < 250; i++) w.spawnMob(g.mobTypes.find(i % 2 == 0 ? "zombie" : "spider"), at(-20 + i % 40, top + 1, -20 + i / 40));
        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) { w.setTime((long) (w.dayTicks() * 0.78)); w.tick(); }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(ms < 3000, "250 істот x 100 тіків: " + ms + " мс");
        System.out.println("  (250 істот x 100 тіків: " + ms + " мс)");
        w.close();
    }
}
