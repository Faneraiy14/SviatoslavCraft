import voxelin.core.*;
import voxelin.core.entity.PlayerEntity;
import voxelin.core.entity.Vitals;
import voxelin.loader.*;
import voxelin.api.*;

import java.io.File;
import java.util.List;

/** Творчий режим: політ подвійним пробілом, Shift - вниз, приземлення вимикає; у виживанні польоту нема. Підказки команд у чаті. */
public class VoxelinFlyTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static final double DT = 0.05;

    static void tick(PlayerEntity p, boolean jump, boolean shift) { p.tick(DT, false, false, false, false, jump, false, shift); }
    static void tap(PlayerEntity p) { tick(p, true, false); tick(p, false, false); }

    public static void main(String[] a) {
        PlayerEntity.Solid floor = (x, y, z) -> y < 10;
        PlayerEntity p = new PlayerEntity(floor, Pos.ofBlock(0, 10 + PlayerEntity.EYE + 0.01, 0));
        for (int i = 0; i < 10; i++) tick(p, false, false);
        check(p.onGround(), "стоїть на землі");
        // виживання: подвійний пробіл - лише стрибки
        p.canFly = false;
        tap(p); tap(p);
        check(!p.flying, "без canFly польоту нема");
        for (int i = 0; i < 40; i++) tick(p, false, false);
        // творчий: подвійний пробіл
        p.canFly = true;
        tap(p); tap(p);
        check(p.flying, "подвійний пробіл вмикає політ");
        double y0 = p.pos.y;
        for (int i = 0; i < 40; i++) tick(p, false, false);
        check(Math.abs(p.pos.y - y0) < 1.5 && p.flying, "у польоті без клавіш - висить (нема гравітації): " + (p.pos.y - y0));
        for (int i = 0; i < 20; i++) tick(p, true, false);
        check(p.pos.y > y0 + 4, "пробіл - вгору: " + (p.pos.y - y0));
        double hi = p.pos.y;
        for (int i = 0; i < 10; i++) tick(p, false, true);
        check(p.pos.y < hi - 1 && p.flying, "Shift - вниз");
        for (int i = 0; i < 200 && p.flying; i++) tick(p, false, true);
        check(!p.flying && p.onGround(), "приземлився - політ вимкнувся");
        check(p.takeLanded() == 0, "після польоту висота падіння не рахується");
        tick(p, false, false);
        tap(p); tap(p);
        check(p.flying, "знову злетів");
        tap(p); tap(p);
        check(!p.flying, "подвійний пробіл у повітрі - вимкнути політ");
        p.flying = true; p.canFly = false; tick(p, false, false);
        check(!p.flying, "режим змінився на виживання - політ скинуто");
        // повільні натиски - не подвійний
        PlayerEntity q = new PlayerEntity(floor, Pos.ofBlock(0, 10 + PlayerEntity.EYE + 0.01, 0));
        q.canFly = true;
        for (int i = 0; i < 10; i++) tick(q, false, false);
        tap(q); for (int i = 0; i < 12; i++) tick(q, false, false); tap(q);
        check(!q.flying, "два повільні натиски - не політ");

        // творчий режим: безсмертя й голод
        Vitals v = new Vitals();
        v.setGod(true);
        v.hurt(50, true); v.exhaust(40); for (int i = 0; i < 400; i++) v.tick(true);
        check(!v.dead() && v.health() == 20 && v.hunger() == 20, "творчий: не гине, не голодує, не тоне");

        // підказки команд
        Game g = new Game(new File("nope"), null);
        List<CommandRegistry.Entry> s = g.commands.suggest("/g", 99);
        check(s.stream().anyMatch(e -> e.name.equals("give")) && s.stream().anyMatch(e -> e.name.equals("gamemode")) && s.stream().allMatch(e -> e.name.startsWith("g")), "/g -> give, gamemode...");
        check(g.commands.suggest("/", 99).size() == g.commands.all().size(), "«/» - усі команди");
        check(g.commands.suggest("/give ", 99).size() == 1 && g.commands.suggest("/give ", 99).get(0).usage.length() > 0, "після пробілу - одна команда з використанням");
        check(g.commands.suggest("/g", 0).stream().noneMatch(e -> e.name.equals("give")), "без читів - лише доступні команди");
        check(g.commands.suggest("/s", 0).stream().anyMatch(e -> e.name.equals("seed")), "без читів /seed є");
        check(g.commands.suggest("привіт", 99).isEmpty() && g.commands.suggest("/zzz", 99).isEmpty(), "не команда - підказок нема");
        g.commands.register("mymod", "hello_mod", "<ім'я>", 0, k -> k.reply("hi"));
        check(g.commands.suggest("/hel", 0).stream().anyMatch(e -> e.name.equals("hello_mod")), "команди модів теж");
        System.out.println("VoxelinFlyTest: " + n + " перевірок OK");
    }
}
