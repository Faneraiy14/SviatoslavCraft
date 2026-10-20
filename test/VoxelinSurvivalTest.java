import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Доба, здоров'я, голод, падіння, утоплення, смерть, збереження, команди /time /hurt /eat /heal. */
public class VoxelinSurvivalTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

    public static void main(String[] a) throws Exception {
        cycle();
        vitals();
        fall();
        world();
        System.out.println("VoxelinSurvivalTest: " + n + " перевірок OK");
    }

    static void cycle() {
        long dt = DayCycle.dayTicks(30);
        check(dt == 36000, "30 хв = 36000 тіків: " + dt);
        check(DayCycle.dayTicks(0) == DayCycle.dayTicks(1) && DayCycle.dayTicks(1e9) == DayCycle.dayTicks(24 * 60) && DayCycle.dayTicks(Double.NaN) == 36000, "довжина доби обмежена");
        check(DayCycle.day(0, dt) == 1 && DayCycle.day(dt - 1, dt) == 1 && DayCycle.day(dt, dt) == 2 && DayCycle.day(Long.MAX_VALUE, dt) > 1, "номер доби");
        check(near(DayCycle.phase(dt + dt / 4, dt), 0.25) && near(DayCycle.phase(-1, dt), (double) (dt - 1) / dt), "фаза, у т.ч. від'ємний час");
        double prev = DayCycle.sky(0), maxStep = 0, min = 9, max = -9;
        for (int i = 0; i <= 100000; i++) {
            double p = i / 100000.0 * 0.999999, s = DayCycle.sky(p);
            check(s >= DayCycle.NIGHT_SKY - 1e-12 && s <= 1 + 1e-12, "sky у межах: " + s);
            maxStep = Math.max(maxStep, Math.abs(s - prev)); prev = s; min = Math.min(min, s); max = Math.max(max, s);
            double g = DayCycle.glow(p); check(g >= 0 && g <= 1, "glow 0..1");
        }
        check(maxStep < 0.002 && near(min, DayCycle.NIGHT_SKY) && near(max, 1), "небо змінюється плавно (макс. крок " + maxStep + ")");
        check(near(DayCycle.sky(0.999999), 1) || DayCycle.sky(0.999999) > 0.99, "кінець світанку = день (цикл замкнений)");
        check(DayCycle.sky(0.3) == 1 && DayCycle.sky(0.8) == DayCycle.NIGHT_SKY, "день яскравий, ніч темна");
        int dayLen = 0, nightLen = 0;
        for (int i = 0; i < 1000; i++) { double s = DayCycle.sky(i / 1000.0); if (s > 0.99) dayLen++; if (s < 0.13) nightLen++; }
        check(dayLen > nightLen * 2 - 20 && dayLen >= 550, "день довший за ніч: " + dayLen + " проти " + nightLen);
        for (double s : new double[]{DayCycle.NIGHT_SKY, 0.5, 1}) for (double gl : new double[]{0, 0.5, 1}) for (float c : DayCycle.skyColor(s, gl)) check(c >= 0 && c <= 1, "колір неба 0..1");
        check(DayCycle.presetPhase("день") == 0 && DayCycle.presetPhase("ніч") > 0.7 && DayCycle.presetPhase("нісенітниця") < 0, "назви часу");
        check(DayCycle.phaseName(0.1).equals("день") && DayCycle.phaseName(0.65).equals("вечір") && DayCycle.phaseName(0.8).equals("ніч") && DayCycle.phaseName(0.97).equals("світанок"), "назви фаз");
    }

    static void vitals() {
        Vitals v = new Vitals();
        check(v.health() == 20 && v.hunger() == 20 && !v.dead(), "старт: повне");
        v.damage(5);
        check(v.health() == 15, "шкода 5");
        for (int i = 0; i < Vitals.REGEN_TICKS + 1; i++) v.tick(false);
        check(v.health() == 16, "відновлення +1 за 4 с при ситості: " + v.health());
        check(v.hunger() < 20 || v.saturation() < 5, "відновлення виснажує");
        // голод
        Vitals h = new Vitals(20, 3, 0);
        h.exhaust(4 * 3);
        h.tick(false);
        check(h.hunger() == 0, "виснаження з'їло голод: " + h.hunger());
        h.damage(0); h.hurt(0, true);
        for (int i = 0; i < 80 * 40; i++) h.tick(false);
        check(h.health() == 1 && !h.dead(), "голод не вбиває, лишає 1: " + h.health());
        h.feed(10);
        check(h.hunger() == 10 && h.saturation() > 0, "їжа додає голод і насиченість");
        h.feed(1000);
        check(h.hunger() == 20, "голод не більше 20");
        // кінець
        Vitals d = new Vitals();
        d.damage(1e30);
        check(d.dead() && d.health() == 0, "величезна шкода = смерть, без переповнень");
        d.heal(10); d.feed(10); d.tick(false);
        check(d.dead() && d.health() == 0, "мертвого не лікують");
        d.respawn();
        check(!d.dead() && d.health() == 20 && d.hunger() == 20, "відродження");
        d.hurt(3, false);
        check(d.health() == 20, "після відродження коротка невразливість");
        for (int i = 0; i < Vitals.RESPAWN_INVULN; i++) d.tick(false);
        d.hurt(3, false);
        check(d.health() == 17, "перша шкода проходить");
        d.hurt(3, false);
        check(d.health() == 17, "невразливість після удару");
        for (int i = 0; i < Vitals.INVULN_TICKS; i++) d.tick(false);
        d.hurt(3, false);
        check(d.health() == 14, "після невразливості шкода знову проходить");
        d.damage(Double.NaN); d.damage(-5); d.heal(Double.NaN); d.feed(-1);
        check(d.health() == 14, "NaN/від'ємні значення ігноруються");
        // утоплення: 300 тіків повітря + 20 на перший удар
        Vitals w = new Vitals();
        for (int i = 0; i < 299; i++) w.tick(true);
        check(w.air() == 1 && w.health() == 20, "повітря спадає, шкоди ще нема");
        for (int i = 0; i < 21; i++) w.tick(true);
        check(w.health() == 18, "утоплення б'є на 2: " + w.health());
        for (int i = 0; i < 100; i++) w.tick(false);
        check(w.air() == 300, "на поверхні повітря повертається");
        Vitals wd = new Vitals();
        for (int i = 0; i < 300 + 20 * 11; i++) wd.tick(true);
        check(wd.dead(), "довге утоплення вбиває");
        // збереження стану
        Vitals r = new Vitals(7.5, 12, 99);
        check(r.health() == 7.5 && r.hunger() == 12 && r.saturation() <= 12, "відновлення зі збережених значень, насиченість <= голоду");
        check(new Vitals(0, 5, 0).dead() && new Vitals(Double.NaN, Double.NaN, Double.NaN).dead(), "биті значення не дають «безсмертя»");
        Vitals big = new Vitals(1e9, 1e9, 1e9);
        check(big.health() == 20 && big.hunger() == 20, "значення обмежені");
    }

    static void fall() {
        Vitals v = new Vitals();
        v.land(2); v.land(3);
        check(v.health() == 20, "падіння до 3 блоків безпечне");
        v.land(5);
        check(v.health() == 18, "падіння на 5 = шкода 2: " + v.health());
        // справжній гравець: падає з 20 блоків на землю
        Pos start = Pos.ofBlock(0, 30, 0);
        PlayerEntity p = new PlayerEntity((x, y, z) -> y <= 10, start);
        double landed = 0;
        for (int i = 0; i < 400 && landed == 0; i++) { p.tick(0.05, false, false, false, false, false, false, false); landed = p.takeLanded(); }
        check(landed > 15 && landed < 22, "гравець зафіксував висоту падіння ~19: " + landed);
        check(p.takeLanded() == 0, "значення падіння скидається після читання");
        // ходьба по землі й стрибок не дають падіння
        for (int i = 0; i < 40; i++) { p.tick(0.05, true, false, false, false, i == 10, false, false); check(p.takeLanded() <= 3, "ходьба/стрибок - не падіння"); }
        // падіння у воду - без шкоди
        PlayerEntity w = new PlayerEntity((x, y, z) -> y <= 0, (x, y, z) -> y >= 1 && y <= 12, Pos.ofBlock(0, 40, 0));
        double sum = 0;
        for (int i = 0; i < 300; i++) { w.tick(0.05, false, false, false, false, false, false, false); sum += w.takeLanded(); }
        check(sum == 0, "падіння у воду не завдає шкоди: " + sum);
    }

    static void world() throws Exception {
        File root = Files.createTempDirectory("vx-surv").toFile();
        Game g = new Game(new File(root, "saves"), null);
        g.createWorld("S", 5, WorldInfo.Type.NORMAL);
        World w = g.openWorld("S");
        Source p = Sources.player("T");
        check(w.time() == 0 && w.dayNumber() == 1 && w.skyLevel() == 1, "новий світ починається на початку дня");
        w.tick(); w.tick();
        check(w.time() == 2, "тік двигає час");
        List<String> out = g.commands.execute(p, w, null, "/time set ніч");
        check(w.dayPhase() > 0.7 && w.dayNumber() == 1 && w.skyLevel() == DayCycle.NIGHT_SKY, "/time set ніч: " + out);
        out = g.commands.execute(p, w, null, "/time add " + w.dayTicks());
        check(w.dayNumber() == 2, "/time add доба: " + out);
        out = g.commands.execute(p, w, null, "/time set 12345");
        check(w.time() % w.dayTicks() == 12345 && w.dayNumber() == 2, "/time set число: " + out);
        out = g.commands.execute(p, w, null, "/time add -999999999");
        check(w.time() == 0 || w.time() >= 0, "від'ємний час не виходить за 0: " + w.time());
        out = g.commands.execute(p, w, null, "/time add 9223372036854775807");
        long before = w.time();
        out = g.commands.execute(p, w, null, "/time add 9223372036854775807");
        check(out.get(0).contains("надто") && w.time() == before, "переповнення часу відхиляється: " + out);
        out = g.commands.execute(p, w, null, "/time set абракадабра");
        check(out.get(0).contains("Не час") && w.time() == before, "невідомий час: " + out);
        out = g.commands.execute(p, w, null, "/time foo 1");
        check(out.get(0).contains("Невідома дія"), "невідома дія: " + out);
        out = g.commands.execute(p, w, null, "/time");
        check(out.get(0).startsWith("День"), "/time показує: " + out);
        // здоров'я через команди
        Vitals v = w.vitals(p);
        g.commands.execute(p, w, null, "/hurt 7");
        check(v.health() == 13, "/hurt 7");
        g.commands.execute(p, w, null, "/heal");
        check(v.health() == 20, "/heal");
        g.commands.execute(p, w, null, "/eat 5");
        for (String bad : new String[]{"/hurt", "/hurt abc", "/hurt -3", "/hurt NaN", "/hurt Infinity", "/hurt 1e9", "/eat 0", "/eat"}) {
            double hp = v.health(), hg = v.hunger();
            out = g.commands.execute(p, w, null, bad);
            check(v.health() == hp && v.hunger() == hg && !out.isEmpty(), "погана команда " + bad + " нічого не міняє: " + out);
        }
        Source guest = Sources.of("guest", Source.Kind.PLAYER, 0, 10);
        check(g.commands.execute(guest, w, null, "/hurt 3").get(0).contains("прав"), "без прав /hurt заборонено");
        Source mod = Sources.mod("m");
        check(g.commands.execute(mod, w, null, "/heal").get(0).contains("нема здоров"), "у мода здоров'я нема");
        g.commands.execute(p, w, null, "/hurt 6");
        long t = w.time();
        w.close();
        WorldInfo info = WorldInfo.load(new File(root, "saves/S"));
        check(info.time == t && info.hasVitals && info.health == 14 && info.hunger <= 20, "час і здоров'я збережено: " + info.time + " " + info.health);
        // перезавантаження
        Game g2 = new Game(new File(root, "saves"), null);
        World w2 = g2.openWorld("S");
        Source p2 = Sources.player("T");
        check(w2.time() == t && w2.vitals(p2).health() == 14, "після перезавантаження стан той самий");
        g2.commands.execute(p2, w2, null, "/hurt 100");
        check(w2.vitals(p2).dead(), "смерть");
        w2.close();
        World w3 = new Game(new File(root, "saves"), null).openWorld("S");
        check(!w3.vitals(Sources.player("T")).dead() && w3.vitals(Sources.player("T")).health() == 20, "збережена смерть не робить світ нежилим: відродження при вході");
        w3.close();
        // старий світ без полів
        File old = new File(root, "saves/S/voxelin.world");
        List<String> lines = new ArrayList<>();
        for (String l : Files.readAllLines(old.toPath())) if (!l.startsWith("time=") && !l.startsWith("dayMinutes=") && !l.startsWith("hasVitals") && !l.startsWith("health") && !l.startsWith("hunger") && !l.startsWith("saturation")) lines.add(l);
        Files.write(old.toPath(), lines);
        WorldInfo oi = WorldInfo.load(new File(root, "saves/S"));
        check(oi.time == 0 && !oi.hasVitals && oi.dayMinutes == DayCycle.DEFAULT_MINUTES, "старий світ без полів - значення за замовчуванням");
        Files.write(old.toPath(), (String.join("\n", lines) + "\ntime=abc\ndayMinutes=nan\nhasVitals=true\nhealth=x\nhunger=1\n").getBytes());
        oi = WorldInfo.load(new File(root, "saves/S"));
        check(oi.time == 0 && oi.dayMinutes == DayCycle.DEFAULT_MINUTES && !oi.hasVitals, "биті поля не ламають завантаження");
    }
}
