import { createArenaRenderer } from "./renderer.js";
import { createFrameModel } from "./frame-model.js";
import { reconMatch } from "./recon-view-model.js";

const $ = (id) => document.getElementById(id);
const renderer = createArenaRenderer($("field"));
const reduced = matchMedia("(prefers-reduced-motion: reduce)");
let cameraPoint = null;
let lastDraw = 0;
const labels = {
  AMMUNITION: "Боезапас",
  REPAIR: "Ремонт",
  TURRET_MODULE: "Модуль башни",
};
let key = "",
  teacher = false,
  generation = 0,
  current = null,
  model = null,
  received = 0;
let personal = null,
  roster = [],
  controller = new AbortController();

function clear() {
  generation++;
  controller.abort();
  controller = new AbortController();
  key = "";
  current = model = personal = null;
  cameraPoint = null;
  roster = [];
  $("key").value = "";
  for (const id of ["ranking", "supplies", "events"]) $(id).replaceChildren();
  $("camera").replaceChildren(new Option("Всё поле", "all"));
  $("equipment").textContent = "Нет наблюдения.";
  $("supplies-empty").textContent = "Пока нет наблюдения.";
  $("clock").textContent = "—";
  $("connection-panel").open = true;
  renderer.render(createFrameModel({ match: null, frame: null }));
}

