import { createArenaRenderer } from "./renderer.js";
import { teamOf } from "./team-roster.js";
import { createFrameModel, tankPalette } from "./frame-model.js";
import {
  reconMatch,
  replayMatch,
  chooseActionFocus,
  cameraTarget,
  focusFacts,
  cameraViewport,
  cargoSnapshot,
  observedCarriers,
  gameClock,
  projectileSnapshot,
  controlZoneSnapshot,
} from "./recon-view-model.js";

const $ = (id) => document.getElementById(id);
const renderer = createArenaRenderer($("field"));
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
const names = ["А", "Б", "В", "Г"];
let credential = "",
  teacher = false,
  generation = 0,
  match = null,
  previous = null,
  model = null;
let received = 0,
  span = 150,
  recording = null,
  playing = false,
  replayIndex = 0,
  replayTime = 0;
let controller = new AbortController(),
  failures = 0;
let fileSelection = 0;
let focusedSlot = null,
  focusSince = 0,
  cameraCenter = null,
  lastCameraTime = 0,
  shownEvents = [],
  observedEvents = [],
  zoneEvents = [];
let roundRequestId = null,
  roundExpectedId = null;
const tell = (text) => {
  $("notice").textContent = text;
};

function clearSession() {
  document.body.dataset.connection = "offline";
  $("setup-panel").open = true;
  $("empty-state").hidden = false;
  $("roster-count").textContent = "0";
  $("roster-heading").textContent = "Участники";
  generation++;
  controller.abort();
  controller = new AbortController();
  credential = "";
  match = null;
  previous = null;
  model = null;
  recording = null;
  playing = false;
  failures = 0;
  $("credential").value = "";
  $("credential-file").value = "";
  fileSelection++;
  focusedSlot = null;
  focusSince = 0;
  cameraCenter = null;
  shownEvents = [];
  observedEvents = [];
  zoneEvents = [];
  $("arena-title").textContent = "Танковая арена";
  $("score-label").textContent = "Доставки";
  $("score-purpose").textContent = "Доставьте груз на свою базу — команда получит очко.";
  $("legend-cargo").hidden = false;
  $("legend-source-text").textContent = "Место появления";
  $("legend-source-icon").style.borderStyle = "";
  $("objectives-heading").textContent = "Грузы сейчас";
  $("objective-section").setAttribute("aria-label", "Грузы в текущем обзоре");
  $("field").setAttribute("aria-label", "Танки, препятствия, грузы и базы");
  $("demo-label").textContent = "Открыть пример доставки";
  $("cargo-carriers").replaceChildren();
  $("cargo-availability").textContent = "Откройте бой, чтобы увидеть грузы.";
  $("cargo-distance-help").hidden = true;
  delete $("cargo-carriers").dataset.key;
  $("shot-shooters").replaceChildren();
  $("shots-empty").hidden = false;
  $("shots-unknown").hidden = true;
  delete $("shot-shooters").dataset.key;
  $("camera-mode").replaceChildren(
    new Option("Автоматически", "auto"),
    new Option("Всё поле", "all"),
  );
  $("focus-name").textContent = "Выберите танк или откройте пример";
  $("focus-facts").textContent = "Камера покажет перевозчика и ближайших соперников.";
  $("camera-indicator").textContent = "";
  $("events").replaceChildren();
  $("events-empty").hidden = false;
  const overview = $("overview").getContext("2d");
  overview.clearRect(0, 0, 180, 180);
  $("roster").replaceChildren();
  $("scores").replaceChildren();
  delete $("scores").dataset.key;
  delete $("perspective").dataset.roster;
  delete $("camera-mode").dataset.roster;
  $("team-player").replaceChildren();
  $("team-section").hidden = true;
  $("round-section").hidden = true;
  $("perspective").replaceChildren(new Option("Полное поле преподавателя", "all"));
  $("perspective").disabled = true;
  $("playback").hidden = true;
  $("connection").textContent = "Нет подключения";
  $("mode").textContent = "Арена ожидает участников";
  $("tick").textContent = "Шаг —";
  renderer.render(createFrameModel({ match: null, frame: null }));
}

async function request(path, options = {}) {
  const res = await fetch(`/api/v2${path}`, {
    signal: controller.signal,
    cache: "no-store",
    ...options,
    headers: {
      ...(credential ? { Authorization: `Bearer ${credential}` } : {}),
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...options.headers,
    },
  });
  const text = await res.text();
  if (text.length > 4 * 1024 * 1024) throw new Error("Ответ слишком большой");
  const value = JSON.parse(text);
  if (!res.ok) {
    const error = new Error(value.message || `Ошибка ${res.status}`);
    error.status = res.status;
    error.code = value.code;
    throw error;
  }
  return value;
}

async function refreshTeamPlayers(current) {
  const selected = $("team-player").value;
  const players = await request("/admin/players");
  if (current !== generation) return;
  $("team-player").replaceChildren(...players.map((p) => new Option(p.name, p.id)));
  if (players.some((p) => p.id === selected)) $("team-player").value = selected;
}

