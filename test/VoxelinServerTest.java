import voxelin.core.*;
import voxelin.net.*;
import voxelin.server.DedicatedServer;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Сервер без вікна: налаштування, вхід гостя без хоста-гравця, консоль, збереження при зупинці. */
public class VoxelinServerTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    static final List<String> log = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("vx-server").toFile();
        File conf = new File(root, "server.properties"), saves = new File(root, "saves"), mods = new File(root, "mods");
        String[] base = {"--config", conf.getPath(), "--saves", saves.getPath(), "--mods", mods.getPath()};

        // налаштування: перший запуск пише файл з кодом і прапорцями; далі код той самий, прапорці - лише на запуск
        DedicatedServer.Config c1 = DedicatedServer.Config.parse(cat(base, "--world", "Острів", "--seed", "7", "--flat", "--creative"));
        check(conf.isFile() && c1.code != null && NetServer.validCode(c1.code), "server.properties створено з кодом");
        DedicatedServer.Config c2 = DedicatedServer.Config.parse(cat(base, "--port", "0"));
        check(c2.code.equals(c1.code) && c2.world.equals("Острів") && c2.seed == 7 && c2.flat && !c2.survival, "другий запуск: код і світ з файлу");
        check(DedicatedServer.Config.parse(base).port == Protocol.DEFAULT_PORT, "--port не записався у файл");
        for (String[] bad : new String[][]{{"--code", "ab"}, {"--code", "ABC-12"}, {"--port", "70000"}, {"--world", "../x"}, {"--name", "a b"}, {"--wat"}})
            check(threw(() -> DedicatedServer.Config.parse(cat(base, bad))), "погане налаштування: " + String.join(" ", bad));

        // запуск: виживання, свій код
        DedicatedServer.Config cfg = DedicatedServer.Config.parse(cat(base, "--port", "0", "--survival", "--code", "Ferma42"));
        DedicatedServer s = new DedicatedServer(cfg);
        s.out = log::add;
        check(s.net.dedicated() && s.net.code.equals("FERMA42") && s.net.port > 0, "сервер слухає, код великими літерами");
        Pos spawn = s.spawn();
        check(s.world.chunk(spawn.cx, spawn.cz) != null, "точка появи завантажена ще до гостей");
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        s.game.events.on("test", voxelin.api.event.PlayerJoinEvent.class, e -> events.add("join " + e.name + " " + e.guest));
        s.game.events.on("test", voxelin.api.event.PlayerLeaveEvent.class, e -> events.add("leave " + e.name + " " + e.reason));
        s.game.events.on("test", voxelin.api.event.ChatEvent.class, e -> { events.add("chat " + e.name + " " + e.text); if (e.text.contains("реклама")) e.cancel(); });
        Thread loop = new Thread(s::run, "voxelin-world");
        loop.start();

        NetClient cli = join(s, "Гість", "ferma42");
        check(cli.welcome.survival() && cli.welcome.worldName().equals("Острів"), "привітання: виживання, світ");
        check(cli.welcome.dedicated() && cli.welcome.where().equals("сервер «Сервер», світ «Острів»"), "гість сервера бачить «сервер», а не «світ гравця Сервер»: " + cli.welcome.where());
        check(dist(cli.welcome.spawn(), spawn) < 0.01, "гість з'являється на точці появи світу");
        check(threwJoin(s, "Сервер", "FERMA42").contains("зайняте"), "ім'я сервера зайняте");
        World mirror = s.game.openMirror(cli.welcome.worldName(), cli.welcome.seed(), cli.welcome.type(), cli.welcome.palette(), true);
        cli.attach(mirror);
        Pos at = cli.welcome.spawn();
        wait(() -> { cli.poll(); mirror.update(at.cx, at.cz, 2); return mirror.chunk(at.cx, at.cz) != null; }, "чанк точки появи дійшов до гостя");
        wait(() -> { cli.poll(); return cli.players() != null; }, "список гравців");
        Thread.sleep(300); cli.poll();
        check(cli.players().isEmpty(), "хоста-гравця гості не бачать");
        check(log.stream().anyMatch(l -> l.contains("Гість приєднався")), "консоль бачить вхід гостя");

        // гість ставить блок - у світі сервера
        long bx = at.blockX() + 1, bz = at.blockZ();
        int y0 = (int) Math.floor(at.y - voxelin.core.entity.PlayerEntity.EYE + 0.05);
        while (y0 > 1 && id(s, bx, y0 - 1, bz) == 0) y0--; // вниз до землі
        while (id(s, bx, y0, bz) != 0) y0++; // перше повітря
        final int py = y0;
        cli.sendPos(at, 0, 0);
        cli.placeBlock(bx, py, bz, "voxelin:stone");
        wait(() -> locked(s, () -> s.world.getBlock(bx, py, bz).name().equals("voxelin:stone")), "камінь гостя у світі сервера");

        // консоль
        s.enqueue("say вітаю на сервері");
        wait(() -> { cli.poll(); return cli.takeChat().stream().anyMatch(l -> l.contains("<Сервер> вітаю на сервері")); }, "say доходить до гостя");
        log.clear();
        s.enqueue("list");
        wait(() -> log.stream().anyMatch(l -> l.contains("у грі 1/") && l.contains("Гість")), "list");
        s.enqueue("status");
        wait(() -> log.stream().anyMatch(l -> l.contains("гостей 1")), "status");
        s.enqueue("/time set 6000");
        wait(() -> log.stream().anyMatch(l -> l.contains("Час")), "команда гри від консолі");

        // події модів: вхід, чат (мод може не пропустити), вихід
        check(events.contains("join Гість true"), "PlayerJoinEvent для гостя: " + events);
        cli.chat("купуйте реклама");
        cli.chat("привіт");
        wait(() -> { cli.poll(); return cli.takeChat().stream().anyMatch(l -> l.contains("<Гість> привіт")); }, "звичайний чат гостя доходить");
        check(events.contains("chat Гість купуйте реклама") && log.stream().noneMatch(l -> l.contains("реклама")), "ChatEvent: мод скасував повідомлення - ніхто його не побачив");
        // інвентар гостя зберігається у світі сервера
        Inventory inv = new Inventory();
        inv.add("voxelin:stone", 5, 0);
        cli.sendInv(inv);
        Thread.sleep(200);
        Pos moved = at.copy(); moved.add(10, 0, 0); moved.normalize(); // відійшов на 10 блоків - сервер запам'ятає
        cli.sendPos(moved, 0, 0);
        Thread.sleep(300);
        s.enqueue("kick Гість дякую");
        wait(() -> { cli.poll(); return cli.kicked() != null; }, "kick");
        check(cli.kicked().contains("дякую"), "причина кіка дійшла: " + cli.kicked());
        check(events.contains("leave Гість дякую"), "PlayerLeaveEvent з причиною: " + events);
        cli.close();
        File invFile = new File(s.world.dir, "guests/гість.inv");
        wait(invFile::isFile, "інвентар гостя записано при виході");
        check(Files.readString(invFile.toPath()).contains("voxelin:stone"), "у файлі інвентаря камінь");
        mirror.close();
        // повторний вхід: туди, де вийшов
        NetClient back = join(s, "Гість", "FERMA42");
        World mirror2 = s.game.openMirror(back.welcome.worldName(), back.welcome.seed(), back.welcome.type(), back.welcome.palette(), true);
        back.attach(mirror2); // як у грі: після входу підключається дзеркало світу
        wait(() -> { back.poll(); Pos t = back.takeTeleport(); if (t != null) tp[0] = t; return tp[0] != null; }, "повторний вхід: телепорт на місце виходу");
        check(dist(tp[0], moved) < 0.01 && dist(back.welcome.spawn(), spawn) < 0.01, "гість повертається туди, де вийшов, а точка відродження - та сама");
        back.close();
        mirror2.close();
        wait(() -> s.net.guests().isEmpty(), "гість вийшов");

        // зупинка з консолі: світ зберігається
        s.enqueue("stop");
        loop.join(30_000);
        check(!loop.isAlive(), "stop зупиняє сервер");
        check(log.stream().anyMatch(l -> l.contains("сервер зупинено")), "повідомлення про зупинку");
        voxelin.loader.Game g = new voxelin.loader.Game(saves, null);
        World w = g.openWorld("Острів");
        for (int i = 0; i < 600 && w.chunk(bx >> 4, bz >> 4) == null; i++) { w.update(bx >> 4, bz >> 4, 1); Thread.sleep(5); }
        check(w.getBlock(bx, py, bz).name().equals("voxelin:stone"), "камінь гостя пережив перезапуск");
        w.close();
        System.out.println("VoxelinServerTest: " + n + " перевірок OK");
        System.exit(0);
    }

    static final Pos[] tp = new Pos[1];

    static int id(DedicatedServer s, long x, int y, long z) { return locked(s, () -> s.world.blockId(x, y, z)); }

    static <T> T locked(DedicatedServer s, Callable<T> f) {
        s.world.lock.lock();
        try { return f.call(); } catch (Exception e) { throw new RuntimeException(e); } finally { s.world.lock.unlock(); }
    }

    static NetClient join(DedicatedServer s, String name, String code) throws Exception {
        FutureTask<NetClient> f = new FutureTask<>(() -> NetClient.connect("127.0.0.1", s.net.port, name, code, ""));
        new Thread(f).start();
        return f.get(15, TimeUnit.SECONDS);
    }

    static String threwJoin(DedicatedServer s, String name, String code) {
        try { join(s, name, code).close(); return ""; } catch (Exception e) { return String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()); }
    }

    static void wait(java.util.function.BooleanSupplier ok, String what) throws InterruptedException {
        for (int i = 0; i < 500; i++) { if (ok.getAsBoolean()) { check(true, what); return; } Thread.sleep(10); }
        check(ok.getAsBoolean(), "не дочекались: " + what);
    }

    static boolean threw(Runnable r) { try { r.run(); return false; } catch (RuntimeException e) { return true; } }

    static String[] cat(String[] a, String... b) { String[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }

    static double dist(Pos a, Pos b) { double dx = a.relX(b.cx) - b.x, dz = a.relZ(b.cz) - b.z, dy = a.y - b.y; return Math.sqrt(dx * dx + dy * dy + dz * dz); }
}
