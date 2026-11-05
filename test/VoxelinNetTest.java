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
        check(!cli.welcome.dedicated() && cli.welcome.where().equals("світ «Хата» гравця " + cli.welcome.hostName()), "гість гравця: «світ … гравця …»: " + cli.welcome.where());
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
        cli.breakBlock(2, ground + 1, 2, null);
        run(() -> host.blockId(2, ground + 1, 2) == 0 && mirror.blockId(2, ground + 1, 2) == 0, 100);
        check(true, "гість зламав камінь");
        // перевірки хоста
        cli.placeBlock(40, ground + 1, 40, "voxelin:stone");                // далеко від гостя
        cli.placeBlock(1, ground + 1, 1, "voxelin:bedrock");                // незламний
        cli.placeBlock(1, ground + 2, 2, "voxelin:nope");                   // невідомий
        cli.placeBlock(hostPos.blockX(), ground + 1, hostPos.blockZ(), "voxelin:stone"); // у тілі хоста/гостя
        cli.breakBlock(3, 0, 3, null);                                           // далеко вниз
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
        // режим виживання: хост обирає; дроп зламаного блока - гостю
        check(!srv.survival, "типово гості - у творчому");
        srv = new NetServer(host, 0, "Хост", "", hostChat::add, true);
        cli = null; mirror = null;
        NetClient sv = join("Шахтар", srv.code);
        check(sv.welcome.survival(), "привітання каже: виживання");
        World m2 = g.openMirror(sv.welcome.worldName(), sv.welcome.seed(), sv.welcome.type(), sv.welcome.palette(), true);
        check(!m2.creative() && m2.rule(GameRules.KEEP_INVENTORY), "дзеркало у виживанні, речі при смерті лишаються");
        sv.attach(m2);
        cli = sv; mirror = m2;
        hostPos = sv.welcome.spawn().copy();
        run(() -> m2.chunk(hostPos.cx, hostPos.cz) != null, 400);
        long bx = hostPos.blockX() + 1, bz = hostPos.blockZ();
        host.setBlock(Sources.player("Хост"), bx, ground + 1, bz, host.blocks.byName("voxelin:dirt"));
        tickAll(3);
        sv.sendPos(hostPos, 0, 0);
        sv.breakBlock(bx, ground + 1, bz, "voxelin:wooden_shovel");
        run(() -> host.blockId(bx, ground + 1, bz) == 0, 100);
        List<Object[]> gifts = new ArrayList<>();
        run(() -> { gifts.addAll(sv.takeGifts()); return !gifts.isEmpty(); }, 60);
        check(gifts.size() == 1 && gifts.get(0)[0].equals("voxelin:dirt") && (int) gifts.get(0)[1] == 1, "дроп землі прийшов гостю");
        Pos far = sv.welcome.spawn().copy(); far.add(60, 0, 0);
        sv.sendPos(far, 0, 0); tickAll(3); sv.takeTeleport();
        sv.sendPos(sv.welcome.spawn(), 0, 0); tickAll(5);
        check(sv.takeTeleport() == null || NetServerDist(srv.guests().get(0).pos, sv.welcome.spawn()) < 0.01, "відродження в точці появи - не стрибок");
        check(NetServerDist(srv.guests().get(0).pos, sv.welcome.spawn()) < 0.01, "хост прийняв відродження");
        // істоти хоста нападають на гостя (виживання)
        check(host.targets().size() == 1 && host.targetFor(sv.welcome.spawn()) != null, "гість - ціль для істот");
        MobType foe = host.mobTypes.all().stream().filter(t -> t.hostile() && t.attackDamage > 0 && !t.flying && !t.ranged() && !t.boss).findFirst().orElseThrow();
        host.naturalSpawning = false;
        host.info.time = 18000; // ніч - ніхто не горить
        Pos near = sv.welcome.spawn().copy(); near.add(1.2, -voxelin.core.entity.PlayerEntity.EYE + 0.05, 0);
        Mob z = host.spawnMob(foe, near);
        List<double[]> hits = new ArrayList<>();
        for (int i = 0; i < 200 && hits.isEmpty(); i++) { host.tick(); tickAll(1); hits.addAll(sv.takeHurts()); }
        check(!hits.isEmpty() && hits.get(0)[0] > 0, foe.name + " вдарив гостя: " + (hits.isEmpty() ? "-" : hits.get(0)[0]));
        sv.sendState(0, true);
        tickAll(3);
        check(host.targetFor(sv.welcome.spawn()) == null || !host.targetFor(sv.welcome.spawn()).alive(), "мертвого гостя не б'ють");
        host.killAll(foe);
        sv.sendState(20, false);
        // інвентар гостя зберігає хост
        Inventory gi = new Inventory(host.itemTypes);
        gi.add("voxelin:dirt", 7);
        sv.sendInv(gi);
        tickAll(5);
        check(new File(host.dir, "guests/шахтар.inv").isFile(), "інвентар гостя записано в теку світу");
        // скриня: кліки рахує хост, гість отримує готовий стан
        long cx = hostPos.blockX() + 1, cz = hostPos.blockZ() + 1; int cy = ground + 1;
        host.setBlock(Sources.player("Хост"), cx, cy, cz, host.blocks.byName("voxelin:chest"));
        tickAll(3);
        sv.openContainer(cx, cy, cz, gi);
        NetClient.MenuState[] ms = new NetClient.MenuState[1];
        java.util.function.Predicate<java.util.function.Predicate<NetClient.MenuState>> menuWhere = cond -> { NetClient.MenuState x = sv.takeMenu(); if (x != null) ms[0] = x; return ms[0] != null && cond.test(ms[0]); };
        run(() -> menuWhere.test(x -> true), 60);
        check(ms[0].kind().equals("chest") && "voxelin:dirt|7|0".equals(ms[0].slots()[0]) && ms[0].data().startsWith("-;"), "гість відкрив скриню хоста");
        sv.menuOp(Menu.OP_CLICK, 0, 0, true); // shift: земля в скриню
        run(() -> menuWhere.test(x -> x.slots()[0] == null), 60);
        check(host.chestAt(cx, cy, cz).get(0) != null && host.chestAt(cx, cy, cz).get(0).count == 7 && ms[0].data().startsWith("voxelin:dirt|7|0"), "shift-клік: земля в скрині хоста");
        host.chestAt(cx, cy, cz).set(5, new Inventory.Stack("voxelin:stone", 3)); // хост (інший гравець) поклав своє - гість бачить
        run(() -> menuWhere.test(x -> x.data().contains("voxelin:stone|3")), 20);
        check(true, "зміна скрині хостом дійшла до відкритого вікна гостя");
        sv.menuOp(Menu.OP_CLICK, ChestMenu.CHEST + 5, 0, false); // камінь - у курсор
        run(() -> menuWhere.test(x -> x.cursor().equals("voxelin:stone|3|0")), 20);
        check(host.chestAt(cx, cy, cz).get(5) == null, "камінь у курсорі гостя, зі скрині зник");
        sv.menuOp(Menu.OP_CLICK, 20, 0, false); // камінь у сховище (слот 20), сортування (хост) - на початок сховища (9)
        run(() -> menuWhere.test(x -> "voxelin:stone|3|0".equals(x.slots()[20])), 20);
        sv.menuOp(Menu.OP_SORT, 0, 0, false);
        run(() -> menuWhere.test(x -> "voxelin:stone|3|0".equals(x.slots()[9])), 20);
        check(true, "сортування у вікні скрині робить хост");
        sv.menuOp(Menu.OP_CLICK, 9, 0, false); // знову в курсор
        run(() -> menuWhere.test(x -> x.cursor().equals("voxelin:stone|3|0")), 20);
        sv.closeContainer();
        String[][] end = new String[1][];
        run(() -> (end[0] = sv.takeMenuEnd()) != null, 20);
        check("voxelin:stone|3|0".equals(end[0][0]) && Arrays.stream(end[0]).filter(Objects::nonNull).count() == 1, "закрив: курсор - в інвентар гостя: " + Arrays.toString(end[0]));
        // піч: горить у хоста, гість бачить прогрес
        String ore = host.itemTypes.all().stream().map(t -> t.name).filter(nm -> host.recipes.smeltFor(nm) != null && host.recipes.fuelTicks(nm) == 0).findFirst().orElseThrow();
        String fuel = host.itemTypes.all().stream().map(t -> t.name).filter(nm -> host.recipes.fuelTicks(nm) > 0 && host.recipes.smeltFor(nm) == null).findFirst().orElseThrow();
        long fx = cx, fz = cz - 2;
        host.setBlock(Sources.player("Хост"), fx, cy, fz, host.blocks.byName("voxelin:furnace"));
        Inventory fi = new Inventory(host.itemTypes);
        fi.add(ore, 2); fi.add(fuel, 4);
        tickAll(3);
        ms[0] = null;
        sv.openContainer(fx, cy, fz, fi);
        run(() -> menuWhere.test(x -> x.kind().equals("furnace")), 60);
        sv.menuOp(Menu.OP_CLICK, 0, 0, true); sv.menuOp(Menu.OP_CLICK, 1, 0, true);
        run(() -> menuWhere.test(x -> x.slots()[0] == null && x.slots()[1] == null), 40); // руда й паливо пішли кожне у свій слот
        FurnaceEntity hf = host.furnaceAt(fx, cy, fz);
        check(hf.input != null && hf.fuel != null || hf.lit(), "руда й паливо - у печі хоста (" + ore + ", " + fuel + ")");
        for (int i = 0; i < 40; i++) { host.tick(); tickAll(1); }
        run(() -> menuWhere.test(x -> { FurnaceEntity fe = new FurnaceEntity(0, 0, 0); fe.decode(x.data()); return fe.progress > 0 && x.cookTotal() > 0; }), 40);
        check(true, "гість бачить, як піч хоста плавить");
        // хост зламав піч - вікно гостя закривається, інвентар повертається
        host.setBlock(Sources.player("Хост"), fx, cy, fz, host.blocks.air());
        run(() -> (end[0] = sv.takeMenuEnd()) != null, 20);
        check(end[0][0] == null, "піч зникла - вікно гостя закрито хостом");
        // далеко - не відкривається
        sv.openContainer(cx + 40, cy, cz, gi);
        run(() -> (end[0] = sv.takeMenuEnd()) != null, 20);
        check("voxelin:dirt|7|0".equals(end[0][0]), "скриня поза рукою - відмова, речі як були");
        // гість зламав скриню (виживання): вміст - йому, не на землю хоста
        host.chestAt(cx, cy, cz).set(2, new Inventory.Stack("voxelin:cobblestone", 9));
        gifts.clear(); sv.takeGifts();
        sv.breakBlock(cx, cy, cz, "voxelin:wooden_axe");
        run(() -> host.blockId(cx, cy, cz) == 0, 40);
        run(() -> { gifts.addAll(sv.takeGifts()); return gifts.stream().anyMatch(o -> o[0].equals("voxelin:cobblestone")); }, 40);
        check(gifts.stream().anyMatch(o -> o[0].equals("voxelin:dirt") && (int) o[1] == 7) && gifts.stream().anyMatch(o -> o[0].equals("voxelin:chest")), "скриню зламав гість: вміст і сама скриня - йому");
        sv.sendInv(gi); tickAll(3); // останній інвентар перед виходом: хост запише його навіть попри «не частіше 3 с»
        sv.close(); srv.close();
        tickAll(2);
        check(host.targets().isEmpty(), "після виходу гостя - не ціль");
        srv = new NetServer(host, 0, "Хост", "", hostChat::add, true);
        NetClient back2 = join("Шахтар", srv.code);
        back2.attach(m2);
        List<String> inv2 = new ArrayList<>();
        run(() -> { back2.poll(); String[] l = back2.takeSavedInv(); if (l != null) inv2.addAll(Arrays.asList(l)); return !inv2.isEmpty(); }, 60);
        check(inv2.size() == Inventory.SIZE + 5 && inv2.get(0).startsWith("voxelin:dirt|7"), "повернувся - речі знову з ним: " + (inv2.isEmpty() ? "-" : inv2.get(0)));
        back2.close(); srv.close();
        File m2d = m2.dir; m2.close(); WorldArchive.deleteTree(m2d);
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