function apply(next) {
  if (!match) $("setup-panel").open = false;
  $("empty-state").hidden = true;
  previous =
    match?.id === next.id &&
    match?.partial === next.partial &&
    match?.recon?.roundId === next.recon?.roundId
      ? match
      : null;
  if (previous?.state.tick !== next.state.tick) {
    span = Math.max(40, Math.min(250, performance.now() - received));
    received = performance.now();
  }
  match = next;
  const controlMode = match.map?.id === "recon-control";
  const combatMode = match.map?.id === "team-combat";
  $("friendly-rule").textContent = combatMode
    ? "Стены и танки перекрывают пули. Союзные попадания не наносят урон. Исчезновение из обзора не означает гибель."
    : "Стены перекрывают пули и обзор. Союзные снаряды тоже наносят урон. Исчезновение из обзора не означает гибель.";
  $("arena-title").textContent = controlMode ? "Контроль точек" : "Разведка и прикрытие";
  $("score-label").textContent = controlMode ? "Очки" : "Доставки";
  $("scores").setAttribute("aria-label", controlMode ? "Очки команд" : "Доставки команд");
  $("score-purpose").textContent = controlMode
    ? "Удерживайте точки без соперников: суммарное время контроля приносит очки."
    : "Доставьте груз на свою базу — команда получит очко.";
  $("legend-cargo").hidden = controlMode;
  $("legend-source-text").textContent = controlMode ? "Точка" : "Место появления";
  $("legend-source").title = controlMode ? "Область контроля" : "Известное место появления груза";
  $("objectives-heading").textContent = controlMode ? "Точки сейчас" : "Грузы сейчас";
  $("objective-section").setAttribute(
    "aria-label",
    controlMode ? "Контроль точек" : "Грузы в текущем обзоре",
  );
  $("field").setAttribute(
    "aria-label",
    controlMode ? "Танки и точки контроля" : "Танки, препятствия, грузы и базы",
  );
  $("legend-source-icon").style.borderStyle = controlMode ? "solid" : "";
  $("demo-label").textContent = "Открыть пример доставки";
  $("objective-rule").textContent = controlMode
    ? "Время контроля точек складывается: за каждую полную секунду — очко. Несколько танков одного отряда в точке доход не увеличивают. При появлении соперника точка не приносит время. Остаток сохраняется."
    : "Груз приносит очко всей команде. При гибели груз падает; танк возвращается на базу.";
  $("legend-source").hidden = combatMode;
  $("legend-cargo").hidden = controlMode || combatMode;
  const selectedTeam = $("team-number").value;
  $("team-number").replaceChildren(...Array.from({length: match.recon?.teams || 2},
    (_, i) => new Option(names[i], String(i))));
  if (Number(selectedTeam) < (match.recon?.teams || 2)) $("team-number").value = selectedTeam;
  if (combatMode) {
    $("arena-title").textContent = "Командный бой";
    $("score-label").textContent = "Уничтожения";
    $("scores").setAttribute("aria-label", "Уничтожения противников по командам");
    $("score-purpose").textContent = "Уничтожение противника приносит команде одно очко.";
    $("objective-rule").textContent = "Союзники не получают урон друг от друга, но перекрывают огонь. После гибели танк возвращается в бой. Совместное добивание засчитывается команде.";
    $("objectives-heading").textContent = "В текущем обзоре";
    $("objective-section").setAttribute("aria-label", "Состояние видимых танков");
    $("field").setAttribute("aria-label", "Командный бой: танки и укрытия");
  }
  updateCameraChoices();
  updateFocus();
  model = createFrameModel({
    match,
    frame: match.state,
    previousFrame: previous?.state,
    previousLive: previous?.live,
    focusSlot: focusedSlot ?? match.self ?? -1,
    controlZones: controlZoneSnapshot(match),
  });
  $("mode").textContent = recording
    ? "Повтор боя"
    : match.partial
      ? "Личное наблюдение танка"
      : "Полное поле преподавателя";
  $("tick").textContent = `Игровое время ${gameClock(match.state.tick, match.rules.tickRate)}`;
  $("tick").title =
    `Игровое время с запуска арены, шаг ${match.state.tick}; при новом бое не сбрасывается.`;
  $("caption").textContent = match.partial
    ? "Затемнённая область сейчас не наблюдается. Нажмите на видимый танк, чтобы следить за ним."
    : combatMode ? "Выберите танк для крупного плана. Цвет обозначает команду; кольцо — действующий щит." : controlMode
      ? "Цвет точки показывает контролирующий отряд; пунктир означает спор."
      : "Нажмите на танк, чтобы следить за ним. Пунктир — направление к базе по прямой, не маршрут.";
  const cargoSpeed = Math.round((match.recon?.cargoSpeedFactor ?? 1) * 100);
  $("cargo-rule").textContent = controlMode || combatMode
    ? "Щит защищает от снарядов, но во время его действия танк не стреляет."
    : `С грузом: ${cargoSpeed}% обычной скорости. Стрелять нельзя; щит доступен.`;
  $("vision-rule").textContent =
    `Дальность обзора: ${match.recon?.visionRadius ?? "—"} ед. Стены закрывают обзор.`;
  $("roster-heading").textContent = match.partial ? "Видимые танки" : "Участники";
  $("vision-note").textContent = match.partial
    ? "Только собственные наблюдения. Пропавший из обзора танк может быть жив."
    : controlMode || combatMode
      ? "Цвет обозначает отряд."
      : "Цвет обозначает команду, ромб — перевозчика.";
  const scoreKey = JSON.stringify(match.recon?.teamScores);
  if ($("scores").dataset.key !== scoreKey) {
    $("scores").dataset.key = scoreKey;
    $("scores").replaceChildren();
    (match.recon?.teamScores || []).forEach((score, i) => {
      const item = document.createElement("div");
      item.className = "team-score";
      item.style.setProperty("--team", tankPalette[i]);
      const label = document.createElement("span");
      label.textContent = `Отряд ${names[i]}`;
      const value = document.createElement("strong");
      value.textContent = score;
      item.append(label, value);
      $("scores").append(item);
    });
  }
  const focusedRoster = document.activeElement?.closest(".member")?.dataset.slot;
  $("roster").replaceChildren();
  let count = 0;
  for (const tank of match.state.tanks) {
    if (match.live && !match.players.some((p) => p.slot === tank.slot)) continue;
    const row = document.createElement("div");
    row.className = "member";
    row.dataset.slot = String(tank.slot);
    row.setAttribute("role", "listitem");
    const team = teamOf(match, tank.slot);
    row.style.setProperty("--team", tankPalette[team]);
    const mark = document.createElement("span");
    mark.className = "team-mark";
    mark.textContent = names[team];
    mark.setAttribute("aria-label", `Отряд ${names[team]}`);
    const detail = document.createElement("div");
    detail.className = "member-info";
    const name = document.createElement("strong");
    name.textContent =
      match.players.find((p) => p.slot === tank.slot)?.name || `Танк ${tank.slot + 1}`;
    const select = document.createElement("button");
    select.type = "button";
    select.className = "focus-crew";
    select.title = `Показать ${name.textContent} крупнее`;
    select.append(name);
    select.addEventListener("click", () => {
      $("camera-mode").value = String(tank.slot);
      if (match) apply(match);
    });
    const cargo = match.state.delivery?.cargo.some((c) => c.carrierSlot === tank.slot);
    row.classList.toggle("carrying", Boolean(cargo));
    row.classList.toggle("low-health", tank.health === 1);
    const status = document.createElement("span");
    status.textContent =
      tank.health > 0
        ? `${tank.health} HP${cargo ? " · везёт груз" : tank.shieldTicks > 0 ? " · щит" : ""}`
        : "Возрождение";
    const health = document.createElement("div");
    health.className = "member-health";
    health.setAttribute("aria-hidden", "true");
    for (let hp = 0; hp < Math.min(12, match.rules.hitPoints || 3); hp++) {
      const segment = document.createElement("i");
      segment.classList.toggle("filled", hp < tank.health);
      health.append(segment);
    }
    detail.append(select, status, health);
    row.append(mark, detail);
    $("roster").append(row);
    count++;
  }
  $("roster-count").textContent = String(count);
  if (focusedRoster !== undefined) {
    $("roster")
      .querySelector(`.member[data-slot="${focusedRoster}"] .focus-crew`)
      ?.focus({ preventScroll: true });
  }
  updateStory();
  updateCargo();
  updateShots();
  updateEvents();
  drawOverview();
}

