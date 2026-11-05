package voxelin.loader;

/** Текст scripts/voxelin.nx - помічника для скриптів NyxilumLang (ScriptHost пише його в теку скриптів при кожному запуску). */
final class ScriptHelper {
    private ScriptHelper() {}

    static final String FILE = "voxelin.nx";

    static final String TEXT = """
// Помічник для скриптів Voxelin. Файл гри: перезаписується при кожному запуску - свої зміни роби у власних файлах.
// Підключення: import "../voxelin.nx" у scripts/<назва>/main.nx, далі on(...)/command(...) і наприкінці vxRun().

var vxHandlers = newMap()
var vxCommands = newMap()
var vxRid = 0
var vxQueue = []
var vxRunning = true

func vxSend(m) {
    print(toJson(m))
}

func vxOp(op) {
    var m = newMap()
    mapSet(m, "op", op)
    return m
}

// Надіслати запит і дочекатись відповіді; події, що прийшли тим часом, обробляться пізніше по черзі.
func vxRequest(m) {
    vxRid = vxRid + 1
    mapSet(m, "rid", vxRid)
    vxSend(m)
    while (true) {
        var line = readLine()
        if (isNull(line) || line == "") {
            vxRunning = false
            return null
        }
        var ev = fromJson(line)
        if (mapGet(ev, "ev") == "reply" && mapGet(ev, "rid") == vxRid) {
            return ev
        }
        append(vxQueue, ev)
    }
}

func say(text) {
    var m = vxOp("say")
    mapSet(m, "text", toString(text))
    vxSend(m)
}

func log(text) {
    var m = vxOp("log")
    mapSet(m, "text", toString(text))
    vxSend(m)
}

func setBlock(x, y, z, block) {
    var m = vxOp("setBlock")
    mapSet(m, "x", x)
    mapSet(m, "y", y)
    mapSet(m, "z", z)
    mapSet(m, "block", block)
    vxSend(m)
}

// Назва блока ("voxelin:stone") або null, якщо чанк не завантажений.
func getBlock(x, y, z) {
    var m = vxOp("getBlock")
    mapSet(m, "x", x)
    mapSet(m, "y", y)
    mapSet(m, "z", z)
    var r = vxRequest(m)
    if (isNull(r)) {
        return null
    }
    return mapGet(r, "block")
}

// Виконати команду гри (/fill, /summon, /time ...) від імені скрипта. Повертає масив рядків відповіді.
func run(cmd) {
    var m = vxOp("run")
    mapSet(m, "cmd", cmd)
    var r = vxRequest(m)
    if (isNull(r)) {
        return []
    }
    return mapGet(r, "lines")
}

// Гравець: мапа x, y, z (ноги, блоки), health, alive, creative; null - гравця нема.
func player() {
    var r = vxRequest(vxOp("player"))
    if (isNull(r)) {
        return null
    }
    return mapGet(r, "player")
}

// Час світу: мапа time (тіки від початку), day (номер доби), dayTicks (довжина доби).
func worldTime() {
    return vxRequest(vxOp("time"))
}

// Підписка на подію: "tick" (20/с), "second" (раз на секунду), "block" (гравець змінив блок), "world" (новий світ чи вимір), "stop" (скрипт зупиняють),
// "join" / "leave" (гравець чи гість: name, guest, reason), "mob_death" (mob, boss, x, y, z), "chat" (name, text, guest).
func on(event, handler) {
    mapSet(vxHandlers, event, handler)
    if (event != "stop") {
        var m = vxOp("subscribe")
        mapSet(m, "event", event)
        vxSend(m)
    }
}

// Своя команда гри: /назва аргументи. handler(ev): ev.args - масив, ev.x/y/z - де гравець.
func command(name, usage, handler) {
    mapSet(vxCommands, name, handler)
    var m = vxOp("command")
    mapSet(m, "name", name)
    mapSet(m, "usage", usage)
    vxSend(m)
}

func vxDispatch(ev) {
    var kind = mapGet(ev, "ev")
    if (kind == "command") {
        var name = mapGet(ev, "name")
        if (mapHas(vxCommands, name)) {
            var h = mapGet(vxCommands, name)
            h(ev)
        }
        return null
    }
    if (mapHas(vxHandlers, kind)) {
        var h = mapGet(vxHandlers, kind)
        h(ev)
    }
    return null
}

func vxHandle(ev) {
    if (typeOf(ev) != "map") {
        return null
    }
    if (mapGet(ev, "ev") == "ping") {
        var m = vxOp("pong")
        mapSet(m, "n", mapGet(ev, "n"))
        vxSend(m)
        return null
    }
    try {
        vxDispatch(ev)
    } catch (e) {
        log("помилка в обробнику " + toString(mapGet(ev, "ev")) + ": " + e)
    }
    return null
}

// Головний цикл: читає події гри, поки гра не закриє скрипт.
func vxRun() {
    vxSend(vxOp("ready"))
    while (vxRunning) {
        while (len(vxQueue) > 0) {
            var ev = vxQueue[0]
            removeAt(vxQueue, 0)
            vxHandle(ev)
        }
        if (!vxRunning) {
            break
        }
        var line = readLine()
        if (isNull(line) || line == "") {
            vxRunning = false
        } else {
            vxHandle(fromJson(line))
        }
    }
    if (mapHas(vxHandlers, "stop")) {
        var h = mapGet(vxHandlers, "stop")
        h(newMap())
    }
}
""";
}
