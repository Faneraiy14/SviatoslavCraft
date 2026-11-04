import voxelin.core.*;
import voxelin.core.entity.Mob;
import voxelin.loader.*;
import voxelin.net.*;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Гра по мережі без вікна: хост і гість на 127.0.0.1 - вхід за кодом, чанки, блоки в обидва боки, перевірки хоста, чат, гравці, істоти, вихід. */
public class VoxelinNetTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    static World host, mirror;
    static NetServer srv;
    static NetClient cli;
    static Pos hostPos;
    static final List<String> hostChat = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        // адреси й імена
        check(Arrays.equals(Protocol.parseAddress("192.168.1.5"), new Object[]{"192.168.1.5", Protocol.DEFAULT_PORT}), "адреса без порту");
        check(Arrays.equals(Protocol.parseAddress("pc.local:25000"), new Object[]{"pc.local", 25000}) && Arrays.equals(Protocol.parseAddress("[::1]:7"), new Object[]{"::1", 7}), "з портом, IPv6");
        for (String bad : new String[]{"", "a:0", "a:70000", "a b", "a:x", "[::1", "a;rm"}) check(Protocol.parseAddress(bad) == null, "погана адреса: " + bad);
        check("Гість_1".equals(Protocol.cleanName(" Гість_1 ")) && Protocol.cleanName("a b") == null && Protocol.cleanName("x".repeat(17)) == null && Protocol.cleanName("<b>") == null, "імена");
        check(Protocol.cleanChat("a\u0000b\nc").equals("abc") && Protocol.cleanChat("x".repeat(500)).length() == Protocol.MAX_CHAT, "чат чиститься");
        String code = Protocol.newCode();
        check(code.length() == 6 && code.chars().allMatch(c -> Protocol.CODE_CHARS.indexOf(c) >= 0), "код запрошення");

        File root = Files.createTempDirectory("vx-net").toFile();
        Game g = new Game(new File(root, "saves"), null);
        g.createWorld("Хата", 5, WorldInfo.Type.FLAT);
        host = g.openWorld("Хата");
        host.naturalSpawning = false;
        for (int i = 0; i < 900 && (host.chunk(0, 0) == null || host.chunk(-1, -1) == null || host.chunk(-1, 0) == null || host.chunk(0, -1) == null); i++) { host.update(0, 0, 3); Thread.sleep(5); }
        int top = 0;
        for (int x = -3; x <= 6; x++) for (int z = -3; z <= 6; z++) { int y = Chunk.HEIGHT - 1; while (y > 0 && host.blockId(x, y, z) == 0) y--; top = Math.max(top, y); }
        final int ground = top; // найвищий непорожній блок довкола - вище все порожнє
        hostPos = Pos.ofBlock(0, ground + 1 + voxelin.core.entity.PlayerEntity.EYE, 0);
        srv = new NetServer(host, 0, "Хост", "builtin:traveler|none|none|none|none|none", hostChat::add);
        check(srv.port > 0 && srv.code.length() == 6, "сервер слухає порт " + srv.port);

        // відмови
        check(joinErr("Гість", "AAAAAA").contains("код"), "неправильний код");
        check(joinErr("Хост", srv.code).contains("зайняте"), "ім'я хоста зайняте");
        check(joinErr("a b", srv.code).contains("ім'я"), "погане ім'я - ще до з'єднання");

        // вхід
        cli = join("Гість", srv.code.toLowerCase());
        check(cli.welcome.seed() == 5 && cli.welcome.type() == WorldInfo.Type.FLAT && cli.welcome.worldName().equals("Хата") && cli.welcome.palette().equals(host.info.palette), "привітання: зерно, тип, палітра");
        check(Math.abs(NetServerDist(cli.welcome.spawn(), hostPos) - 2.5) < 0.01, "гість з'являється за 2.5 блока від хоста");
        hostPos = cli.welcome.spawn().copy(); // далі хост стоїть там само, де гість (перевірки відстаней простіші)
        check(joinErr("гість", srv.code).contains("зайняте"), "те саме ім'я (без огляду на регістр) - відмова");
        mirror = g.openMirror(cli.welcome.worldName(), cli.welcome.seed(), cli.welcome.type(), cli.welcome.palette());
        check(mirror.remote() && mirror.creative(), "дзеркало: віддалене, творчий режим");
        cli.attach(mirror);
        run(() -> mirror.chunk(0, 0) != null && mirror.chunk(1, 1) != null && mirror.chunk(-1, 0) != null, 400);
        boolean same = true;
        for (int x = 0; x < 16; x += 5) for (int z = 0; z < 16; z += 5) for (int y = 0; y < Chunk.HEIGHT; y++) if (!host.getBlock(x, y, z).name().equals(mirror.getBlock(x, y, z).name())) same = false;
        check(same, "чанк у дзеркалі такий самий, як у хоста");
        check(srv.guests().size() == 1 && srv.guests().get(0).name.equals("Гість") && hostChat.contains("Гість приєднався до гри."), "хост бачить гостя");

        // хост міняє блок - гість бачить
        BlockType gold = host.blocks.byName("voxelin:gold_block") != null ? host.blocks.byName("voxelin:gold_block") : host.blocks.byName("voxelin:bricks");
        check(host.setBlock(Sources.player("Хост"), 3, ground + 1, 3, gold), "хост ставить блок");
        run(() -> mirror.getBlock(3, ground + 1, 3).name().equals(gold.name), 100);
        check(true, "зміна хоста дійшла до гостя");

        // гість ставить і ламає
        cli.sendPos(hostPos, 0, 0);
        cli.placeBlock(2, ground + 1, 2, "voxelin:stone");
        run(() -> host.getBlock(2, ground + 1, 2).name().equals("voxelin:stone") && mirror.getBlock(2, ground + 1, 2).name().equals("voxelin:stone"), 100);
        check(true, "гість поставив камінь: у хоста й у дзеркалі");
        cli.breakBlock(2, ground + 1, 2);
        run(() -> host.blockId(2, ground + 1, 2) == 0 && mirror.blockId(2, ground + 1, 2) == 0, 100);
        check(true, "гість зламав камінь");
        // перевірки хоста
        cli.placeBlock(40, ground + 1, 40, "voxelin:stone");                // далеко від гостя
        cli.placeBlock(1, ground + 1, 1, "voxelin:bedrock");                // незламний
        cli.placeBlock(1, ground + 2, 2, "voxelin:nope");                   // невідомий
        cli.placeBlock(hostPos.blockX(), ground + 1, hostPos.blockZ(), "voxelin:stone"); // у тілі хоста/гостя
        cli.breakBlock(3, 0, 3);                                            // далеко вниз
        cli.placeBlock(1, ground + 1, 3, Infection.EGG);                    // яйце зараження
        cli.placeBlock(3, ground + 1, 3, "voxelin:stone");                  // зайнято золотом
        tickAll(30);
        check(host.blockId(40, ground + 1, 40) <= 0 && host.blockId(1, ground + 1, 1) == 0 && host.blockId(1, ground + 2, 2) == 0 && host.blockId(hostPos.blockX(), ground + 1, hostPos.blockZ()) == 0, "далеко, незламне, невідоме, у тілі - відхилено");
        check(host.getBlock(3, 0, 3).name().equals(mirror.getBlock(3, 0, 3).name()) && host.blockId(1, ground + 1, 3) == 0 && host.getBlock(3, ground + 1, 3) == gold, "основа ціла, яйце не ставиться, зайняте не перезаписується");
        // частота: 200 запитів за раз - пройде не більше запасу
        for (int i = 0; i < 200; i++) cli.placeBlock(-2 + i % 5, ground + 1 + i / 25, -2 + (i / 5) % 5, "voxelin:dirt");
        tickAll(2);
        int placed = 0;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = ground + 1; y < ground + 9; y++) if (host.getBlock(x, y, z).name().equals("voxelin:dirt")) placed++;
        check(placed > 0 && placed <= 24, "обмеження частоти дій: " + placed);

        // чат
        host.info.time = 5000;
        cli.chat("привіт усім");
        cli.chat("/time set 0");
        tickAll(10);
        List<String> got = cli.takeChat();
        check(hostChat.contains("<Гість> привіт усім") && got.contains("<Гість> привіт усім"), "чат гостя - хосту й гостям: " + got);
        check(got.contains("Команди в чужому світі недоступні.") && host.info.time == 5000, "команди гостя не виконуються");
        srv.say("вітаю");
        tickAll(5);
        check(cli.takeChat().contains("<Хост> вітаю"), "чат хоста - гостям");

        // гравці
        check(cli.players().size() == 1 && cli.players().iterator().next().name.equals("Хост") && cli.players().iterator().next().look.startsWith("builtin:"), "гість бачить хоста з виглядом");
        Pos step = hostPos.copy(); step.add(3, 0, 0);
        cli.sendPos(step, 90, 10);
        tickAll(5);
        check(NetServerDist(srv.guests().get(0).pos, step) < 0.01 && srv.guests().get(0).yaw == 90, "хост бачить, куди пішов гість");
        Pos jump = hostPos.copy(); jump.add(500, 0, 0);
        cli.sendPos(jump, 0, 0);
        tickAll(5);
        Pos back = cli.takeTeleport();
        check(back != null && NetServerDist(back, step) < 0.01 && NetServerDist(srv.guests().get(0).pos, step) < 0.01, "стрибок на 500 блоків - назад");

        // істоти
        MobType cow = host.mobTypes.all().stream().filter(t -> t.passive()).findFirst().orElseThrow();
        Mob m = host.spawnMob(cow, Pos.ofBlock(6, ground + 1, 6));
        run(() -> mirror.mobs().stream().anyMatch(x -> x.type.name.equals(cow.name)), 60);
        check(true, "гість бачить істоту хоста");
        m.pos.x += 1; m.bodyYaw = 45;
        tickAll(20);
        Mob mm = mirror.mobs().stream().filter(x -> x.type.name.equals(cow.name)).findFirst().orElseThrow();
        check(Math.abs(mm.pos.relX(m.pos.cx) - m.pos.x) < 0.1 && mm.bodyYaw == 45, "істота рухається в дзеркалі");
        host.killAll(cow);
        run(() -> mirror.mobs().isEmpty(), 60);
        check(true, "зникла в хоста - зникла в гостя");

        // сміття в мережі: хост живе далі
        try (Socket s = new Socket("127.0.0.1", srv.port)) {
            DataOutputStream o = new DataOutputStream(s.getOutputStream());
            o.writeInt(Integer.MAX_VALUE); o.flush();
            tickAll(10);
        }
        try (Socket s = new Socket("127.0.0.1", srv.port)) {
            DataOutputStream o = new DataOutputStream(s.getOutputStream());
            Protocol.write(o, Protocol.frame(Protocol.C_PLACE, x -> x.writeLong(0)));
            o.flush();
            tickAll(10);
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] f = Protocol.read(in);
            check(f[0] == Protocol.S_REJECT, "без привітання - відмова");
        }
        check(srv.guests().size() == 1 && !srv.closed(), "після сміття хост працює, гість на місці");

        // вихід гостя й закриття хоста
        NetClient c2 = join("Друг", srv.code);
        tickAll(5);
        check(srv.guests().size() == 2, "другий гість");
        c2.close();
        run(() -> srv.guests().size() == 1, 60);
        check(hostChat.stream().anyMatch(s -> s.startsWith("Друг вийшов")), "вихід гостя - одразу");
        // перебір коду: після 5 хибних - пауза навіть для правильного
        for (int i = 0; i < 4; i++) joinErr("Злий", "ZZZZZZ");
        check(joinErr("Злий", srv.code).contains("забагато"), "перебір коду блокується");

        srv.close();
        run(() -> cli.kicked() != null, 100);
        check(cli.kicked().contains("закрив"), "хост закрив гру - гість дізнався: " + cli.kicked());
        cli.close();
        File md = mirror.dir;
        mirror.close();
        WorldArchive.deleteTree(md);
        check(!md.exists(), "тека дзеркала прибрана");
        host.close();
        System.out.println("VoxelinNetTest: " + n + " перевірок OK");
        System.exit(0);
    }

    static double NetServerDist(Pos a, Pos b) { double dx = a.relX(b.cx) - b.x, dz = a.relZ(b.cz) - b.z, dy = a.y - b.y; return Math.sqrt(dx * dx + dy * dy + dz * dz); }

    static void tickAll(int ticks) throws InterruptedException {
        for (int i = 0; i < ticks; i++) {
            srv.tick(hostPos, 0, 0);
            host.update(hostPos.cx, hostPos.cz, 3);
            if (cli != null && mirror != null) { cli.poll(); mirror.update(hostPos.cx, hostPos.cz, 2); }
            Thread.sleep(10);
        }
    }

    static void run(java.util.function.BooleanSupplier until, int maxTicks) throws InterruptedException {
        for (int i = 0; i < maxTicks; i++) { if (until.getAsBoolean()) return; tickAll(1); }
        check(until.getAsBoolean(), "не дочекались за " + maxTicks + " тіків (рядок " + new Throwable().getStackTrace()[1].getLineNumber() + ")");
    }

    static NetClient join(String name, String code) throws Exception {
        FutureTask<NetClient> f = new FutureTask<>(() -> NetClient.connect("127.0.0.1", srv.port, name, code, "builtin:traveler|none|none|none|none|none"));
        new Thread(f).start();
        while (!f.isDone()) tickAll(1);
        return f.get();
    }

    static String joinErr(String name, String code) throws Exception {
        try { join(name, code); return ""; } catch (ExecutionException e) { return e.getCause().getMessage(); }
    }
}