function playerName(slot) {
  return match?.players.find((player) => player.slot === slot)?.name || `Танк ${slot + 1}`;
}

function updateCameraChoices() {
  const camera = $("camera-mode");
  const value = camera.value;
  const available = match.state.tanks.filter(
    (tank) => !match.live || match.players.some((player) => player.slot === tank.slot),
  );
  const signature = JSON.stringify(available.map((tank) => [tank.slot, playerName(tank.slot)]));
  if (camera.dataset.roster === signature) return;
  camera.dataset.roster = signature;
  camera.replaceChildren(new Option("Автоматически", "auto"), new Option("Всё поле", "all"));
  for (const tank of available) camera.append(new Option(playerName(tank.slot), String(tank.slot)));
  if (
    value !== "auto" &&
    value !== "all" &&
    !available.some((tank) => String(tank.slot) === value)
  ) {
    camera.append(new Option("Танк вне обзора", value));
  }
  camera.value = value || "auto";
}

function updateFocus() {
  const mode = $("camera-mode").value;
  if (mode === "all") {
    focusedSlot = null;
    return;
  }
  if (mode !== "auto") {
    focusedSlot = Number(mode);
    return;
  }
  const existing = match.state.tanks.find(
    (tank) => tank.slot === focusedSlot && tank.health > 0 && focusFacts(match, tank.slot),
  );
  const now = performance.now();
  const next = chooseActionFocus(match);
  const existingFacts = existing && focusFacts(match, existing.slot);
  const nextFacts = next !== null && focusFacts(match, next);
  const quietAndBusyElsewhere =
    existingFacts &&
    nextFacts &&
    existingFacts.opponentsNearby === 0 &&
    nextFacts.opponentsNearby >= 2;
  if (
    !existing ||
    now - focusSince >= 5000 ||
    (quietAndBusyElsewhere && now - focusSince >= 1500)
  ) {
    if (next !== focusedSlot || !existing) focusSince = now;
    focusedSlot = next;
  }
}

