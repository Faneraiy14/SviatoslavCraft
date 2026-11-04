import voxelin.client.code.BlockProgram;
import voxelin.client.code.BlockProgram.Field;
import voxelin.client.code.BlockProgram.Node;

/** Редактор блоків без вікна: перевірка полів, вставка й переміщення, ліміти вкладень, JSON, безпечний код (без ін'єкцій у рядки й вирази). */
public class VoxelinBlocksTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static BlockProgram.Kind k(String id) { return BlockProgram.kind(id); }

    public static void main(String[] a) {
        // поля
        for (String ok : new String[]{"3", "-2", "i", "i*2", "arg + 1", "count-5", "-i+j*3", "100000"}) check(BlockProgram.check(Field.NUM, ok, false) == null, "вираз годиться: " + ok);
        for (String bad : new String[]{"", "1.5", "a)", "2**3", "x;y", "i +", "(1)", "1+2+3+4+5", "\"", "Ж", "1000000"}) check(BlockProgram.check(Field.NUM, bad, false) != null, "вираз відкинуто: " + bad);
        check(BlockProgram.check(Field.BLOCK, "stone", false) == null && BlockProgram.check(Field.BLOCK, "voxelin:torch", false) == null, "блоки");
        check(BlockProgram.check(Field.BLOCK, "*", false) != null && BlockProgram.check(Field.BLOCK, "*", true) == null, "«*» - лише в події");
        check(BlockProgram.check(Field.BLOCK, "a:b:c", false) != null && BlockProgram.check(Field.BLOCK, "Stone", false) != null, "погані назви блоків");
        check(BlockProgram.check(Field.NAME, "my_var2", false) == null && BlockProgram.check(Field.NAME, "2x", false) != null && BlockProgram.check(Field.NAME, "лічильник", false) != null, "назви");
        check(BlockProgram.check(Field.CMD, "/time set 0", false) == null && BlockProgram.check(Field.CMD, "time", false) != null, "команда з /");
        check(BlockProgram.check(Field.PCT, "50", false) == null && BlockProgram.check(Field.PCT, "0", false) != null && BlockProgram.check(Field.PCT, "x", false) != null, "відсоток");
        check(BlockProgram.check(Field.TEXT, "a\nb", false) != null && BlockProgram.check(Field.TEXT, "x".repeat(121), false) != null, "текст в один рядок до 120");
        check(BlockProgram.check(Field.OP, "≥", false) == null && BlockProgram.check(Field.OP, "==", false) != null, "оператори");
        for (BlockProgram.Kind kd : BlockProgram.KINDS) for (int i = 0; i < kd.fields().length; i++) check(BlockProgram.check(kd.fields()[i], kd.defaults()[i], kd.hat()) == null, "типове значення годиться: " + kd.id() + "[" + i + "]");

        // вставка: без шапки - створюється «коли запускається»; під обгортку - у тіло
        BlockProgram p = new BlockProgram();
        Node say = p.insert(k("say"), null);
        check(p.hats.size() == 1 && p.hats.get(0).kind.id().equals("start") && p.hats.get(0).body.get(0) == say, "перший блок - під нову подію «запуск»");
        Node rep = p.insert(k("repeat"), say);
        Node in1 = p.insert(k("setblock"), rep);
        check(p.hats.get(0).body.size() == 2 && rep.body.get(0) == in1, "під обгортку - всередину");
        Node after = p.insert(k("say"), in1);
        check(rep.body.size() == 2 && rep.body.get(1) == after, "під звичайний - наступним");
        // вкладення повторів обмежено
        Node r2 = p.insert(k("repeat"), rep), r3 = p.insert(k("repeat"), r2);
        check(r3 != null && p.loopDepth(r3) == 3, "три рівні повторів");
        check(p.insert(k("repeat"), r3) == null, "четвертий рівень - відмова");
        check(p.insert(k("say"), r3) != null, "у найглибший повтор - звичайні блоки можна");
        // переміщення через обгортки
        BlockProgram m = new BlockProgram();
        Node h = m.insert(k("command"), null);
        Node a1 = m.insert(k("say"), h), w = m.insert(k("ifchance"), a1), a2 = m.insert(k("say"), w);
        // h: [a1, w{a2}]
        check(m.move(a1, 1) && w.body.get(0) == a1 && h.body.size() == 1, "вниз через обгортку - у її тіло");
        check(m.move(a1, -1) && h.body.get(0) == a1 && w.body.get(0) == a2, "вгору з початку тіла - перед обгорткою");
        check(m.move(a2, 1) && h.body.get(2) == a2, "вниз з кінця тіла - після обгортки");
        check(!m.move(a1, -1), "вгору з початку стопки єдиної шапки - нікуди");
        Node h2 = m.insert(k("second"), null);
        check(m.move(a2, 1) && h2.body.get(0) == a2, "з кінця стопки - у наступну шапку");
        check(m.move(h2, -1) && m.hats.get(0) == h2, "шапки міняються місцями");
        // переміщення не дає перевищити вкладення: повтор-у-повторі не заходить у повтор-у-повторі
        BlockProgram d = new BlockProgram();
        Node dh = d.insert(k("command"), null);
        Node outer = d.insert(k("repeat"), dh), outerIn = d.insert(k("repeat"), outer);
        Node lone = d.insert(k("repeat"), null); // у кінець останньої шапки
        check(dh.body.get(1) == lone, "без вибору - у кінець останньої шапки");
        Node loneIn = d.insert(k("repeat"), lone);
        check(loneIn != null && d.move(lone, -1) && outer.body.get(1) == lone, "повтор-у-повторі входить у повтор поруч із вкладеним (3 рівні)");
        check(!d.move(lone, -1) && outer.body.get(1) == lone, "далі в outerIn - ні (було б 4 рівні)");
        check(d.move(lone, 1) && dh.body.get(1) == lone, "вниз з кінця тіла - після обгортки");
        check(d.move(loneIn, -1) && lone.body.isEmpty() && dh.body.get(1) == loneIn, "вгору з тіла - перед обгорткою");
        Node cp = d.duplicate(outer);
        check(cp != null && dh.body.get(1) == cp && cp != outer && cp.body.size() == 1 && cp.body.get(0) != outerIn, "копія - глибока, поруч");
        d.delete(cp);
        check(!dh.body.contains(cp), "видалення");

        // JSON туди-назад, псування
        String json = m.toJson();
        BlockProgram back = BlockProgram.fromJson(json);
        check(back.toJson().equals(json) && back.toCode("t").equals(m.toCode("t")), "JSON туди-назад");
        BlockProgram bad = BlockProgram.fromJson("{\"hats\":[{\"kind\":\"command\",\"values\":[\"Bad Name\"],\"body\":[{\"kind\":\"say\",\"values\":[42]},{\"kind\":\"nope\"},{\"kind\":\"start\"},"
                + "{\"kind\":\"setblock\",\"values\":[\"stone\\\"); run(\\\"/kill\",\"1;x\",\"2\",\"3\"]}]},{\"kind\":\"say\"},\"сміття\"]}");
        check(bad.hats.size() == 1 && bad.hats.get(0).values[0].equals("hello"), "погана назва команди - типова, блок без шапки відкинуто");
        Node b0 = bad.hats.get(0);
        check(b0.body.size() == 2 && b0.body.get(0).values[0].equals("42") && b0.body.get(1).values[0].equals("stone") && b0.body.get(1).values[1].equals("2"), "невідомі й шапки в тілі пропущено, погані поля - типові: " + bad.toJson());
        for (String broken : new String[]{"", "{", "[]", "{\"hats\":5}"}) {
            boolean threw = false;
            try { BlockProgram.fromJson(broken); } catch (IllegalArgumentException e) { threw = true; }
            check(threw, "зламаний JSON: " + broken);
        }
        StringBuilder deep = new StringBuilder("{\"hats\":[{\"kind\":\"start\",\"body\":[");
        for (int i = 0; i < 6; i++) deep.append("{\"kind\":\"repeat\",\"values\":[\"2\"],\"body\":[");
        deep.append("{\"kind\":\"say\"}");
        for (int i = 0; i < 6; i++) deep.append("]}");
        deep.append("]}]}");
        BlockProgram dp = BlockProgram.fromJson(deep.toString());
        check(dp.toCode("x").contains("vx_k") && !dp.toCode("x").contains("vx_l") && dp.count() == 4, "з файла - не глибше 3 повторів: " + dp.count());
        StringBuilder many = new StringBuilder("{\"hats\":[{\"kind\":\"start\",\"body\":[");
        for (int i = 0; i < 2000; i++) many.append(i > 0 ? "," : "").append("{\"kind\":\"say\"}");
        many.append("]}]}");
        check(BlockProgram.fromJson(many.toString()).count() <= BlockProgram.MAX_BLOCKS, "з файла - не більше " + BlockProgram.MAX_BLOCKS + " блоків");

        // код: рядки екрановано, вирази - лише дозволені імена
        BlockProgram c = new BlockProgram();
        Node t = c.insert(k("say"), null);
        t.values[0] = "\"); run(\"/kill\") // \\ {count} {i}";
        Node r = c.insert(k("run"), t);
        r.values[0] = "/give @p {arg} \"x\"";
        Node v = c.insert(k("setvar"), r);
        v.values[0] = "count"; v.values[1] = "-arg*2+i";
        String code = c.toCode("c");
        check(code.contains("say(\"\\\"); run(\\\"/kill\\\") // \\\\ \" + toString(v_count) + \" \" + toString(vx_i))"), "лапки й \\ у тексті екрановано: " + line(code, "say("));
        check(code.contains("run(\"/give @p \" + toString(vx_arg) + \" \\\"x\\\"\")"), "команда з {arg}: " + line(code, "run(\"/give"));
        check(code.contains("v_count = (-vx_arg * 2 + vx_i)"), "вираз: " + line(code, "v_count ="));
        check(code.contains("var v_count = 0"), "змінні оголошено");
        check(code.indexOf("func main()") > 0 && code.trim().endsWith("}") && code.contains("vxRun()"), "main з vxRun");
        int open = 0; for (char ch : code.replaceAll("\"([^\"\\\\]|\\\\.)*\"", "").toCharArray()) { if (ch == '{') open++; if (ch == '}') open--; check(open >= 0, "дужки"); }
        check(open == 0, "фігурні дужки збалансовані");
        // кілька подій одного виду - один обробник гри
        BlockProgram e = new BlockProgram();
        e.insert(k("second"), null); e.insert(k("second"), null); e.insert(k("start"), null);
        e.insert(k("placed"), null); e.insert(k("broken"), null);
        Node c1 = e.insert(k("command"), null), c2 = e.insert(k("command"), null);
        String ec = e.toCode("e");
        check(count(ec, "on(\"second\"") == 1 && count(ec, "on(\"block\"") == 1 && count(ec, "command(\"hello\"") == 1, "одна підписка на вид події, однакові команди - раз");
        check(count(ec, "    vx_h1(ev)\n") == 1 && count(ec, "    vx_h2(ev)\n") == 1 && ec.contains("if (!vx_started)"), "обидві «кожну секунду» викликаються, «запуск» - раз");
        System.out.println("VoxelinBlocksTest: " + n + " перевірок OK");
    }

    static int count(String s, String sub) { int c = 0, i = 0; while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); } return c; }
    static String line(String code, String has) { for (String l : code.split("\n")) if (l.contains(has)) return l.trim(); return "(нема)"; }
}
