package hello;

import voxelin.api.*;
import voxelin.api.event.ChunkGeneratedEvent;
import voxelin.api.event.MobDeathEvent;

import java.util.Random;

/**
 * Приклад мода: новий блок «hello:ruby» (світиться), руда рубіна в новозгенерованих чанках під землею, команда /hello,
 * налаштування в config/hello.properties (ModConfig) і лічильник переможених ворогів (MobDeathEvent).
 * Збірка: bash build.sh -> hello-mod.jar. Поклади jar у теку mods/ гри.
 */
public final class HelloMod implements VoxelinMod {
    @Override public void init(ModContext ctx) {
        BlockRef ruby = ctx.registries().block("ruby", new BlockSettings().color(0xD01040).light(8).hardness(3f));
        BlockRef stone = ctx.registries().findBlock("voxelin:stone");

        // налаштування мода - файл config/hello.properties (з'являється з типовими значеннями, гравець може правити)
        ModConfig cfg = ctx.api(ModConfig.class);
        int veins = cfg.getInt("ruby.veins", 12);
        cfg.save();

        // руда: у кожному НОВОМУ чанку до veins жил по 1-4 блоки між y=4 і y=48, лише замість каменю
        ctx.events().on(ChunkGeneratedEvent.class, e -> {
            Random r = new Random(e.seed ^ (e.chunk.chunkX() * 341873128712L) ^ (e.chunk.chunkZ() * 132897987541L));
            for (int vein = 0; vein < veins; vein++) {
                int x = r.nextInt(16), y = 4 + r.nextInt(44), z = r.nextInt(16);
                for (int i = 0, n = 1 + r.nextInt(4); i < n; i++) {
                    int px = x + r.nextInt(3) - 1, py = y + r.nextInt(3) - 1, pz = z + r.nextInt(3) - 1;
                    if (px >= 0 && px < 16 && pz >= 0 && pz < 16 && e.chunk.get(px, py, pz) == stone) e.chunk.set(px, py, pz, ruby);
                }
            }
        });

        // нова можливість API: перевіряємо ctx.has - на грі без неї мод просто не рахує (і не падає)
        int[] kills = {0};
        if (ctx.has("events.mob_death")) ctx.events().on(MobDeathEvent.class, e -> { if (!e.mob.startsWith("voxelin:villager")) kills[0]++; });

        ctx.commands().register("hello", "", 0, k -> k.reply("Привіт від hello-mod, " + k.source().name() + "! Істот переможено: " + kills[0] + ". Спробуй /setblock ~ ~2 ~ hello:ruby"));
        ctx.log("готово: блок " + ruby.name());
    }
}