function updateStory() {
  const facts = focusedSlot === null ? null : focusFacts(match, focusedSlot);
  $("camera-indicator").textContent = $("camera-mode").value === "auto" ? "Авто" : "";
  if (!facts) {
    $("focus-name").textContent = focusedSlot === null ? "Всё поле" : "Танк вне обзора";
    $("focus-facts").textContent =
      focusedSlot === null
        ? "Выберите танк в списке кадра, чтобы приблизить его."
        : "В текущем снимке нет его положения.";
    return;
  }
  $("focus-name").textContent = playerName(focusedSlot);
  const team = teamOf(match, focusedSlot);
  const status = facts.tank.health > 0 ? `${facts.tank.health} HP` : "Возрождение";
  const cargo =
    match.map?.id === "recon-control" || match.map?.id === "team-combat"
      ? ""
      : facts.carrying
        ? `Несёт груз. Доставка на базу ${names[facts.destination?.id ?? team]} принесёт очко; пунктир на схеме — направление, не маршрут.`
        : "Груза нет в текущем наблюдении.";
  const vicinity = facts.opponentsNearby
    ? `Соперников рядом: ${facts.opponentsNearby}.`
    : "Видимых соперников рядом нет.";
  const shots = (match.state.projectiles || []).filter((shot) => shot.owner === focusedSlot).length;
  const shotsText = shots ? `Видимых снарядов этого танка: ${shots}.` : "";
  $("focus-facts").textContent =
    `Отряд ${names[team]} · ${status}. ${cargo} ${vicinity} ${shotsText}`;
}

function updateCargo() {
  if (match.map?.id === "team-combat") {
    $("cargo-availability").textContent = match.partial
      ? "Сведения вне личного обзора неизвестны." : "Текущее состояние команд на поле.";
    $("cargo-distance-help").hidden = true;
    const container = $("cargo-carriers");
    delete container.dataset.key;
    container.replaceChildren(...Array.from({length: match.recon.teams}, (_, team) => {
      const tanks = match.state.tanks.filter(t => t.health > 0 && teamOf(match, t.slot) === team
        && (!match.live || match.players.some(p => p.slot === t.slot)));
      const row = document.createElement("p");
      row.className = "cargo-carrier";
      row.textContent = `Отряд ${names[team]} · ${match.partial ? "видимых" : "в бою"}: ${tanks.length} · под щитом: ${tanks.filter(t => t.shieldTicks > 0).length}`;
      return row;
    }));
    return;
  }
  if (match.map?.id === "recon-control") {
    const zones = controlZoneSnapshot(match);
    $("cargo-availability").textContent = match.partial
      ? "Состояние показано, только когда точка полностью видна."
      : "Отряд получает очки за время контроля; противники останавливают начисление.";
    $("cargo-distance-help").hidden = true;
    const container = $("cargo-carriers");
    delete container.dataset.key;
    container.replaceChildren(
      ...zones.map((zone, index) => {
        const row = document.createElement("div");
        row.className = "cargo-carrier";
        const label = document.createElement("strong");
        label.textContent = `Точка ${index + 1}`;
        const description = document.createElement("span");
        description.textContent =
          zone.status === "controlled"
            ? `Отряд ${names[zone.team]}`
            : zone.status === "contested"
              ? "Спорная"
              : zone.status === "empty"
                ? "Свободна"
                : "Неизвестно";
        if (zone.team !== null) row.style.setProperty("--team", tankPalette[zone.team]);
        row.append(label, description);
        return row;
      }),
    );
    return;
  }
  const { carriers, free } = cargoSnapshot(match);
  $("cargo-availability").textContent = match.partial
    ? `Видимых свободных грузов: ${free}`
    : `Свободных грузов: ${free}`;
  $("cargo-distance-help").hidden = carriers.length === 0;
  const entries = carriers.map((carrier) => ({
    ...carrier,
    name: playerName(carrier.slot),
    approx: Math.round(carrier.distance / 10) * 10,
  }));
  const key = JSON.stringify(entries);
  const container = $("cargo-carriers");
  if (container.dataset.key === key) return;
  const activeSlot = document.activeElement?.closest(".cargo-carrier")?.dataset.slot;
  container.dataset.key = key;
  container.replaceChildren(
    ...entries.map((carrier) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "cargo-carrier";
      button.dataset.slot = String(carrier.slot);
      button.style.setProperty("--team", tankPalette[carrier.team]);
      const title = document.createElement("strong");
      title.textContent = carrier.name;
      const description = document.createElement("span");
      description.textContent = `Отряд ${names[carrier.team]} · до базы ≈ ${carrier.approx} ед.`;
      button.append(title, description);
      button.addEventListener("click", () => {
        $("camera-mode").value = String(carrier.slot);
        if (match) apply(match);
      });
      return button;
    }),
  );
  if (activeSlot !== undefined)
    container
      .querySelector(`.cargo-carrier[data-slot="${activeSlot}"]`)
      ?.focus({ preventScroll: true });
}

