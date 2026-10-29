package voxelin.client.audio;

import voxelin.core.*;
import voxelin.core.entity.PlayerEntity;

import java.util.Random;

/**
 * Звуки одного відкритого світу: приймає події ядра (SoundSink), рахує кроки, вмикає петлі оточення за місцем і часом доби.
 * Ядро називає подію ("voxelin:mob.zombie.hurt"); тут вирішується категорія гучності й запасний звук, якщо власного нема.
 */
public final class GameSounds implements SoundSink {
    private final AudioEngine audio;
    private final World world;
    private final Random rng = new Random();
    private double stepDist;
    private int ticks;
    private String ambientKey;

    public GameSounds(AudioEngine audio, World world) { this.audio = audio; this.world = world; }

    @Override public void play(String id, Pos at, float volume, float pitch) {
        String use = resolve(id);
        if (use != null) audio.play(use, at, volume, pitch, category(use));
    }

    /** Ця назва або запасна (загальний «біль»/«смерть» для істот без власних; villager_elder -> villager). null - мовчати. */
    public String resolve(String id) {
        SoundBank b = audio.bank();
        if (b.has(id)) return id;
        int m = id.indexOf(":mob.");
        if (m < 0) return null;
        String rest = id.substring(m + 5), what = rest.substring(rest.lastIndexOf('.') + 1), mob = rest.substring(0, rest.lastIndexOf('.'));
        int u = mob.indexOf('_');
        if (u > 0) { String base = id.substring(0, m) + ":mob." + mob.substring(0, u) + "." + what; if (b.has(base)) return base; }
        String generic = id.substring(0, m) + ":mob." + what;
        return b.has(generic) ? generic : null;
    }

    public static AudioEngine.Cat category(String id) {
        String n = id.substring(id.indexOf(':') + 1);
        if (n.startsWith("mob.")) return AudioEngine.Cat.MOBS;
        if (n.startsWith("ambient.")) return AudioEngine.Cat.AMBIENT;
        if (n.startsWith("ui.") || n.startsWith("record.")) return AudioEngine.Cat.UI;
        return AudioEngine.Cat.BLOCKS;
    }

    /** Звук матеріалу блока: kind = break | place | hit. */
    public void block(BlockType bt, long x, int y, long z, String kind) {
        if (bt == null || bt.id <= 0) return;
        float vol = kind.equals("hit") ? 0.6f : 1f;
        audio.play("voxelin:block." + bt.sound + "." + kind, Pos.ofBlock(x, y + 0.5, z), vol, 0.85f + rng.nextFloat() * 0.25f, AudioEngine.Cat.BLOCKS);
    }

    public void ui(String id) { audio.play(id, null, 1f, 1f, AudioEngine.Cat.UI); }

    /** Звук біля гравця (без панорами): біль, їжа, удар. */
    public void self(String id, float volume) { audio.play(id, null, volume, 0.9f + rng.nextFloat() * 0.2f, category(id)); }

    /** Щотіку з PlayScreen: кроки, приземлення, петлі оточення й польоту, пульс Ядра Шву. */
    public void tick(PlayerEntity p, boolean alive) {
        ticks++;
        double hx = p.pos.relX(p.prev.cx) - p.prev.x, hz = p.pos.relZ(p.prev.cz) - p.prev.z;
        long fx = p.pos.blockX(), fz = p.pos.blockZ();
        int feet = (int) Math.floor(p.pos.y - PlayerEntity.EYE - 0.2);
        if (alive && p.onGround() && !p.inWater) {
            stepDist += Math.hypot(hx, hz);
            if (stepDist >= 1.7) { stepDist = 0; step(fx, feet, fz, 1f); }
        } else if (!p.onGround()) stepDist = 1.2; // перший крок після стрибка - швидко
        if (!p.gliding) audio.loop("glide", "voxelin:ambient.glide", 0);
        else audio.loop("glide", "voxelin:ambient.glide", (float) Math.min(1.2, p.speed() / 30));
        if (ticks % 20 == 0) ambient(p);
        WorldInfo i = world.info;
        if (i.infState == WorldInfo.InfState.ACTIVE && i.type != WorldInfo.Type.END && i.type != WorldInfo.Type.BETWEEN && ticks % 80 == 0) {
            Pos core = Pos.ofBlock(i.infX, i.infY + 0.5, i.infZ);
            double dx = core.relX(p.pos.cx) - p.pos.x, dz = core.relZ(p.pos.cz) - p.pos.z;
            if (dx * dx + dz * dz < 40 * 40) audio.play("voxelin:seam.pulse", core, 1.4f, 1f, AudioEngine.Cat.AMBIENT);
        }
    }

    /** Приземлення: крок, гучніший за висоту падіння. */
    public void land(PlayerEntity p, double fall) {
        if (fall < 1.2) return;
        step(p.pos.blockX(), (int) Math.floor(p.pos.y - PlayerEntity.EYE - 0.2), p.pos.blockZ(), (float) Math.min(2, 0.8 + fall * 0.15));
    }

    private void step(long x, int y, long z, float vol) {
        int id = world.blockId(x, y, z);
        if (id <= 0) return;
        BlockType bt = world.blocks.get(id);
        if (!bt.solid) return;
        audio.play("voxelin:step." + bt.sound, null, vol, 0.9f + rng.nextFloat() * 0.2f, AudioEngine.Cat.BLOCKS);
    }

    /** Яка петля оточення зараз: Край, міжсвіття (темніше під час зараження), печера, ніч, вітер. */
    private void ambient(PlayerEntity p) {
        WorldInfo i = world.info;
        String key; float vol = 1f;
        if (i.type == WorldInfo.Type.END) key = "voxelin:ambient.end";
        else if (i.type == WorldInfo.Type.BETWEEN) key = i.overInf == WorldInfo.InfState.ACTIVE ? "voxelin:ambient.between_dark" : "voxelin:ambient.between";
        else {
            boolean open = skyOpen(p.pos.blockX(), p.pos.blockY(), p.pos.blockZ());
            boolean night = DayCycle.sky(world.dayPhase()) < 0.45;
            if (!open && p.pos.y < 56) key = "voxelin:ambient.cave";
            else if (!open) { key = "voxelin:ambient.wind"; vol = 0.3f; } // під дахом - вітер ледь чути
            else if (night) key = "voxelin:ambient.night";
            else { key = "voxelin:ambient.wind"; vol = (float) Math.max(0.35, Math.min(1, (p.pos.y - 50) / 50)); } // вище - вітряніше
            if (i.infState == WorldInfo.InfState.ACTIVE && open) vol *= (float) Math.max(0.1, 0.7 - world.infection.gloom() * 1.2); // зараження: звуки світу стихають з кожною порою (попередження з DESIGN.md)
        }
        if (ambientKey != null && !ambientKey.equals(key)) audio.loop("ambient", ambientKey, 0);
        ambientKey = key;
        audio.loop("ambient", key, vol);
    }

    /** Чи видно небо з цієї точки (немає суцільних блоків до 32 вгору). */
    private boolean skyOpen(long x, int y, long z) {
        for (int k = 1; k <= 32; k++) { int id = world.blockId(x, y + k, z); if (id > 0 && world.blocks.get(id).opaque) return false; }
        return true;
    }

    /** Світ закрито: петлі згасають. */
    public void close() {
        if (ambientKey != null) audio.loop("ambient", ambientKey, 0);
        audio.loop("glide", "voxelin:ambient.glide", 0);
    }
}
