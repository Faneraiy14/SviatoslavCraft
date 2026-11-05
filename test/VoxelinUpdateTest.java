import voxelin.core.*;
import voxelin.loader.Game;
import voxelin.loader.UpdateCheck;
import voxelin.server.DedicatedServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Оновлення без сюрпризів: «є оновлення» з поясненням сумісності, копія світу перед відкриттям іншою версією, сервер не оновлюється сам. */
public class VoxelinUpdateTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    static String manifest(String latest, int proto, int minProto, int api, int apiMin, String breaking) {
        return "{\"format\":1,\"latest\":{\"release\":\"" + latest + "\",\"server\":\"" + latest + "\"},\"versions\":["
                + "{\"id\":\"" + latest + "\",\"type\":\"release\",\"protocol\":" + proto + ",\"minProtocol\":" + minProto + ",\"api\":" + api + ",\"apiMin\":" + apiMin + ",\"breaking\":[" + breaking + "]},"
                + "{\"id\":\"1.3.5\",\"type\":\"release\",\"protocol\":5,\"breaking\":[\"скрипти: \\\"старий\\\" формат\"]},"
                + "{\"id\":\"1.3.0\",\"type\":\"release\",\"protocol\":5,\"breaking\":[\"те, що вже було\"]}]}";
    }

    public static void main(String[] a) throws Exception {
        check(GameVersion.compare("1.3.0", "1.2.10") > 0 && GameVersion.compare("1.10.0", "1.9.9") > 0 && GameVersion.compare("1.3", "1.3.0") == 0 && GameVersion.compare(null, "0.0.1") < 0, "порівняння версій по числах");

        // сумісне оновлення: гості з новою грою зайдуть, моди ті самі
        UpdateCheck.Update u = UpdateCheck.compare(manifest("1.4.0", 6, 5, 2, 2, ""), "1.3.0", 5, 2, true);
        check(u != null && u.version().equals("1.4.0") && u.lines().get(0).contains("1.4.0") && u.lines().get(0).contains("1.3.0"), "є оновлення: " + u);
        check(u.lines().stream().anyMatch(l -> l.contains("зайдуть і на цей сервер")), "сумісне по мережі");
        check(u.lines().stream().anyMatch(l -> l.contains("«старий» формат") || l.contains("\"старий\" формат")) && u.lines().stream().noneMatch(l -> l.contains("те, що вже було")), "несумісні зміни проміжних версій, без уже встановлених: " + u.lines());
        check(u.lines().stream().anyMatch(l -> l.contains("backups/")) && u.lines().get(u.lines().size() - 1).contains("VERSION=1.4.0"), "про копію світу й як оновити Docker");

        // несумісне: новий протокол без старого, нове обов'язкове API модів
        UpdateCheck.Update hard = UpdateCheck.compare(manifest("2.0.0", 7, 7, 3, 3, "\"формат світу 3\""), "1.3.0", 5, 2, true);
        check(hard.lines().stream().anyMatch(l -> l.contains("НЕ зайдуть")) && hard.lines().stream().anyMatch(l -> l.contains("не завантажаться")) && hard.lines().stream().anyMatch(l -> l.contains("формат світу 3")), "несумісне оновлення пояснено: " + hard.lines());
        check(UpdateCheck.compare(manifest("1.3.0", 5, 4, 2, 2, ""), "1.3.0", 5, 2, true) == null, "та сама версія - нічого");
        check(UpdateCheck.compare("не json", "1.3.0", 5, 2, true) == null && UpdateCheck.compare("{\"latest\":{}}", "1.3.0", 5, 2, true) == null, "поганий список - нічого, без падіння");
        check(UpdateCheck.check("http://127.0.0.1:1/versions.json", "1.3.0", 5, 2, true) == null, "без зв'язку - нічого");

        // світ: версія записується; відкриття іншою версією - спершу копія в backups/
        File root = Files.createTempDirectory("vx-update").toFile();
        File saves = new File(root, "saves");
        Game g = new Game(saves, null);
        g.createWorld("Острів", 3, WorldInfo.Type.FLAT);
        World w = g.openWorld("Острів");
        check(g.lastBackup == null, "щойно створений світ - без копії");
        for (int i = 0; i < 600 && w.chunk(0, 0) == null; i++) { w.update(0, 0, 1); Thread.sleep(5); }
        w.close();
        WorldInfo info = WorldInfo.load(new File(saves, "Острів"));
        check(GameVersion.VERSION.equals(info.gameVersion), "світ пам'ятає версію гри: " + info.gameVersion);
        g.openWorld("Острів").close();
        check(g.lastBackup == null, "та сама версія - без копії");
        File wf = new File(new File(saves, "Острів"), WorldInfo.FILE);
        Files.writeString(wf.toPath(), Files.readString(wf.toPath()).replace("game=" + GameVersion.VERSION, "game=1.2.1"));
        World w2 = g.openWorld("Острів");
        check(g.lastBackup != null && g.lastBackup.isFile() && g.lastBackup.getName().startsWith("Острів-1.2.1-") && g.lastBackup.getParentFile().getName().equals("backups"), "світ версії 1.2.1 - копія перед відкриттям: " + g.lastBackup);
        check(g.lastBackup.length() > 100, "копія не порожня");
        w2.close();
        check(GameVersion.VERSION.equals(WorldInfo.load(new File(saves, "Острів")).gameVersion), "після збереження - нова версія");

        // сервер: «update» з консолі читає список (тут - локальний), сам нічого не оновлює
        com.sun.net.httpserver.HttpServer http = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = manifest("9.0.0", 9, 9, 2, 2, "").getBytes(StandardCharsets.UTF_8);
        http.createContext("/versions.json", ex -> { ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body); ex.close(); });
        http.start();
        File conf = new File(root, "server.properties");
        DedicatedServer.Config cfg = DedicatedServer.Config.parse(new String[]{"--config", conf.getPath(), "--saves", saves.getPath(), "--port", "0", "--world", "Острів", "--no-update-check"});
        check(!cfg.updateCheck && Files.readString(conf.toPath()).contains("update-check=false"), "update-check у server.properties");
        cfg.manifest = "http://127.0.0.1:" + http.getAddress().getPort() + "/versions.json";
        DedicatedServer s = new DedicatedServer(cfg);
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        s.out = log::add;
        Thread loop = new Thread(s::run, "voxelin-world");
        loop.start();
        s.enqueue("update");
        for (int i = 0; i < 300 && log.stream().noneMatch(l -> l.contains("9.0.0")); i++) Thread.sleep(20);
        check(log.stream().anyMatch(l -> l.contains("Є оновлення Voxelin 9.0.0")) && log.stream().anyMatch(l -> l.contains("НЕ зайдуть")), "консоль: є оновлення і що зламається: " + log);
        s.enqueue("stop");
        loop.join(30_000);
        http.stop(0);
        check(GameVersion.VERSION.equals(voxelin.client.Voxelin.VERSION), "сервер лишився на своїй версії");
        System.out.println("VoxelinUpdateTest: " + n + " перевірок OK");
        System.exit(0);
    }
}