function updateShots() {
  const { shooters, unknown } = projectileSnapshot(match);
  const container = $("shot-shooters");
  $("shots-empty").hidden = shooters.length + unknown > 0;
  $("shots-unknown").hidden = unknown === 0;
  $("shots-unknown").textContent = `Снарядов без видимого автора: ${unknown}.`;
  const entries = shooters.map((shooter) => ({
    ...shooter,
    name: playerName(shooter.slot),
  }));
  const key = JSON.stringify(entries);
  if (container.dataset.key === key) return;
  const activeSlot = document.activeElement?.closest(".shot-shooter")?.dataset.slot;
  container.dataset.key = key;
  container.replaceChildren(
    ...entries.map((shooter) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "shot-shooter";
      button.dataset.slot = String(shooter.slot);
      button.textContent = `${shooter.name} — ${shooter.count} сн.`;
      button.title = "Показать танк крупнее";
      button.addEventListener("click", () => {
        $("camera-mode").value = String(shooter.slot);
        if (match) apply(match);
      });
      return button;
    }),
  );
  if (activeSlot !== undefined)
    container
      .querySelector(`.shot-shooter[data-slot="${activeSlot}"]`)
      ?.focus({ preventScroll: true });
}

function updateEvents() {
  if (
    !previous ||
    previous.id !== match.id ||
    previous.partial !== match.partial ||
    previous.state.tick > match.state.tick
  ) {
    shownEvents = [];
  }
  if (!previous) {
    observedEvents = [];
    zoneEvents = [];
  }
  if (match.map?.id === "recon-control" && previous && previous.state.tick < match.state.tick) {
    const before = previous.recon?.teamScores || [];
    for (const [team, score] of (match.recon?.teamScores || []).entries()) {
      const diff = score - (before[team] ?? score);
      if (diff > 0)
        zoneEvents.unshift({
          text: `Отряд ${names[team]}: +${diff} за контроль`,
          tick: match.state.tick,
        });
    }
    zoneEvents = zoneEvents.slice(0, 5);
  }
  for (const change of observedCarriers(previous, match)) {
    observedEvents.unshift({
      text: `На снимке: ${playerName(change.slot)} везёт груз`,
      tick: change.tick,
    });
  }
  observedEvents = observedEvents.slice(0, 5);
  if (!recording && !match.partial && match.live?.events) {
    shownEvents = match.live.events
      .filter((event) => event.reason === "DESTROYED" || event.reason === "DELIVERED")
      .slice(-5)
      .reverse()
      .map((event) => ({
        text:
          event.reason === "DELIVERED"
            ? `${event.victimName} доставил груз`
            : event.actorName
              ? `${event.actorName} уничтожил ${event.victimName}`
              : `Уничтожен танк ${event.victimName}`,
        tick: event.tick,
      }));
  } else if (previous && previous.state.tick < match.state.tick) {
    const before = previous.recon?.teamScores || [];
    for (const [team, score] of (match.recon?.teamScores || []).entries()) {
      const diff = score - (before[team] ?? score);
      if (diff > 0 && match.map?.id !== "recon-control")
        shownEvents.unshift({
          text:
            match.map?.id === "recon-control"
              ? `Отряд ${names[team]}: +${diff} за контроль`
              : match.map?.id === "team-combat" ? `Отряд ${names[team]}: +${diff} за бой`
                : `Отряд ${names[team]} доставил груз${diff > 1 ? ` ×${diff}` : ""}`,
          tick: match.state.tick,
        });
    }
    if (recording) {
      for (const event of match.state.events || []) {
        if (event.type === "DESTROYED")
          shownEvents.unshift({
            text:
              event.actor >= 0
                ? `${playerName(event.actor)} уничтожил ${playerName(event.target)}`
                : `Уничтожен танк ${playerName(event.target)}`,
            tick: match.state.tick,
          });
      }
    }
    shownEvents = shownEvents.slice(0, 5);
  }
  const displayEvents = [...shownEvents, ...observedEvents, ...zoneEvents]
    .sort((a, b) => b.tick - a.tick)
    .slice(0, 5);
  $("events").replaceChildren(
    ...displayEvents.map((event) => {
      const row = document.createElement("li");
      row.textContent = `${event.text} · ${Math.floor(event.tick / (match.rules.tickRate || 20))} с`;
      return row;
    }),
  );
  $("events-empty").hidden = displayEvents.length > 0;
}