async function request(path) {
  const response = await fetch(`/api/v2${path}`, {
    signal: controller.signal,
    cache: "no-store",
    headers: { Authorization: `Bearer ${key}` },
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
  return body;
}

function list(id, texts) {
  $(id).replaceChildren(
    ...texts.map((text) => {
      const element = document.createElement("li");
      element.textContent = text;
      return element;
    }),
  );
}

function paintDetails() {
  if (!current) return;
  const selected = $("camera").value;
  const slot = selected === "all" ? (current.self ?? -1) : Number(selected);
  const tank = current.state.tanks.find((value) => value.slot === slot);
  const equipment =
    personal?.equipment || current.state.combat?.equipment?.[slot];
  const name = roster.find((value) => value.slot === slot)?.name || "Танк";
  $("equipment").textContent = !tank
    ? "Выберите танк для крупного плана."
    : `${name} · ${tank.health} HP\n` +
      (equipment
        ? `Снаряды: ${equipment.ammunition} / 24\nБашня: ${equipment.turretUpgrade ? "+35%" : "обычная"}\nРемонт: ${(equipment.repairTicks / current.rules.tickRate).toFixed(1)} / 3 с\n`
        : "") +
      `Перезарядка: ${(tank.cooldownTicks / current.rules.tickRate).toFixed(1)} с · Щит: ${(tank.shieldTicks / current.rules.tickRate).toFixed(1)} с`;
}

function apply(next, frame) {
  const previous = current;
  current = next;
  personal = frame?.observation?.combat || null;
  roster = frame?.players || next.players;
  if (!next.state.combat)
    throw new Error(
      "Сервер использует другой режим. Нужен config/combat.properties.",
    );
  const supplies = next.state.combat.supplies;
  const selection = $("camera").value;
  const candidates = next.players.filter((p) =>
    next.state.tanks.some((t) => t.slot === p.slot),
  );
  $("camera").replaceChildren(
    new Option("Всё поле", "all"),
    ...candidates.map((p) => new Option(p.name, String(p.slot))),
  );
  $("camera").value = candidates.some((p) => String(p.slot) === selection)
    ? selection
    : !previous && next.partial
      ? String(next.self)
      : "all";
  model = createFrameModel({
    match: next,
    frame: next.state,
    previousFrame: previous?.id === next.id ? previous.state : null,
    previousLive: previous?.live,
    focusSlot: next.self ?? -1,
  });
  received = performance.now();
  const scores = next.state.combat.scores;
  list(
    "ranking",
    [...roster]
      .sort((a, b) => (scores[b.slot] || 0) - (scores[a.slot] || 0))
      .map((p) => `${p.name}: ${scores[p.slot] ?? 0}`),
  );
  list(
    "supplies",
    supplies.map(
      (s) =>
        `${labels[s.kind]} #${s.id}: ${s.health === 0 ? "разрушено" : `${s.stock}/${s.capacity}, прочность ${s.health}/3`}`,
    ),
  );
  $("supplies-empty").textContent = supplies.length
    ? ""
    : "В текущем обзоре расходников нет. За его пределами состояние неизвестно.";
  list(
    "events",
    (next.live?.events || [])
      .slice(-6)
      .reverse()
      .map((e) =>
        e.reason === "DESTROYED"
          ? `${e.actorName || "Неизвестный"} → ${e.victimName}`
          : `${e.victimName}: ${e.reason}`,
      ),
  );
  $("clock").textContent =
    `${(next.state.tick / next.rules.tickRate).toFixed(1)} с`;
  $("view-note").textContent = next.partial
    ? "Личный обзор. Затемнённые области сейчас неизвестны."
    : "Полное поле преподавателя. Цвета различают отдельных участников.";
  $("status").textContent =
    `${next.partial ? "Личное наблюдение" : "Полное поле"} · ${roster.length} участников · подключено`;
  $("connection-panel").open = false;
  paintDetails();
}

async function poll(run) {
  while (key && run === generation) {
    try {
      const owner = await request(teacher ? "/lobby" : "/me");
      const id = teacher ? owner.activeMatchId : owner.matchId;
      if (run !== generation) return;
      if (!id)
        $("status").textContent =
          "Ожидание допуска или свободного места. Запустите student.Main.";
      else {
        const frame = await request(
          `/matches/${encodeURIComponent(id)}${teacher ? "" : "/recon"}`,
        );
        if (run !== generation) return;
        apply(teacher ? frame : reconMatch(frame), teacher ? null : frame);
      }
    } catch (error) {
      if (run !== generation || error.name === "AbortError") return;
      $("status").textContent = `Нет свежего снимка: ${error.message}`;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

$("connect").addEventListener("submit", async (event) => {
  event.preventDefault();
  const value = $("key").value.trim();
  const role = $("role").value;
  clear();
  key = value;
  teacher = role === "teacher";
  const run = generation;
  $("status").textContent = "Подключение…";
  try {
    await request(teacher ? "/admin/players" : "/me");
    if (run === generation) poll(run);
  } catch (error) {
    if (run !== generation) return;
    clear();
    $("status").textContent = error.message;
  }
});
$("disconnect").addEventListener("click", () => {
  clear();
  $("status").textContent = "Отключено. Ключ не сохраняется.";
});
$("camera").addEventListener("change", paintDetails);
$("key-file").addEventListener("change", async (event) => {
  const file = event.target.files[0];
  if (!file) return;
  if (file.size > 256) {
    $("status").textContent = "Нужен небольшой файл личного токена.";
    return;
  }
  $("key").value = (await file.text()).trim();
  $("role").value = "player";
});
function animate(now) {
  if (model && current) {
    const slot = $("camera").value;
    const target = current.state.tanks.find((t) => String(t.slot) === slot);
    const elapsed = Math.min(100, Math.max(0, now - lastDraw));
    lastDraw = now;
    if (target) {
      const amount = reduced.matches ? 1 : 1 - Math.exp(-elapsed / 100);
      cameraPoint = cameraPoint || { ...target.position };
      cameraPoint.x += (target.position.x - cameraPoint.x) * amount;
      cameraPoint.y += (target.position.y - cameraPoint.y) * amount;
    } else cameraPoint = null;
    const size = Math.min(620, model.width, model.height);
    const camera = target
      ? {
          width: size,
          height: size,
          x: Math.max(
            0,
            Math.min(model.width - size, cameraPoint.x - size / 2),
          ),
          y: Math.max(
            0,
            Math.min(model.height - size, cameraPoint.y - size / 2),
          ),
        }
      : null;
    renderer.render(model, {
      time: now,
      progress: (now - received) / 100,
      reducedMotion: reduced.matches,
      camera,
    });
  }
  requestAnimationFrame(animate);
}
clear();
requestAnimationFrame(animate);
