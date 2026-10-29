import voxelin.client.audio.*;
import voxelin.core.*;
import voxelin.core.entity.Mob;
import voxelin.loader.Game;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Звук без звукової карти: синтез, мікшер (панорама, відстань, категорії, ліміт голосів, петлі), .wav-перекриття, події світу й запасні звуки. */
public class VoxelinAudioTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    static float[] mix(AudioEngine e, int blocks) { float[] buf = new float[AudioEngine.BLOCK_FRAMES * 2], acc = new float[2]; for (int b = 0; b < blocks; b++) { e.mix(buf, AudioEngine.BLOCK_FRAMES); for (int i = 0; i < buf.length; i++) acc[i & 1] += Math.abs(buf[i]); } return acc; }

    public static void main(String[] a) throws Exception {
        long t0 = System.nanoTime();
        SoundBank bank = SoundGen.build();
        double ms = (System.nanoTime() - t0) / 1e6;
        check(ms < 3000, "синтез усіх звуків швидкий: " + (int) ms + " мс");
        for (String id : bank.ids()) for (Sound s : bank.variants(id)) {
            float peak = 0; boolean bad = false;
            for (int i = 0; i < s.length(); i++) { float v = s.sample(i); if (Float.isNaN(v) || Math.abs(v) > 1) bad = true; peak = Math.max(peak, Math.abs(v)); }
            check(!bad && peak > 0.05f && s.seconds() < 16, "звук " + id + " чесний (пік " + peak + ", " + s.seconds() + " с)");
        }
        for (String m : SoundGen.MATERIALS) for (String k : new String[]{"break", "place", "hit"}) check(bank.has("voxelin:block." + m + "." + k), "є block." + m + "." + k);
        for (String m : SoundGen.MATERIALS) check(bank.has("voxelin:step." + m), "є step." + m);
        SoundBank again = SoundGen.build();
        check(Arrays.equals(toArr(again.variants("voxelin:block.stone.break").get(0)), toArr(bank.variants("voxelin:block.stone.break").get(0))), "синтез детермінований");

        // мікшер: позиція й панорама (дивимось на -Z, праве вухо - +X)
        AudioEngine e = new AudioEngine(bank); e.enableForTest();
        e.listener(new Pos(0, 0, 8, 64, 8), 0f);
        e.play("voxelin:block.stone.break", new Pos(0, 0, 14, 64, 8), 1f, 1f, AudioEngine.Cat.BLOCKS);
        float[] lr = mix(e, 20);
        check(lr[1] > lr[0] * 2, "звук праворуч - у правому каналі: " + Arrays.toString(lr));
        e.play("voxelin:block.stone.break", new Pos(0, 0, 2, 64, 8), 1f, 1f, AudioEngine.Cat.BLOCKS);
        lr = mix(e, 20);
        check(lr[0] > lr[1] * 2, "ліворуч - у лівому: " + Arrays.toString(lr));
        e.listener(new Pos(0, 0, 8, 64, 8), 180f);
        e.play("voxelin:block.stone.break", new Pos(0, 0, 14, 64, 8), 1f, 1f, AudioEngine.Cat.BLOCKS);
        lr = mix(e, 20);
        check(lr[0] > lr[1] * 2, "розвернувся - канали міняються: " + Arrays.toString(lr));
        check(e.voiceCount() == 0, "короткі звуки закінчуються й прибираються");
        e.listener(new Pos(0, 0, 8, 64, 8), 0f);
        e.play("voxelin:block.stone.break", new Pos(0, 0, 8, 64, 8 + 10), 1f, 1f, AudioEngine.Cat.BLOCKS);
        float near = sum(mix(e, 20));
        e.play("voxelin:block.stone.break", new Pos(0, 0, 8, 64, 8 + 25), 1f, 1f, AudioEngine.Cat.BLOCKS);
        float far = sum(mix(e, 20));
        e.play("voxelin:block.stone.break", Pos.ofBlock(8 + 40, 64, 8), 1f, 1f, AudioEngine.Cat.BLOCKS);
        float gone = sum(mix(e, 20));
        check(near > far * 1.5 && far > 0 && gone == 0, "гучність спадає з відстанню й зникає за " + AudioEngine.HEARING + " блоків: " + near + " " + far + " " + gone);
        e.play("voxelin:block.stone.break", Pos.ofBlock(-16 * 1000000L + 3, 64, 8), 1f, 1f, AudioEngine.Cat.BLOCKS);
        check(sum(mix(e, 20)) == 0, "далекий чанк (мільйон чанків) - тиша, без переповнення");
        e.listener(new Pos(5_000_000L, -3, 8, 64, 8), 0f);
        e.play("voxelin:block.stone.break", new Pos(5_000_000L, -3, 10, 64, 8), 1f, 1f, AudioEngine.Cat.BLOCKS);
        check(sum(mix(e, 20)) > 0, "звук поруч гравця далеко від нуля - чути");

        // категорії й загальна гучність
        e.listener(null, 0);
        e.play("voxelin:mob.cow.idle", null, 1f, 1f, AudioEngine.Cat.MOBS);
        float full = sum(mix(e, 10)); mix(e, 200);
        e.setCategory(AudioEngine.Cat.MOBS, 0.5f);
        e.play("voxelin:mob.cow.idle", null, 1f, 1f, AudioEngine.Cat.MOBS);
        bank.variants("voxelin:mob.cow.idle"); // варіанти випадкові - порівнюємо грубо
        float half = sum(mix(e, 10)); mix(e, 200);
        e.setCategory(AudioEngine.Cat.MOBS, 0f);
        e.play("voxelin:mob.cow.idle", null, 1f, 1f, AudioEngine.Cat.MOBS);
        check(sum(mix(e, 10)) == 0 && half > 0 && half < full * 0.8, "гучність категорії: " + full + " -> " + half + " -> 0");
        e.setCategory(AudioEngine.Cat.MOBS, 1f); e.master = 0f; mix(e, 200);
        e.play("voxelin:ui.click", null, 1f, 1f, AudioEngine.Cat.UI);
        check(sum(mix(e, 5)) == 0, "загальна 0 - тиша");
        e.master = 1f; mix(e, 50);
        e.setCategory(AudioEngine.Cat.UI, Float.NaN);
        check(e.category(AudioEngine.Cat.UI) == 1f, "NaN гучності -> 1");

        // ліміт голосів і межа сигналу
        for (int i = 0; i < 100; i++) e.play("voxelin:block.metal.break", null, 2f, 1f, AudioEngine.Cat.BLOCKS);
        float[] buf = new float[AudioEngine.BLOCK_FRAMES * 2];
        e.mix(buf, AudioEngine.BLOCK_FRAMES);
        float mx = 0; for (float v : buf) mx = Math.max(mx, Math.abs(v));
        check(e.voiceCount() == AudioEngine.MAX_VOICES && mx <= 1f, "100 звуків -> " + e.voiceCount() + " голосів, пік " + mx);
        mix(e, 100);
        e.play("voxelin:nope", null, 1f, 1f, AudioEngine.Cat.UI);
        e.play("voxelin:ui.click", null, Float.NaN, Float.NaN, AudioEngine.Cat.UI);
        mix(e, 2);
        check(e.voiceCount() == 0, "невідомий звук і NaN - ігнор");

        // петлі: наростають, міняються без подвоєння, згасають
        e.loop("amb", "voxelin:ambient.wind", 1f);
        float l1 = sum(mix(e, 5)), l2 = sum(mix(e, 30));
        check(e.voiceCount() == 1 && l2 / 30 > l1 / 5, "петля плавно наростає");
        for (int i = 0; i < 20; i++) e.loop("amb", "voxelin:ambient.wind", 1f);
        mix(e, 2);
        check(e.voiceCount() == 1, "повторний виклик петлі - не дублює");
        e.loop("amb", "voxelin:ambient.cave", 1f);
        mix(e, 2);
        check(e.voiceCount() == 2, "заміна петлі: стара згасає, нова наростає");
        mix(e, 60);
        check(e.voiceCount() == 1, "стара петля зникла після згасання");
        e.loop("amb", "voxelin:ambient.cave", 0f);
        mix(e, 60);
        check(e.voiceCount() == 0, "петля з гучністю 0 зникає");
        AudioEngine off = new AudioEngine(bank);
        off.play("voxelin:ui.click", null, 1f, 1f, AudioEngine.Cat.UI); off.loop("x", "voxelin:ambient.wind", 1);
        mix(off, 2);
        check(off.voiceCount() == 0, "рушій без пристрою мовчить і не копить черги");

        // .wav-перекриття
        File dir = Files.createTempDirectory("vx-snd").toFile();
        AudioEngine rec = new AudioEngine(bank); rec.startVirtual();
        rec.play("voxelin:ui.click", null, 1f, 1f, AudioEngine.Cat.UI);
        Thread.sleep(300);
        File wav = new File(dir, "ui.click.wav");
        rec.saveRecording(wav); rec.close();
        check(wav.length() > 44, "запис мікшера у .wav");
        Files.copy(wav.toPath(), new File(dir, "mob.cow.idle.wav").toPath());
        Files.copy(wav.toPath(), new File(dir, "mob.cow.idle_2.wav").toPath());
        Files.write(new File(dir, "BAD NAME.wav").toPath(), new byte[]{1, 2, 3});
        Files.write(new File(dir, "block.stone.hit.wav").toPath(), "не wav".getBytes());
        SoundBank b2 = SoundGen.build();
        int loaded = b2.loadOverrides(dir);
        check(loaded == 3 && b2.variants("voxelin:mob.cow.idle").size() == 2 && b2.variants("voxelin:ui.click").size() == 1, "перекриття: " + loaded + ", корова " + b2.variants("voxelin:mob.cow.idle").size());
        check(b2.variants("voxelin:block.stone.hit").size() == bank.variants("voxelin:block.stone.hit").size(), "битий файл не ламає згенерований звук");
        check(Math.abs(b2.variants("voxelin:ui.click").get(0).seconds() - 0.3) < 0.15, "довжина прочитаного .wav ~ записаній");

        // події світу -> назви звуків; для кожної істоти й блока є що грати
        File root = Files.createTempDirectory("vx-audio").toFile();
        Game g = new Game(new File(root, "saves"), null);
        g.createWorld("snd", 1, WorldInfo.Type.FLAT);
        World w = g.openWorld("snd");
        w.naturalSpawning = false;
        List<String> heard = new ArrayList<>();
        w.sound = (id, at, vol, pitch) -> heard.add(id);
        AudioEngine ge = new AudioEngine(bank); ge.enableForTest();
        GameSounds gs = new GameSounds(ge, w);
        for (MobType t : w.mobTypes.all()) {
            for (String what : new String[]{"hurt", "death", "idle"}) {
                Mob m = new Mob(t, new Pos(0, 0, 8, 70, 8));
                heard.clear(); w.mobSound(m, what, 1f);
                String id = heard.get(0);
                check(id.equals(t.name.replace(":", ":mob.") + "." + what), "назва звуку істоти: " + id);
                if (!what.equals("idle")) check(gs.resolve(id) != null, "у " + t.name + " є звук " + what + " (" + gs.resolve(id) + ")");
            }
        }
        check(gs.resolve("voxelin:mob.villager_elder.idle").equals("voxelin:mob.villager.idle"), "старійшина говорить як житель");
        check(gs.resolve("voxelin:mob.cow.hurt").equals("voxelin:mob.cow.hurt") && gs.resolve("voxelin:mob.ender_dragon.hurt").equals("voxelin:mob.ender_dragon.hurt"), "власні звуки мають перевагу");
        check(gs.resolve("voxelin:block.nope.break") == null && gs.resolve("voxelin:mob.ghost.flap") == null, "чого нема - мовчання");
        check(GameSounds.category("voxelin:mob.cow.idle") == AudioEngine.Cat.MOBS && GameSounds.category("voxelin:ambient.cave") == AudioEngine.Cat.AMBIENT && GameSounds.category("voxelin:record.unlock") == AudioEngine.Cat.UI && GameSounds.category("voxelin:step.sand") == AudioEngine.Cat.BLOCKS, "категорії за назвою");
        Set<String> mats = new HashSet<>(Arrays.asList(SoundGen.MATERIALS));
        for (BlockType bt : w.blocks.all()) if (bt != null && bt.id > 1) check(mats.contains(bt.sound), "матеріал звуку блока " + bt.name + ": " + bt.sound);
        check(w.blocks.byName("voxelin:stone").sound.equals("stone") && w.blocks.byName("voxelin:glass").sound.equals("glass") && w.blocks.byName("voxelin:oak_log").sound.equals("wood")
                && w.blocks.byName("voxelin:sand").sound.equals("sand") && w.blocks.byName("voxelin:grass").sound.equals("grass") && w.blocks.byName("voxelin:dirt").sound.equals("dirt"), "здогадка матеріалу для основних блоків");
        heard.clear();
        Mob cow = w.spawnMob(w.mobTypes.get("voxelin:cow"), new Pos(0, 0, 8, 70, 8));
        w.hurtMob(cow, 100, 1, 0, new Pos(0, 0, 6, 70, 8));
        check(heard.contains("voxelin:mob.cow.hurt") && heard.contains("voxelin:mob.cow.death"), "удар і смерть корови звучать: " + heard);
        heard.clear();
        w.unlockRecord("voxelin:dragon");
        check(heard.contains("voxelin:record.unlock"), "новий запис журналу дзвенить");
        // кроки: справжня фізика гравця на пласкому світі
        for (int i = 0; i < 1000 && (w.chunk(0, 0) == null || w.chunk(0, -1) == null || w.chunk(0, -2) == null); i++) { w.update(0, -1, 3); Thread.sleep(10); }
        int top = 120; while (top > 0 && !w.isSolid(8, top, 8)) top--;
        voxelin.core.entity.PlayerEntity pl = new voxelin.core.entity.PlayerEntity(w::isSolid, w::isLiquid, Pos.ofBlock(8, top + 1 + voxelin.core.entity.PlayerEntity.EYE + 0.01, 8));
        ge.listener(pl.pos, 0);
        int steps = 0;
        for (int t = 0; t < 100; t++) {
            pl.tick(0.05, t > 10, false, false, false, false, false, false);
            gs.tick(pl, true);
            int before = ge.voiceCount(); ge.mix(new float[4410], 2205);
            if (ge.voiceCount() > before) steps++;
        }
        check(steps >= 6 && steps <= 20, "5 с ходьби - кроки (" + steps + "), пройдено " + (8.5 - pl.pos.relZ(0)) + " y=" + pl.pos.y + " g=" + pl.onGround());
        steps = 0;
        for (int t = 0; t < 60; t++) { pl.tick(0.05, false, false, false, false, false, false, false); gs.tick(pl, true); int before = ge.voiceCount(); ge.mix(new float[4410], 2205); if (ge.voiceCount() > before) steps++; }
        check(steps <= 1, "стоїть - кроків нема (" + steps + ")"); // окрім петлі оточення, що могла з'явитись
        w.close();
        System.out.println("VoxelinAudioTest: " + n + " перевірок OK");
    }

    static float sum(float[] lr) { return lr[0] + lr[1]; }
    static float[] toArr(Sound s) { float[] a = new float[s.length()]; for (int i = 0; i < a.length; i++) a[i] = s.sample(i); return a; }
}