function currentCamera() {
  if (!model || focusedSlot === null || !focusFacts(match, focusedSlot)) return null;
  const target = model.tanks.find((tank) => tank.slot === focusedSlot);
  if (!target) return null;
  return cameraViewport(model.width, model.height, cameraCenter || target.position);
}

function drawOverview() {
  if (!model) return;
  const canvas = $("overview"),
    ctx = canvas.getContext("2d"),
    size = canvas.width;
  const sx = size / model.width,
    sy = size / model.height;
  ctx.fillStyle = "#dce3dc";
  ctx.fillRect(0, 0, size, size);
  ctx.fillStyle = "#91a2a0";
  for (const wall of model.walls)
    ctx.fillRect(wall.x * sx, wall.y * sy, wall.width * sx, wall.height * sy);
  for (const depot of model.delivery?.depots || []) {
    ctx.strokeStyle = tankPalette[depot.id] || "#526675";
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(depot.position.x * sx, depot.position.y * sy, 7, 0, Math.PI * 2);
    ctx.stroke();
    ctx.fillStyle = ctx.strokeStyle;
    ctx.font = "bold 10px Segoe UI";
    ctx.textAlign = "center";
    ctx.fillText(names[depot.id] || "", depot.position.x * sx, depot.position.y * sy + 3);
  }
  const facts = focusedSlot === null ? null : focusFacts(match, focusedSlot);
  if (facts?.carrying && facts.destination) {
    ctx.strokeStyle = "#815d20";
    ctx.lineWidth = 1.5;
    ctx.setLineDash([3, 3]);
    ctx.beginPath();
    ctx.moveTo(facts.tank.position.x * sx, facts.tank.position.y * sy);
    ctx.lineTo(facts.destination.position.x * sx, facts.destination.position.y * sy);
    ctx.stroke();
    ctx.setLineDash([]);
  }
  for (const tank of model.tanks) {
    if (tank.health <= 0) continue;
    ctx.fillStyle = tank.color;
    ctx.beginPath();
    ctx.arc(
      tank.position.x * sx,
      tank.position.y * sy,
      tank.slot === focusedSlot ? 4 : 2.5,
      0,
      Math.PI * 2,
    );
    ctx.fill();
  }
  const camera = currentCamera();
  if (camera) {
    ctx.strokeStyle = "#243f50";
    ctx.lineWidth = 2;
    ctx.strokeRect(camera.x * sx, camera.y * sy, camera.width * sx, camera.height * sy);
  }
}

async function poll(myGeneration) {
  while (credential && myGeneration === generation) {
    try {
      let id;
      if (teacher) id = (await request("/lobby")).activeMatchId;
      else id = (await request("/me")).matchId;
      if (!id) {
        $("connection").textContent = "Ожидание входа на поле";
      } else {
        const slot = $("perspective").value;
        let next;
        if (teacher && slot === "all") next = await request(`/matches/${encodeURIComponent(id)}`);
        else
          next = reconMatch(
            await request(
              `/matches/${encodeURIComponent(id)}/recon${teacher ? `?slot=${encodeURIComponent(slot)}` : ""}`,
            ),
          );
        if (myGeneration !== generation) return;
        if (slot !== $("perspective").value) continue;
        if (!next.recon)
          throw new Error(
            "Сервер запущен без режима разведки. Используйте config/recon.properties.",
          );
        if (teacher && !next.partial) {
          const signature = JSON.stringify(next.players.map((p) => [p.slot, p.name]));
          if ($("perspective").dataset.roster !== signature) {
            $("perspective").dataset.roster = signature;
            $("perspective").replaceChildren(new Option("Полное поле преподавателя", "all"));
            next.players.forEach((p) =>
              $("perspective").append(new Option(p.name, String(p.slot))),
            );
          }
        }
        apply(next);
        document.body.dataset.connection = "live";
        $("connection").textContent = "Подключено";
        failures = 0;
        tell("Получаем актуальные наблюдения.");
      }
    } catch (error) {
      if (myGeneration !== generation || error.name === "AbortError") return;
      failures++;
      document.body.dataset.connection = "stale";
      tell(error.message);
      $("connection").textContent = "Нет свежего снимка";
      if (error.status === 401 || error.status === 403) {
        clearSession();
        tell(error.message);
        return;
      }
    }
    await new Promise((resolve) =>
      setTimeout(resolve, failures ? Math.min(5000, 500 * failures) : 150),
    );
  }
}

