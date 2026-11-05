import voxelin.api.*;
import voxelin.core.*;
import voxelin.loader.Game;
import voxelin.loader.ModLoader;
import voxelin.net.*;
import voxelin.server.DedicatedServer;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

/**
 * Моди на сервері як «плагіни» (протокол 5): сервер надсилає гостю вміст своїх модів даними - гість без модів бачить блоки,
 * предмети, текстури й істоти мода, крафтить за його рецептами; гість протоколу 4 грає як раніше; гість 1.3 до хоста 1.2 - протоколом 4.
 */
public class VoxelinContentTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    /** Мод лише на сервері: завантажується з jar-а, у якого в маніфесті цей клас (сам клас - на шляху тестів). */
    public static class RubyMod implements VoxelinMod {
        public void init(ModContext ctx) {
            Registries r = ctx.registries();
            int[] px = new int[256];
            for (int i = 0; i < 256; i++) px[i] = 0xFF000000 | (i * 0x010203);
            r.texture("ruby_block", px);
            r.block("ruby_block", new BlockSettings().texture("ruby_block").color(0xC0182A).hardness(3f).light(7).tool(ToolKind.PICKAXE).minTier(2).title("Рубіновий блок").drops("ruby", 2, 4, 0.5f).sound("glass"));
            r.block("ruby_glass", new BlockSettings().color(0xFF5566).translucent(true).noDrops());
            r.item("ruby", new ItemSettings().color(0xE0115F).title("Рубін"));
            r.item("ruby_pickaxe", new ItemSettings().tool(ToolKind.PICKAXE, 4, 9f).durability(900).attack(5f).title("Рубінова кирка"));
            r.item("ruby_helmet", new ItemSettings().armor(ArmorSlot.HELMET, 3, 4).fireproof());
            r.recipe("ruby_block", RecipeSettings.shaped("RRR", "RRR", "RRR").key('R', "ruby").result("ruby_block", 1));
            r.recipe("ruby_from_block", RecipeSettings.shapeless("ruby_block").result("ruby", 9));
            r.recipe("ruby_smelt", RecipeSettings.smelting("voxelin:cobblestone", 4f).result("ruby", 1));
            r.fuel("ruby", 30f);
            r.tag("gems", "ruby");
            r.mob("ruby_golem", new MobSettings().health(40).size(1.2f, 2.4f).behavior(MobSettings.Behavior.GUARD).title("Рубіновий голем")
                    .part(0, 0.8f, 0, 1f, 1f, 0.6f, 0xC0182A).pattern("stone")
                    .head(0, 1.8f, 0, 0.6f, 0.6f, 0.6f, 0xE0115F).face(0x00FFFF)
                    .part(0, 2.4f, 0, 0.2f, 0.3f, 0.2f, 0xFFD700).onHead()
                    .legPart(-0.25f, 0, 0, 0.3f, 0.8f, 0.3f, 0x801020, false).legPart(0.25f, 0, 0, 0.3f, 0.8f, 0.3f, 0x801020, true)
                    .armPart(-0.7f, 1f, 0, 0.3f, 0.8f, 0.3f, 0x801020, true));
            ctx.commands().register("ruby", "/ruby", 0, c -> c.reply("рубін!"));
            // «плагін»: команда для всіх, що діє саме на того, хто її написав
            Players players = ctx.api(Players.class);
            ctx.commands().register("gift", "", 0, c -> {
                String who = c.source().name();
                long[] p = players.position(who);
                if (p == null) throw new Commands.CommandException("тебе нема в грі");
                players.message(who, "тримай рубін, " + who);
                players.give(who, "ruby:ruby", 3);
                players.teleport(who, p[0] + 5, (int) p[1] + 3, p[2]);
                c.reply("онлайн: " + players.online());
            });
            ctx.commands().register("nuke", "", 3, c -> c.reply("бум"));
        }
    }

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("vx-content").toFile();
        File mods = new File(root, "mods");
        mods.mkdirs();
        jar(new File(mods, "ruby.jar"), "ruby", RubyMod.class.getName());

        // 1) запис того, що зареєстрував мод (вбудований вміст - ні)
        Game host = new Game(new File(root, "saves"), mods);
        check(host.mods.mods().stream().anyMatch(m -> m.id.equals("ruby") && m.status == ModLoader.Status.OK), "мод завантажився: " + host.mods.mods());
        List<ModLoader.ContentOp> ops = host.mods.content();
        check(ops.size() == 12 && ops.stream().allMatch(o -> o.mod().equals("ruby")), "записано 12 реєстрацій мода й нічого вбудованого: " + ops.size());

        // 2) кадри -> назад -> гра гостя без модів
        List<ModLoader.ContentOp> back = new ArrayList<>();
        for (byte[] f : ContentCodec.frames(ops)) { check(f[0] == Protocol.S_CONTENT, "кадр S_CONTENT"); back.addAll(ContentCodec.read(f)); }
        check(back.size() == ops.size(), "усі записи пройшли через кадри");
        Game guest = Game.forHost(new File(root, "guest-saves"), back);
        compare(host, guest);
        check(guest.mods.mods().stream().anyMatch(m -> m.id.equals("ruby") && "(з хоста)".equals(m.file)), "вміст хоста позначено «(з хоста)»");
        check(guest.commands.all().stream().noneMatch(e -> e.name.equals("ruby")), "команди мода в гостя не з'являються - вони живуть на сервері");

        // 3) великий вміст ділиться на кілька кадрів, кожен у межах MAX_FRAME
        List<ModLoader.ContentOp> many = new ArrayList<>();
        for (int i = 0; i < 1500; i++) many.add(new ModLoader.ContentOp("big", "texture", "big:t" + i, new int[256]));
        List<byte[]> fr = ContentCodec.frames(many);
        check(fr.size() > 1 && fr.stream().allMatch(f -> f.length <= Protocol.MAX_FRAME), "1500 текстур - кілька кадрів: " + fr.size());

        // 4) злий хост: погані дані - зрозуміла помилка, а не падіння чи дивні числа
        check(badFrame(enc("ruby", "block", "ruby:x", o -> { o.writeInt(1); })), "обірваний запис блока");
        check(badFrame(enc("voxelin", "texture", "voxelin:stone", o -> { for (int i = 0; i < 256; i++) o.writeInt(0); })), "хост не може підмінити вбудований вміст");
        check(badFrame(enc("ruby", "magic", "ruby:x", o -> { })), "невідомий вид вмісту");
        check(badFrame(enc("ruby", "fuel", "ruby:x", o -> o.writeFloat(Float.NaN))), "паливо NaN");
        check(badFrame(enc("ruby", "mob", "ruby:x", o -> { o.writeFloat(10); o.writeFloat(1); o.writeFloat(1); o.writeUTF("PASSIVE"); o.writeBoolean(false); o.writeBoolean(false); o.writeBoolean(false); o.writeBoolean(false); o.writeInt(-1); o.writeShort(1); o.writeFloat(1e9f); })), "частина істоти за межами");
        ModLoader.ContentOp hack = ContentCodec.read(frameOf(enc("ruby", "item", "ruby:x", o -> { o.writeByte(64); o.writeInt(0); o.writeUTF("NONE"); o.writeByte(0); o.writeFloat(Float.POSITIVE_INFINITY); o.writeByte(127); o.writeFloat(Float.NaN); o.writeBoolean(false); o.writeByte(0); o.writeByte(0); o.writeBoolean(false); o.writeInt(0); o.writeBoolean(false); o.writeBoolean(false); o.writeBoolean(false); }))).get(0);
        ItemSettings hs = (ItemSettings) hack.data();
        check(hs.foodValue() == 20 && hs.speedValue() == 1f && hs.attackValue() == 1f, "дивні числа обрізано будівниками (їжа 127 -> 20, NaN/нескінченність - типові)");
        Game evil = Game.forHost(new File(root, "evil"), List.of(new ModLoader.ContentOp("ruby", "block", "other:steal", new BlockSettings()), new ModLoader.ContentOp("ruby", "block", "ruby:ok", new BlockSettings())));
        check(evil.blocks.byName("other:steal") == null && evil.blocks.byName("ruby:ok") != null, "чужий простір імен відкинуто, решта вмісту працює");

        // 5) по мережі: сервер з модом, гість без модів
        File conf = new File(root, "server.properties");
        DedicatedServer s = new DedicatedServer(DedicatedServer.Config.parse(new String[]{"--config", conf.getPath(), "--saves", new File(root, "srv-saves").getPath(), "--mods", mods.getPath(), "--port", "0", "--flat", "--world", "Рубіни"}));
        s.out = l -> { };
        Thread loop = new Thread(s::run, "voxelin-world");
        loop.start();
        check(s.net.contentCount() == 12, "сервер роздає вміст модів");
        NetClient cli = join(s, "Гість", s.net.code, Protocol.VERSION);
        check(cli.protocol == 5 && cli.content.size() == 12, "гість отримав вміст при вході: " + cli.content.size());
        Game g2 = Game.forHost(new File(root, "guest2"), cli.content);
        compare(host, g2);
        World mirror = g2.openMirror(cli.welcome.worldName(), cli.welcome.seed(), cli.welcome.type(), cli.welcome.palette(), false);
        cli.attach(mirror);
        Pos at = cli.welcome.spawn();
        long bx = at.blockX() + 2, bz = at.blockZ();
        int by;
        s.world.lock.lock();
        try {
            int y = 1; while (s.world.blockId(bx, y, bz) != 0) y++;
            by = y;
            s.world.setBlock(Sources.console(), bx, by, bz, s.world.blocks.byName("ruby:ruby_block"));
        } finally { s.world.lock.unlock(); }
        wait(() -> { cli.poll(); mirror.update(at.cx, at.cz, 2); BlockRef t = mirror.chunk(bx >> 4, bz >> 4) == null ? null : mirror.getBlock(bx, by, bz); return t != null && t.name().equals("ruby:ruby_block"); }, "гість бачить блок мода як блок мода, а не «невідомий»");
        // команди мода для гостя: виконуються на сервері від його імені, Players діє саме на нього
        long[] before = s.net.players.position("Гість");
        cli.chat("/gift");
        List<String> got = new ArrayList<>();
        Pos[] tp = new Pos[1];
        List<Object[]> gifts = new ArrayList<>();
        wait(() -> { cli.poll(); got.addAll(cli.takeChat()); Pos t = cli.takeTeleport(); if (t != null) tp[0] = t; gifts.addAll(cli.takeGifts()); return tp[0] != null && !gifts.isEmpty() && got.stream().anyMatch(l -> l.contains("онлайн")); }, "/gift: телепорт, подарунок і відповідь дійшли");
        check(got.contains("тримай рубін, Гість") && got.stream().anyMatch(l -> l.equals("онлайн: [Гість]")), "повідомлення мода саме цьому гостю й список гравців: " + got);
        check(gifts.size() == 1 && gifts.get(0)[0].equals("ruby:ruby") && (int) gifts.get(0)[1] == 3, "подарунок мода");
        check(tp[0].blockX() == before[0] + 5 && tp[0].blockZ() == before[2] && Math.floor(tp[0].y - voxelin.core.entity.PlayerEntity.EYE + 0.05) == before[1] + 3, "телепорт на 5 вбік і 3 вгору");
        long[] after = s.net.players.position("Гість");
        check(after[0] == before[0] + 5 && after[1] == before[1] + 3, "сервер теж знає нову позицію гостя");
        got.clear();
        cli.chat("/nuke");
        wait(() -> { cli.poll(); got.addAll(cli.takeChat()); return !got.isEmpty(); }, "відповідь на чужу команду");
        check(got.stream().anyMatch(l -> l.contains("/nuke") && (l.contains("Чити") || l.contains("прав"))) && got.stream().noneMatch(l -> l.contains("бум")), "команда з правами гостю не виконується: " + got);
        got.clear();
        cli.chat("/time set 0");
        wait(() -> { cli.poll(); got.addAll(cli.takeChat()); return !got.isEmpty(); }, "відповідь на команду гри");
        check(got.stream().noneMatch(l -> l.startsWith("Час")), "команди гри (читів) гостю недоступні: " + got);
        cli.close();
        mirror.close();

        // 6) гість протоколу 4 (1.2.x): заходить, вмісту не отримує
        NetClient old = join(s, "Старий", s.net.code, 4);
        check(old.protocol == 4 && old.content.isEmpty(), "гість протоколу 4 грає без вмісту модів");
        old.close();
        s.enqueue("stop");
        loop.join(30_000);

        // 7) гість 1.3 до хоста 1.2: хост відмовляє через протокол - гість сам пробує протоколом 4
        try (ServerSocket fake = new ServerSocket(0)) {
            List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
            Thread t = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try (Socket c = fake.accept()) {
                        DataInputStream in = new DataInputStream(c.getInputStream());
                        DataOutputStream out = new DataOutputStream(c.getOutputStream());
                        byte[] f = Protocol.read(in);
                        int ver = Protocol.body(f).readInt();
                        seen.add(ver);
                        Protocol.write(out, Protocol.frame(Protocol.S_REJECT, o -> o.writeUTF(ver == 4 ? "неправильний код запрошення" : "інша версія гри (протокол " + ver + ", у хоста 4)")));
                        out.flush();
                    } catch (IOException e) { return; }
                }
            });
            t.start();
            String why = "";
            try { NetClient.connect("127.0.0.1", fake.getLocalPort(), "Гість", "AAAAAA", ""); } catch (IOException e) { why = e.getMessage(); }
            t.join(10_000);
            check(seen.equals(List.of(5, 4)) && why.contains("неправильний код"), "до старого хоста - друга спроба протоколом 4: " + seen + " / " + why);
        }
        System.out.println("VoxelinContentTest: " + n + " перевірок OK");
        System.exit(0);
    }

    /** Гість бачить те саме, що й хост. */
    static void compare(Game h, Game g) {
        BlockType hb = h.blocks.byName("ruby:ruby_block"), gb = g.blocks.byName("ruby:ruby_block");
        check(gb != null && gb.color == hb.color && gb.hardness == hb.hardness && gb.light == 7 && gb.tool == ToolKind.PICKAXE && gb.minTier == 2 && gb.sound.equals("glass")
                && "ruby:ruby_block".equals(gb.texSide) && gb.drops.size() == 1 && gb.drops.get(0).item.equals("ruby:ruby") && gb.drops.get(0).max == 4 && gb.drops.get(0).chance == 0.5f, "блок мода в гостя такий самий: " + gb);
        BlockType glass = g.blocks.byName("ruby:ruby_glass");
        check(glass != null && glass.translucent && !glass.opaque && glass.drops.isEmpty(), "напівпрозорий блок без дропу");
        check(g.items.get("ruby:ruby_block") != null, "предмет-блок створився сам");
        ItemType pick = g.items.get("ruby:ruby_pickaxe"), helm = g.items.get("ruby:ruby_helmet");
        check(pick != null && pick.tool == ToolKind.PICKAXE && pick.tier == 4 && pick.speed == 9f && pick.durability == 900 && pick.maxStack == 1 && pick.attack == 5f && "Рубінова кирка".equals(pick.title), "інструмент мода");
        check(helm != null && helm.armorSlot == ArmorSlot.HELMET && helm.armorPoints == 3 && helm.armorTier == 4 && helm.fireproof, "броня мода");
        check(g.recipes.all().stream().filter(r -> r.id.startsWith("ruby:")).count() == 2 && g.recipes.smelts().stream().anyMatch(m -> m.id.equals("ruby:ruby_smelt") && m.input.equals("voxelin:cobblestone") && m.result.equals("ruby:ruby") && m.ticks == 80), "рецепти й плавка мода");
        check(g.recipes.fuelTicks("ruby:ruby") == h.recipes.fuelTicks("ruby:ruby") && g.recipes.fuelTicks("ruby:ruby") > 0, "паливо");
        check(g.items.inTag("gems", "ruby:ruby"), "мітка");
        check(g.textures.containsKey("ruby:ruby_block") && Arrays.equals(g.textures.get("ruby:ruby_block")[0], h.textures.get("ruby:ruby_block")[0]), "текстура піксель у піксель");
        MobType hm = h.mobTypes.get("ruby:ruby_golem"), gm = g.mobTypes.get("ruby:ruby_golem");
        check(gm != null && gm.width == hm.width && gm.height == hm.height && gm.parts.size() == hm.parts.size() && gm.eyes == hm.eyes && "Рубіновий голем".equals(gm.title), "вигляд істоти");
        for (int i = 0; i < hm.parts.size(); i++) {
            MobSettings.Part p = hm.parts.get(i), q = gm.parts.get(i);
            check(p.cx == q.cx && p.y0 == q.y0 && p.w == q.w && p.rgb == q.rgb && p.swing == q.swing && p.role == q.role && Objects.equals(p.pattern, q.pattern) && p.face == q.face, "частина істоти " + i);
        }
    }

    interface W { void w(DataOutputStream o) throws IOException; }

    static byte[] enc(String mod, String kind, String name, W body) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeUTF(mod); o.writeUTF(kind); o.writeUTF(name);
        body.w(o);
        return b.toByteArray();
    }

    static byte[] frameOf(byte[] rec) { return Protocol.frame(Protocol.S_CONTENT, o -> { o.writeInt(1); o.writeInt(rec.length); o.write(rec); }); }

    static boolean badFrame(byte[] rec) { try { ContentCodec.read(frameOf(rec)); return false; } catch (IOException e) { return true; } }

    static NetClient join(DedicatedServer s, String name, String code, int ver) throws Exception {
        FutureTask<NetClient> f = new FutureTask<>(() -> NetClient.connect("127.0.0.1", s.net.port, name, code, "", ver));
        new Thread(f).start();
        return f.get(15, TimeUnit.SECONDS);
    }

    static void wait(java.util.function.BooleanSupplier ok, String what) throws InterruptedException {
        for (int i = 0; i < 500; i++) { if (ok.getAsBoolean()) { check(true, what); return; } Thread.sleep(10); }
        check(ok.getAsBoolean(), "не дочекались: " + what);
    }

    static void jar(File f, String id, String cls) throws IOException {
        Manifest mf = new Manifest();
        Attributes at = mf.getMainAttributes();
        at.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        at.putValue("Voxelin-Mod", cls);
        at.putValue("Voxelin-Mod-Id", id);
        at.putValue("Voxelin-Api", String.valueOf(VoxelinApi.VERSION));
        try (JarOutputStream j = new JarOutputStream(new FileOutputStream(f), mf)) { }
    }
}