$("choose-credential").addEventListener("click", () => $("credential-file").click());
$("credential-file").addEventListener("change", async (event) => {
  const file = event.target.files[0];
  event.target.value = "";
  if (!file) return;
  const selection = ++fileSelection;
  $("credential").value = "";
  try {
    if (file.size > 128) throw new Error();
    const value = (await file.text()).trim();
    if (!/^[A-Za-z0-9_-]{43}$/.test(value)) throw new Error();
    if (selection !== fileSelection) return;
    $("access").value = "player";
    $("credential").value = value;
    tell("Ключ участника прочитан. Нажмите «Подключиться».");
  } catch {
    if (selection === fileSelection) tell("Не удалось прочитать файл личного ключа участника.");
  }
});

$("connect-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const value = $("credential").value.trim();
  const selected = $("access").value;
  clearSession();
  credential = value;
  teacher = selected === "teacher";
  const current = generation;
  $("perspective").disabled = !teacher;
  tell("Подключаемся…");
  if (teacher) {
    try {
      await refreshTeamPlayers(current);
      if (current !== generation) return;
      $("team-section").hidden = false;
      $("round-section").hidden = false;
    } catch (error) {
      if (current !== generation) return;
      clearSession();
      tell(error.message);
      return;
    }
  }
  poll(current);
});
$("disconnect").addEventListener("click", () => {
  clearSession();
  tell("Доступ закрыт. Ключ не сохраняется в браузере.");
});
$("perspective").addEventListener("change", () => {
  previous = null;
  model = null;
  match = null;
  focusedSlot = null;
  cameraCenter = null;
  delete $("camera-mode").dataset.roster;
  $("camera-mode").replaceChildren(
    new Option("Автоматически", "auto"),
    new Option("Всё поле", "all"),
  );
  renderer.render(createFrameModel({ match: null, frame: null }));
});
$("camera-mode").addEventListener("change", () => {
  focusSince = 0;
  if (match) apply(match);
});
$("field-size").addEventListener("click", () => {
  const larger = document.body.classList.toggle("larger-field");
  $("field-size").textContent = larger ? "Обычный вид" : "Крупнее";
  $("field-size").setAttribute("aria-pressed", String(larger));
});
$("overview-reset").addEventListener("click", () => {
  $("camera-mode").value = "all";
  if (match) apply(match);
});
$("field").addEventListener("click", (event) => {
  if (!model || !match) return;
  const box = $("field").getBoundingClientRect();
  const scale = Math.min(box.width / model.width, box.height / model.height);
  const drawnWidth = model.width * scale,
    drawnHeight = model.height * scale;
  const x = (event.clientX - box.left - (box.width - drawnWidth) / 2) / drawnWidth;
  const y = (event.clientY - box.top - (box.height - drawnHeight) / 2) / drawnHeight;
  if (x < 0 || x > 1 || y < 0 || y > 1) return;
  const camera = currentCamera() || {
    x: 0,
    y: 0,
    width: model.width,
    height: model.height,
  };
  const point = {
    x: camera.x + x * camera.width,
    y: camera.y + y * camera.height,
  };
  const candidate = model.tanks
    .filter((tank) => tank.health > 0)
    .map((tank) => ({
      slot: tank.slot,
      distance: Math.hypot(tank.position.x - point.x, tank.position.y - point.y),
    }))
    .sort((a, b) => a.distance - b.distance)[0];
  if (
    candidate &&
    candidate.distance <= Math.max(model.tankRadius * 2, (camera.width / drawnWidth) * 16)
  ) {
    $("camera-mode").value = String(candidate.slot);
    apply(match);
  }
});
// Keep the request id on network errors: retrying cannot schedule a second start.
$("round-start").addEventListener("click", async () => {
  if (!roundRequestId) {
    if (
      !confirm(
        "Начать новый бой? Командный счёт, позиции и история боя сбросятся. Участники и команды сохранятся.",
      )
    )
      return;
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
    roundRequestId = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  const button = $("round-start");
  button.disabled = true;
  try {
    if (!roundExpectedId) {
      const current = await request("/admin/recon/round");
      roundExpectedId = current.roundId;
    }
    let result = await request("/admin/recon/round", {
      method: "POST",
      body: JSON.stringify({
        requestId: roundRequestId,
        expectedRoundId: roundExpectedId,
      }),
    });
    $("round-status").textContent = `Старт ожидается на такте ${result.startTick}.`;
    for (let attempts = 0; !result.applied && attempts < 40; attempts++) {
      await new Promise((resolve) => setTimeout(resolve, 250));
      result = await request(`/admin/recon/round/${roundRequestId}`);
    }
    if (!result.applied) throw new Error("сервер ещё не подтвердил старт");
    shownEvents = [];
    previous = null;
    $("round-status").textContent = `Бой начался на такте ${result.startTick}.`;
    roundRequestId = null;
    roundExpectedId = null;
  } catch (error) {
    if (error.code === "ROUND_CHANGED" || error.code === "ROUND_REQUEST_CONFLICT") {
      roundRequestId = null;
      roundExpectedId = null;
      $("round-status").textContent =
        `${error.message} Проверьте состояние поля и при необходимости начните новый бой.`;
    } else {
      $("round-status").textContent =
        `Не удалось подтвердить старт: ${error.message}. Повторите запрос этой кнопкой.`;
    }
  } finally {
    button.disabled = false;
  }
});

$("team-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    await request("/admin/recon/teams", {
      method: "POST",
      body: JSON.stringify({
        playerId: $("team-player").value,
        team: Number($("team-number").value),
      }),
    });
    tell("Команда назначена. Участник может подключиться.");
  } catch (error) {
    tell(error.message);
  }
});
$("team-section").addEventListener("toggle", async () => {
  if (!$("team-section").open || !teacher || !credential) return;
  const current = generation;
  try {
    await refreshTeamPlayers(current);
  } catch (error) {
    if (current === generation) tell(error.message);
  }
});

function loadRecord(value) {
  if (
    !value ||
    !Array.isArray(value.frames) ||
    !value.frames.length ||
    value.frames.length > 10000 ||
    !value.map ||
    !value.rules ||
    !value.mode
  )
    throw new Error("Нужна запись ReconExperiment с map, rules, mode и frames.");
  if (
    value.frames.some(
      (f) =>
        !f.arena ||
        !Array.isArray(f.arena.tanks) ||
        f.arena.tanks.length > 64 ||
        !Array.isArray(f.teamScores),
    )
  )
    throw new Error("Повреждены кадры записи.");
  clearSession();
  recording = value;
  replayIndex = 0;
  playing = true;
  replayTime = performance.now();
  $("seek").max = value.frames.length - 1;
  $("seek").value = 0;
  $("playback").hidden = false;
  $("connection").textContent = "Запись опыта";
  document.body.dataset.connection = "replay";
  $("play").textContent = "Пауза";
  $("experiment-note").textContent = value.label || "Реальная запись симуляции";
  apply(replayMatch(recording, 0));
  tell("Полная запись для разбора. Игровым клиентам эти сведения недоступны.");
}
$("replay-file").addEventListener("change", async (event) => {
  try {
    const file = event.target.files[0];
    if (!file) return;
    if (file.size > 24 * 1024 * 1024) throw new Error("Запись больше 24 МБ");
    loadRecord(JSON.parse(await file.text()));
  } catch (error) {
    tell(error.message);
  }
});
$("demo").addEventListener("click", async () => {
  try {
    const response = await fetch("/recon-demo.json", { cache: "no-store" });
    if (!response.ok)
      throw new Error("Пример ещё не добавлен. Загрузите JSON из результатов опыта.");
    loadRecord(await response.json());
  } catch (error) {
    tell(error.message);
  }
});
$("play").addEventListener("click", () => {
  if (recording && replayIndex === recording.frames.length - 1 && !playing) {
    replayIndex = 0;
    previous = null;
    match = null;
    $("seek").value = 0;
    apply(replayMatch(recording, 0));
  }
  playing = !playing;
  replayTime = performance.now();
  $("play").textContent = playing ? "Пауза" : "Продолжить";
});
$("seek").addEventListener("input", () => {
  if (!recording) return;
  replayIndex = Number($("seek").value);
  previous = null;
  match = null;
  if ($("camera-mode").value === "auto") focusedSlot = null;
  cameraCenter = null;
  apply(replayMatch(recording, replayIndex));
  replayTime = performance.now();
});
function draw(now) {
  if (recording && playing && replayIndex < recording.frames.length - 1) {
    const delta =
      ((recording.frames[replayIndex + 1].arena.tick - recording.frames[replayIndex].arena.tick) *
        1000) /
      recording.rules.tickRate;
    if (now - replayTime >= delta) {
      replayIndex++;
      apply(replayMatch(recording, replayIndex));
      replayTime = now;
      $("seek").value = replayIndex;
      if (replayIndex === recording.frames.length - 1) {
        playing = false;
        $("play").textContent = "Сначала";
      }
    }
  }
  if (recording) $("replay-label").textContent = `${replayIndex + 1} / ${recording.frames.length}`;
  if (model) {
    const selected = model.tanks.find((tank) => tank.slot === focusedSlot && tank.health > 0);
    const progress = Math.min(1, (now - received) / span);
    if (selected && $("camera-mode").value !== "all") {
      const target = cameraTarget(
        match,
        model,
        focusedSlot,
        progress,
        $("camera-mode").value === "auto",
      );
      if (!cameraCenter || reducedMotion.matches) cameraCenter = target;
      else {
        const alpha = 1 - Math.exp(-Math.max(0, now - lastCameraTime) / 180);
        cameraCenter = {
          x: cameraCenter.x + (target.x - cameraCenter.x) * alpha,
          y: cameraCenter.y + (target.y - cameraCenter.y) * alpha,
        };
      }
    } else cameraCenter = null;
    lastCameraTime = now;
    renderer.render(model, {
      time: now,
      progress,
      reducedMotion: reducedMotion.matches,
      camera: currentCamera(),
    });
  }
  requestAnimationFrame(draw);
}
clearSession();
requestAnimationFrame(draw);
