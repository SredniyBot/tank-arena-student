import {
  tankPalette as palette,
  createFrameModel,
  combatStatus,
  eliminationFeed,
} from "./frame-model.js";
import { createArenaRenderer } from "./renderer.js";
import { matchPollMillis, interpolationMillis, shouldDraw, replayMatchAt } from "./live-timing.js";
import { createButtonInput } from "./manual-input.js";
import { createTeacherRules } from "./teacher-rules.js";
import { PROTOCOL_VERSION, PROTOCOL_HEADER, INDEPENDENT_COMBAT } from "./protocol.js";

(() => {
  const $ = (id) => document.getElementById(id);
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const storageKey = "tank-arena-v2-player-token";
  const state = {
    token: "",
    me: null,
    info: null,
    matches: [],
    matchId: null,
    rulesArenaId: null,
    match: null,
    lobby: null,
    autoFollow: true,
    rosterFilter: "all",
    focusSlot: -1,
    pendingCommand: null,
    pendingFireRestore: null,
    commandAcknowledged: false,
    filter: "all",
    manual: false,
    controlMatchId: null,
    lastCommand: -1,
    adminKey: "",
    adminPlayers: [],
    busy: new Set(),
    dashboardBusy: false,
    dashboardAt: 0,
    adminBusy: false,
    adminAt: 0,
    online: false,
    connectionAttempted: false,
    serverBackoff: 0,
    replay: null,
    replayGeneration: 0,
    selectionGeneration: 0,
    keys: new Set(),
    aim: { x: 640, y: 640 },
    fireInput: createButtonInput(),
    shieldRequested: false,
    sessionGeneration: 0,
    playerAbort: new AbortController(),
    adminAbort: new AbortController(),
    effects: [],
    lastEventKey: "",
    eventUntil: 0,
    frameAt: 0,
    noticeAt: 0,
    previousFrame: null,
    previousLive: null,
    snapshotAt: 0,
    snapshotSpan: 100,
    dirty: true,
  };
  try {
    state.token = sessionStorage.getItem(storageKey) || "";
  } catch (_) {
    /* Storage can be disabled. */
  }
  const canvas = $("arena-canvas");
  const renderer = createArenaRenderer(canvas);
  const teacherRules = createTeacherRules({
    document,
    api,
    session: () => state.adminKey,
    onNotice: notify,
    onError: (error) => {
      if (error.status === 401 || error.status === 403) closeAdmin();
      showError(error);
    },
  });
  let cachedView = null,
    cachedViewKey = "";

  class ApiError extends Error {
    constructor(status, code, message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }

  function passwordRequestError(status, payload) {
    const messages = {
      ADMIN_KEY_NOT_PASSWORD:
        "Это ключ преподавателя. Для игры нужен пароль сервера, заданный в разделе «Преподавателю».",
      INVALID_SERVER_PASSWORD: "Неверный пароль сервера. Уточните текущий пароль у преподавателя.",
      PASSWORD_NOT_CONFIGURED:
        "Пароль сервера ещё не задан. Преподавателю нужно сохранить его в разделе «Преподавателю».",
      INVALID_PASSWORD:
        "Пароль должен содержать от 8 до 128 символов без переводов строк. Пробелы по краям удаляются.",
      SERVER_PASSWORD_CHANGED:
        "Пароль сервера изменился во время регистрации. Уточните его у преподавателя и повторите вход.",
      ADMIN_REQUIRED:
        "Проверьте ключ преподавателя из файла, указанного в консоли ServerMain, и войдите в админку снова.",
      INVALID_NAME:
        "Имя: до 32 символов. Используйте буквы, цифры, пробел, точку, дефис или подчёркивание.",
      INVALID_INPUT: "Проверьте введённые данные и повторите запрос.",
      CAPACITY_REACHED: "Сервер достиг лимита участников. Обратитесь к преподавателю.",
      RATE_LIMITED: "Сервер ограничил частоту запросов. Повторите действие позже.",
      STORAGE_UNAVAILABLE:
        "Сервер не смог сохранить данные. Преподавателю нужно проверить каталог данных и свободное место.",
      IO_UNAVAILABLE: "Сервер временно недоступен. Повторите действие позже.",
      INCOMPATIBLE_PROTOCOL: "Обновите страницу: сервер принимает только актуальный протокол.",
    };
    const code = payload?.code;
    if (typeof code === "string" && Object.hasOwn(messages, code))
      return new ApiError(status, code, messages[code]);
    return new ApiError(
      status,
      `HTTP_${status}`,
      "Сервер отклонил запрос. Проверьте данные или повторите действие позже.",
    );
  }

  function cancelled() {
    return new DOMException("Aborted", "AbortError");
  }
  function checkActive(signals) {
    if (signals.some((signal) => signal.aborted)) throw cancelled();
  }
  function waitForBackoff(milliseconds, signals) {
    checkActive(signals);
    return new Promise((resolve, reject) => {
      const cleanup = () => {
        clearTimeout(timer);
        for (const signal of signals) signal.removeEventListener("abort", abort);
      };
      const abort = () => {
        cleanup();
        reject(cancelled());
      };
      const timer = setTimeout(() => {
        cleanup();
        resolve();
      }, milliseconds);
      for (const signal of signals) signal.addEventListener("abort", abort, { once: true });
    });
  }
  function invalidatePlayerRequests() {
    state.sessionGeneration += 1;
    state.playerAbort.abort();
    state.playerAbort = new AbortController();
  }
  function invalidateAdminRequests() {
    state.adminAbort.abort();
    state.adminAbort = new AbortController();
  }

  async function api(path, options = {}) {
    const {
      method = "GET",
      body,
      admin = false,
      token = state.token,
      retries = 2,
      signal,
    } = options;
    const credential = admin ? state.adminKey : token;
    const signals = [
      ...new Set(
        [
          signal,
          credential ? (admin ? state.adminAbort.signal : state.playerAbort.signal) : null,
        ].filter(Boolean),
      ),
    ];
    for (let attempt = 0; ; attempt += 1) {
      checkActive(signals);
      const waiting = state.serverBackoff - Date.now();
      if (waiting > 30000)
        throw new ApiError(
          429,
          "RATE_LIMITED",
          "Сервер ограничил частоту запросов. Повторите действие позже.",
        );
      if (waiting > 0) await waitForBackoff(waiting, signals);
      checkActive(signals);
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 12000);
      const abort = () => controller.abort();
      for (const source of signals) source.addEventListener("abort", abort, { once: true });
      const headers = { Accept: "application/json", [PROTOCOL_HEADER]: PROTOCOL_VERSION };
      if (credential) headers.Authorization = `Bearer ${credential}`;
      if (body !== undefined) headers["Content-Type"] = "application/json";
      let response,
        payload = null;
      try {
        response = await fetch(`/api/v2${path}`, {
          method,
          headers,
          body: body === undefined ? undefined : JSON.stringify(body),
          cache: "no-store",
          credentials: "same-origin",
          redirect: "error",
          signal: controller.signal,
        });
        if (response.status !== 204) {
          const limit = path.includes("/replay?") ? 8 * 1024 * 1024 : 2 * 1024 * 1024;
          const reader = response.body?.getReader();
          let size = 0,
            text = "";
          const decoder = new TextDecoder();
          if (reader) {
            try {
              while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                size += value.byteLength;
                if (size > limit) {
                  await reader.cancel();
                  throw new ApiError(0, "RESPONSE_LIMIT", "Ответ сервера слишком большой.");
                }
                text += decoder.decode(value, { stream: true });
              }
              text += decoder.decode();
            } finally {
              reader.releaseLock();
            }
          }
          if (text) {
            try {
              payload = JSON.parse(text);
            } catch (_) {
              throw new ApiError(
                response.status,
                "INVALID_RESPONSE",
                "Сервер вернул ответ неизвестного формата.",
              );
            }
          }
        }
      } catch (error) {
        if (signals.some((source) => source.aborted) || error instanceof ApiError) throw error;
        setConnection(false);
        throw new ApiError(
          0,
          "CONNECTION",
          "Нет ответа от сервера. Проверьте подключение и адрес.",
        );
      } finally {
        clearTimeout(timeout);
        for (const source of signals) source.removeEventListener("abort", abort);
      }
      checkActive(signals);
      setConnection(true);
      if (response.ok) return payload;
      if (response.status === 429) {
        const value = response.headers.get("Retry-After");
        const seconds = Number(value);
        const delay =
          value && Number.isFinite(seconds)
            ? seconds * 1000
            : value && Number.isFinite(Date.parse(value))
              ? Math.max(0, Date.parse(value) - Date.now())
              : 1000 * 2 ** attempt;
        state.serverBackoff = Math.max(state.serverBackoff, Date.now() + Math.max(500, delay));
        if (attempt < retries && delay <= 30000) continue;
      }
      if (method === "POST" && (path === "/enroll" || path === "/admin/access"))
        throw passwordRequestError(response.status, payload);
      throw new ApiError(
        response.status,
        payload?.code || "REQUEST_FAILED",
        payload?.message || `Не удалось выполнить запрос (${response.status}).`,
      );
    }
  }

  function setConnection(online) {
    state.online = online;
    state.connectionAttempted = true;
    updateConnectionOverlay();
    updateEmptyState();
    $("connection-status").className = `server-status ${online ? "online" : "offline"}`;
    $("connection-status").lastElementChild.textContent = online
      ? "Сервер на связи"
      : "Нет соединения";
  }
  function updateConnectionOverlay() {
    $("arena-connection").hidden = state.online || !displayState();
    $("arena-connection").textContent = state.replay
      ? "Сервер недоступен. Загруженный повтор можно смотреть без сети."
      : "Связь потеряна. Показан последний кадр; подключение восстановится автоматически.";
  }
  function updateEmptyState() {
    const unavailable = state.connectionAttempted && !state.online;
    const saved = state.matches.some((match) => match.status === "FINISHED");
    $("empty-replay").hidden = !saved || unavailable;
    $("empty-replay").disabled = state.busy.has("replay");
    $("empty-replay").textContent = state.busy.has("replay")
      ? "Загружаем повтор…"
      : "Смотреть последний бой";
    $("empty-title").textContent = unavailable
      ? "Арена недоступна"
      : state.online
        ? saved
          ? "Следующий бой — за вами"
          : "Собираемся на старте"
        : "Подключаемся к арене";
    $("empty-detail").textContent = unavailable
      ? "Проверьте адрес и соединение. Мы повторяем подключение автоматически."
      : state.online
        ? saved
          ? "Посмотрите сохранённый раунд, пока собирается следующий состав."
          : "Наблюдать можно без входа. Для участия получите пароль сервера у преподавателя и зарегистрируйтесь."
        : "Загружаем матчи и очередь участников.";
  }
  function notify(message, error = false) {
    $("notice-text").textContent = message;
    $("notice").classList.toggle("error", error);
    $("notice").hidden = false;
    state.noticeAt = Date.now();
  }
  function showError(error) {
    const accessMessages = {
      ADMIN_KEY_NOT_PASSWORD:
        "Это ключ преподавателя. Для игры нужен пароль сервера, заданный в разделе «Преподавателю».",
      INVALID_SERVER_PASSWORD: "Неверный пароль сервера. Уточните текущий пароль у преподавателя.",
      PASSWORD_NOT_CONFIGURED:
        "Пароль сервера ещё не задан. Преподавателю нужно сохранить его в разделе «Преподавателю».",
      INVALID_PASSWORD:
        "Пароль должен содержать от 8 до 128 символов без переводов строк. Пробелы по краям удаляются.",
      SERVER_PASSWORD_CHANGED:
        "Пароль сервера изменился во время регистрации. Уточните его у преподавателя и повторите вход.",
    };
    if (accessMessages[error.code]) error.message = accessMessages[error.code];
    if (error.code === "INVALID_NAME")
      error.message =
        "Имя: до 32 символов. Используйте буквы, цифры, пробел, точку, дефис или подчёркивание.";
    if (error.name === "AbortError") return;
    notify(
      `${error.message}${error.code && error.code !== "CONNECTION" ? ` [${error.code}]` : ""}`,
      true,
    );
  }
  async function runAction(name, work) {
    if (
      state.busy.has(name) ||
      (["enroll", "session"].includes(name) &&
        ["enroll", "session"].some((key) => state.busy.has(key)))
    )
      return;
    state.busy.add(name);
    if (name === "access") {
      $("server-password").disabled = true;
      $("server-password-save").disabled = true;
      $("server-password-save").textContent = "Сохраняем…";
    }
    updateSession();
    try {
      await work();
    } catch (error) {
      showError(error);
    } finally {
      state.busy.delete(name);
      if (name === "access") {
        $("server-password").disabled = false;
        $("server-password-save").disabled = false;
        $("server-password-save").textContent = "Сохранить пароль";
      }
      updateSession();
    }
  }
  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }
  function button(text, className, action) {
    const node = element("button", className, text);
    node.type = "button";
    node.addEventListener("click", action);
    return node;
  }
  function isRunning(match) {
    return match?.status === "RUNNING";
  }
  function isFinished(match) {
    return Boolean(
      match &&
      ["FINISHED", "CANCELLED", "FAILED", "ABORTED", "COMPLETE", "COMPLETED"].includes(
        match.status,
      ),
    );
  }
  function isContinuous(match = state.match) {
    return Boolean(match?.live);
  }
  function canControl() {
    return (
      ownMatch() && isRunning(state.match) && (ownAlive() || (isContinuous() && ownSlot() >= 0))
    );
  }
  function livePlayer(match, slot) {
    return match?.live?.players?.find((player) => player.slot === slot);
  }
  function ownMatch() {
    return Boolean(state.me?.matchId && state.me.matchId === state.matchId);
  }
  function ownSlot() {
    return state.match?.players?.find((p) => p.id === state.me?.playerId)?.slot ?? -1;
  }
  function safePlayerName(player) {
    return player?.name || (player ? "Танк №" + (player.slot + 1) : "Арена");
  }
  function ownAlive() {
    return (
      ownMatch() &&
      (state.match?.state?.tanks?.find((tank) => tank.slot === ownSlot())?.health || 0) > 0
    );
  }
  function playerForSlot(match, slot) {
    return match?.players?.find((player) => player.slot === slot);
  }
  function mapTitle() {
    return "Общая арена";
  }
  function matchTitle(match) {
    return "Общий бой · " + (match?.players?.length || 0) + " участников";
  }
  function outcomeText(match) {
    if (match?.status === "FAILED") return "Результат не сохранён";
    if (match?.status === "SAVING") return "Сохраняем результат";
    if (match?.status !== "FINISHED") return "Раунд остановлен";
    const first = match?.placements?.filter((place) => place.rank === 1) || [];
    if (first.length === 1)
      return "Первое место: " + safePlayerName(playerForSlot(match, first[0].slot));
    if (first.length > 1) return "Первое место делят " + first.length + " участников";
    return "Раунд завершён";
  }
  function finishReason(reason) {
    const reasons = {
      ELIMINATION: "На поле осталось не больше одного живого танка.",
      LAST_SURVIVOR: "На поле остался один живой танк.",
      TIME_LIMIT: "Время вышло. Выжившие делят первое место.",
      FORFEIT: "Участник выбыл досрочно.",
      COMMAND_TIMEOUT: "Клиент пропустил команды.",
      SERVER_RESTART: "Сервер перезапущен. Результат не засчитан.",
      CANCELLED: "Раунд остановлен.",
      TOURNAMENT_CANCELLED: "Турнир остановлен преподавателем.",
      REVOKED: "Доступ отозван.",
      ADMIN_KICK: "Участник снят преподавателем.",
      COMPLETED: "Общий бой завершён.",
      NORMAL: "Общий бой завершён.",
    };
    return reasons[reason] || "Откройте повтор, чтобы разобрать выбор цели и движение.";
  }
  function saveToken(token) {
    invalidatePlayerRequests();
    state.token = token;
    try {
      if (token) sessionStorage.setItem(storageKey, token);
      else sessionStorage.removeItem(storageKey);
    } catch (_) {
      /* In-memory login still works. */
    }
  }
  function clearInput() {
    state.keys.clear();
    state.fireInput.reset();
    state.shieldRequested = false;
    document
      .querySelectorAll(".touch-controls .pressed")
      .forEach((control) => control.classList.remove("pressed"));
  }
  function setManual(value) {
    state.manual = Boolean(value && canControl() && !state.replay);
    state.controlMatchId = state.manual ? state.me.matchId : null;
    clearInput();
    updateSession();
    updateMatchChrome();
  }
  function updateSession() {
    updateEmptyState();
    const enrolled = Boolean(state.token && state.me);
    $("enroll-form").hidden = enrolled || !$("token-login-form").hidden;
    $("player-session").hidden = !enrolled;
    $("tank-name").hidden = !enrolled;
    $("tank-name").disabled = Boolean(state.me?.matchId || state.me?.queued);
    document.querySelector('label[for="tank-name"]').hidden = !enrolled;
    const busy = ["queue", "enroll", "session"].some((name) => state.busy.has(name));
    $("join").disabled =
      !enrolled ||
      state.me?.revoked ||
      state.me?.approved === false ||
      busy ||
      Boolean(state.me?.queued || state.me?.matchId);
    $("queue-status").hidden = !state.me?.queued;
    $("forfeit").hidden = !enrolled || !canControl();
    $("manual-toggle").disabled = !canControl() || Boolean(state.replay);
    $("manual-toggle").textContent = state.manual ? "Наблюдать" : "Управлять";
    $("manual-toggle").classList.toggle("active", state.manual && canControl());
    $("battlefield").classList.toggle("manual", state.manual && ownAlive() && !state.replay);
    $("touch-controls").hidden = !state.manual || !ownAlive() || Boolean(state.replay);
    $("locate-self").hidden = !displayMatch()?.players?.some(
      (player) => player.id === state.me?.playerId,
    );
    updateShieldControl();
    $("launch-title").textContent = enrolled ? state.me.name : "Присоединиться";
    if (enrolled) {
      if (
        document.activeElement !== $("tank-name") &&
        $("tank-name").dataset.player !== state.me.playerId
      ) {
        $("tank-name").value = state.me.name;
        $("tank-name").dataset.player = state.me.playerId;
      }
      $("session-name").textContent = state.me.name;
      $("player-avatar").textContent = Array.from(state.me.name || "Т")[0].toUpperCase();
      $("session-status").textContent = state.me.revoked
        ? "Доступ заблокирован"
        : state.me.approved === false
          ? "Ожидает допуска преподавателя"
          : state.me.queued
            ? "Ожидание входа"
            : state.me.matchId
              ? ownMatch() && !ownAlive()
                ? isContinuous()
                  ? "Возрождается"
                  : "Выбыл, наблюдает"
                : "На общем поле"
              : "Готов к бою";
      $("play-hint").textContent = state.me.revoked
        ? "Преподаватель может восстановить доступ. Токен сохранён в этой вкладке; после восстановления страница обновится сама."
        : state.me.approved === false
          ? "Регистрация завершена. Преподаватель увидит вас в списке и откроет допуск. Страница обновляется сама."
          : state.me.queued
            ? "Вход принят. Танк появится автоматически, когда сервер разрешит подключение."
            : state.me.matchId
              ? ownMatch() && !ownAlive()
                ? isContinuous()
                  ? "Танк скоро вернётся на поле. Очки сохранены; управление продолжится автоматически."
                  : "Вы выбыли. Остальные продолжают бой; вы наблюдаете до конца."
                : "Java-клиент играет сам. Для клавиатуры явно включите управление."
              : "Войдите на поле. После смерти танк возродится, очки сохранятся.";
    } else $("play-hint").textContent = "Войдите, чтобы играть. Наблюдать можно без регистрации.";
  }
  async function syncMe() {
    if (!state.token) return;
    const token = state.token;
    try {
      const me = await api("/me");
      if (state.token !== token) return;
      state.me = me;
      if (state.manual && state.controlMatchId && state.controlMatchId !== me.matchId)
        setManual(false);
      else if (state.manual && me.matchId) state.controlMatchId = me.matchId;
      if (me.revoked) {
        setManual(false);
      } else if (me.matchId && !state.replay && state.autoFollow && state.matchId !== me.matchId) {
        await selectMatch(me.matchId, false);
      }
      updateSession();
    } catch (error) {
      if (state.token !== token) return;
      if (error.status === 403 && error.code === "REVOKED" && state.me) {
        state.me = { ...state.me, revoked: true, queued: false, matchId: null };
        setManual(false);
        updateSession();
      } else if (error.status === 401 || error.status === 403) {
        saveToken("");
        state.me = null;
        setManual(false);
        notify("Токен больше не действует. Войдите снова или обратитесь к преподавателю.", true);
      } else throw error;
    }
  }

  async function refreshDashboard(force = false) {
    if (state.dashboardBusy) return;
    state.dashboardBusy = true;
    try {
      await syncMe();
      state.lobby = await api("/lobby");
      renderLobby();
      if (state.info?.reconEnabled) return;
      if (
        state.autoFollow &&
        !state.replay &&
        state.lobby.activeMatchId &&
        state.matchId !== state.lobby.activeMatchId
      )
        await selectMatch(state.lobby.activeMatchId, false);
      if (force || Date.now() - state.dashboardAt > 5000) {
        state.matches = await api("/matches");
        renderMatches();
        renderLeaderboard(await api("/live/leaderboard"), true);
        state.dashboardAt = Date.now();
      }
    } finally {
      state.dashboardBusy = false;
    }
  }
  async function dashboardLoop() {
    while (true) {
      try {
        if (!state.info) {
          state.info = await api("/info");
          if (state.info.version !== PROTOCOL_VERSION) {
            state.info = null;
            throw new ApiError(
              400,
              "INCOMPATIBLE_SERVER",
              `Версии клиента и сервера не совпадают. Нужен протокол Tank Arena ${PROTOCOL_VERSION}. Обновите страницу.`,
            );
          }
          $("server-version").textContent = state.info.version
            ? ` / API ${state.info.version}`
            : "";
          if (state.info.reconEnabled && location.hash !== "#teacher") {
            location.replace(INDEPENDENT_COMBAT ? "/combat.html" : "/recon.html");
            return;
          }
          if (state.info.reconEnabled) {
            document.querySelector(".conditions-panel").hidden = true;
            $("tournament-form").closest("section").hidden = true;
            $("tournament-status").previousElementSibling.textContent =
              INDEPENDENT_COMBAT ? "Непрерывный бой" : "Сеанс разведки";
            $("tournament-status").textContent =
              INDEPENDENT_COMBAT
                ? "Все участники играют на одном поле. После гибели танк возрождается, личный счёт сохраняется."
                : "Поле работает непрерывно. Для новых условий и обнуления командного счёта перезапустите сервер.";
            notify(
              INDEPENDENT_COMBAT
                ? "Поле боя открывается по ссылке вверху. Здесь доступны общий пароль, допуск и отключение игроков."
                : "Режим разведки: поле и составы команд открываются по ссылке «Разведка» вверху. Здесь доступны пароль и допуск игроков.",
            );
          }
        }
        if (!document.hidden) await refreshDashboard();
      } catch (error) {
        if (error.name !== "AbortError") {
          setConnection(false);
          if (Date.now() - state.noticeAt > 20000) showError(error);
        }
      }
      await sleep(Math.max(document.hidden ? 6000 : 1200, state.serverBackoff - Date.now()));
    }
  }

  async function selectMatch(id, stopControl = true) {
    if (stopControl && id !== state.me?.matchId) setManual(false);
    state.replayGeneration += 1;
    if (state.busy.has("replay")) $("notice").hidden = true;
    if (state.replay) exitReplay();
    if (stopControl) state.autoFollow = false;
    const generation = ++state.selectionGeneration;
    state.matchId = id;
    state.focusSlot = -1;
    state.lastCommand = -1;
    state.pendingCommand = null;
    state.commandAcknowledged = false;
    clearEffects();
    const match = await api(`/matches/${encodeURIComponent(id)}`);
    if (generation !== state.selectionGeneration) return;
    applyMatch(match);
    renderMatches();
  }
  function syncRulesArena(match) {
    if (!match.live) return;
    if (state.rulesArenaId && state.rulesArenaId !== match.id && state.adminKey) {
      closeAdmin();
      notify(
        "Арена перезапущена. Войдите в раздел преподавателя снова: условия и черновик сброшены.",
      );
    }
    state.rulesArenaId = match.id;
  }
  function applyMatch(match) {
    syncRulesArena(match);
    const previous = state.match;
    if (previous?.id === match.id && previous.state?.tick !== match.state?.tick) {
      state.previousFrame = previous.state;
      state.previousLive = previous.live || null;
      state.snapshotSpan = interpolationMillis(performance.now() - state.snapshotAt);
      state.snapshotAt = performance.now();
    } else if (previous?.id !== match.id) {
      state.previousFrame = null;
      state.previousLive = null;
      state.snapshotAt = performance.now();
    }
    state.match = match;
    if (
      previous?.id === match.id &&
      JSON.stringify(previous.rules) !== JSON.stringify(match.rules) &&
      !state.replay &&
      $("teacher-panel").hidden
    ) {
      notify(
        "Преподаватель изменил условия боя. Новые значения доступны над полем в разделе «Условия боя» и в view.rules() вашего бота.",
      );
    }
    if (previous?.id !== match.id) state.focusSlot = -1;
    if ((!isRunning(match) || !canControl()) && state.controlMatchId === match.id) setManual(false);
    else if (!ownAlive()) clearInput();
    if (!state.replay) {
      if (previous?.id !== match.id) clearEffects();
      captureEffects(match.state);
      updateMatchChrome();
    }
    updateSession();
  }
  async function matchLoop() {
    while (true) {
      const started = performance.now();
      const id = state.matchId,
        generation = state.selectionGeneration;
      try {
        if (
          id &&
          !state.replay &&
          (!document.hidden || state.manual) &&
          (isRunning(state.match) || state.match?.status === "SAVING" || state.match?.id !== id)
        ) {
          const match = await api("/matches/" + encodeURIComponent(id));
          if (generation === state.selectionGeneration && id === state.matchId && !state.replay) {
            applyMatch(match);
            if (state.manual && state.controlMatchId === id && ownAlive() && isRunning(match)) {
              const observation = await api("/matches/" + encodeURIComponent(id) + "/observation");
              if (generation !== state.selectionGeneration || id !== state.matchId || !state.manual)
                continue;
              const tick = observation.state.tick;
              if (state.pendingCommand?.tick !== tick) {
                state.pendingFireRestore = state.fireInput.restorePoint();
                state.pendingCommand = { tick, control: readControl() };
                state.commandAcknowledged = false;
              }
              if (!state.commandAcknowledged) {
                const command = state.pendingCommand,
                  restoreFire = state.pendingFireRestore;
                try {
                  await api("/matches/" + encodeURIComponent(id) + "/commands", {
                    method: "POST",
                    body: command,
                    retries: 0,
                  });
                  state.commandAcknowledged = true;
                } catch (error) {
                  if (generation !== state.selectionGeneration || id !== state.matchId) continue;
                  if (error.status !== 409 && error.status !== 429) throw error;
                  if (error.status === 409) state.commandAcknowledged = true;
                  // Only a definite rejection can retry a click. A lost response may
                  // already have fired; replaying that intent could produce a second shot.
                  if (error.status === 409 && error.code === "STALE_TICK" && command.control.fire)
                    restoreFire();
                  if (
                    error.status === 409 &&
                    /conflict|different|already/i.test(error.code + " " + error.message)
                  ) {
                    setManual(false);
                    notify(
                      "Другой клиент уже отправил команду этого шага. Браузер перешёл в наблюдение.",
                    );
                  }
                }
              }
            }
          }
        }
      } catch (error) {
        if (generation === state.selectionGeneration && id === state.matchId) {
          if (error.status === 404) {
            state.matchId = null;
            state.match = null;
            setManual(false);
          }
          if (Date.now() - state.noticeAt > 15000) showError(error);
        }
      }
      const interval = matchPollMillis(state.info, state.manual && ownAlive());
      await sleep(
        Math.max(20, interval - (performance.now() - started), state.serverBackoff - Date.now()),
      );
    }
  }
  function readControl() {
    const forward = state.keys.has("KeyW") || state.keys.has("ArrowUp");
    const back = state.keys.has("KeyS") || state.keys.has("ArrowDown");
    const right = state.keys.has("KeyD") || state.keys.has("ArrowRight");
    const left = state.keys.has("KeyA") || state.keys.has("ArrowLeft");
    const shield = state.shieldRequested;
    state.shieldRequested = false;
    return {
      throttle: Number(forward) - Number(back),
      turn: Number(right) - Number(left),
      aim: { ...state.aim },
      fire: state.fireInput.consume(),
      shield,
    };
  }
  function displayMatch() {
    return state.replay
      ? replayMatchAt(state.replay.match, state.replay.ruleChanges, displayState()?.tick ?? 0)
      : state.match;
  }
  function displayState() {
    return state.replay
      ? state.replay.frames[Math.floor(state.replay.position)]
      : state.match?.state;
  }
  function updateMatchChrome() {
    state.dirty = true;
    const match = displayMatch(),
      frame = displayState();
    $("arena-empty").hidden = Boolean(frame);
    updateConnectionOverlay();
    updateEmptyState();
    $("arena-title").textContent = "Общая арена";
    const continuous = isContinuous(match);
    $("match-kind").textContent = match
      ? state.replay
        ? "Повтор"
        : continuous
          ? "Постоянное поле"
          : match.ranked
            ? "Турнир"
            : "Свободный бой"
      : "Ожидание боя";
    $("live-indicator").classList.toggle("live", Boolean(isRunning(match) && !state.replay));
    $("tick-label").textContent = frame ? "Шаг " + frame.tick : "Шаг —";
    const tickRate = match?.rules?.tickRate || state.info?.tickRate || 20;
    $("battle-conditions").hidden = !match?.rules;
    if (match?.rules) {
      const rules = match.rules;
      const fmt = (value) =>
        new Intl.NumberFormat("ru", { maximumFractionDigits: 2 }).format(value);
      $("battle-conditions-brief").textContent =
        `управление ${fmt(tickRate / (state.info?.controlPeriodTicks || 1))} решений/с · выстрел раз в ${fmt(rules.reloadTicks / tickRate)} с`;
      $("battle-conditions-values").textContent =
        `Танк: ${fmt(rules.moveSpeed)} ед./с; корпус: ${fmt(rules.turnSpeed)} рад/с; башня: ${fmt(rules.turretSpeed)} рад/с. ` +
        `Снаряд: ${fmt(rules.bulletSpeed)} ед./с. Щит: ${fmt(rules.shieldDurationTicks / tickRate)} с, между включениями ${fmt(rules.shieldCooldownTicks / tickRate)} с. Время игровое.`;
    }
    const seconds = frame
      ? continuous
        ? Math.floor(frame.tick / tickRate)
        : Math.max(0, Math.ceil((match.rules.maxTicks - frame.tick) / tickRate))
      : null;
    $("match-clock").title = continuous ? "Прошло игрового времени" : "Осталось игрового времени";
    $("match-clock").textContent =
      seconds === null
        ? "—:—"
        : String(Math.floor(seconds / 60)).padStart(2, "0") +
          ":" +
          String(seconds % 60).padStart(2, "0");
    const visibleTanks = (frame?.tanks || []).filter(
      (tank) => !continuous || match.players.some((player) => player.slot === tank.slot),
    );
    const alive = visibleTanks.filter((tank) => tank.health > 0).length;
    $("alive-count").textContent = frame
      ? "На ходу " + alive + " / " + visibleTanks.length
      : "Живых —";
    $("zone-label").textContent = continuous
      ? "Укрытия блокируют снаряды"
      : frame
        ? "Зона: радиус " + Math.round(frame.safeRadius)
        : "Безопасная зона";
    $("delivery-help").hidden = !frame?.delivery;
    const physicalMillis = 1000 / (state.info?.maxStepRate || tickRate);
    $("timing-status").textContent = state.replay
      ? "Повтор: игровое время · можно остановить и разобрать кадр"
      : `Физика до ${Math.round(1000 / physicalMillis)} шаг/с · решения каждые ${Math.round((state.info?.controlPeriodTicks || 1) * physicalMillis)} мс · сглаживание изображения ${Math.round(state.snapshotSpan || 0)} мс`;
    const self = match?.players?.find((player) => player.id === state.me?.playerId);
    const tank = frame?.tanks?.find((item) => item.slot === self?.slot);
    $("self-label").textContent = tank
      ? self.name +
        (tank.health > 0
          ? " · " + tank.health + " HP"
          : continuous
            ? " · возрождение"
            : " · выбыли")
      : "Наблюдение";
    const revival =
      continuous && !state.replay && ownMatch() && tank && tank.health <= 0
        ? livePlayer(match, tank.slot)
        : null;
    $("respawn-note").hidden = !revival;
    if (revival) {
      $("respawn-title").textContent =
        "Возрождение " + gameSeconds(Math.max(0, revival.respawnAtTick - frame.tick) / tickRate);
      $("respawn-detail").textContent = state.manual
        ? "Управление продолжится автоматически"
        : "Очки сохранены";
    }
    const done = isFinished(match) && !state.replay;
    $("result-overlay").hidden = !done;
    $("result-replay").hidden = match?.status !== "FINISHED";
    if (done) {
      $("result-title").textContent = outcomeText(match);
      $("result-label").textContent = match.ranked ? "Зачётный раунд" : "Свободный раунд";
      $("result-detail").textContent = finishReason(match.finishedReason);
    }
    $("arena-mode").textContent = state.replay
      ? "Повтор"
      : tank && tank.health <= 0 && isRunning(match)
        ? continuous
          ? "Танк возрождается"
          : "Ваш танк выбыл · наблюдение"
        : state.manual && ownAlive()
          ? "Вы управляете: " + safePlayerName(playerForSlot(match, ownSlot()))
          : "Наблюдение";
    $("arena-mode").classList.toggle(
      "playing",
      Boolean(state.manual && ownAlive() && !state.replay),
    );
    $("replay-controls").hidden = !state.replay;
    $("arena-footer").hidden = Boolean(state.replay);
    renderRoster(match, frame);
    renderResults(match);
    renderCombatStatus(match, frame);
    renderKillFeed(match, frame);
    updateShieldControl();
  }

  const gameSeconds = (value) => (Math.ceil(value * 10) / 10).toFixed(1).replace(".", ",") + " с";
  function updateShieldControl() {
    const tank = state.match?.state?.tanks?.find((item) => item.slot === ownSlot());
    const ready = tank ? combatStatus(tank, state.match.rules) : null;
    const control = $("shield-control");
    control.disabled = !state.manual || !ownAlive() || Boolean(state.replay) || !ready?.shieldReady;
    control.textContent = ready?.shieldActive
      ? "Щит активен"
      : ready && !ready.shieldReady && ready.alive
        ? "Щит " + gameSeconds(ready.shieldCooldownSeconds)
        : "Щит · E";
    control.classList.toggle("active", Boolean(ready?.shieldActive));
    control.title = ready?.shieldActive
      ? "Во время щита стрелять нельзя"
      : "Включить щит: E. Время указано в игровых секундах";
  }
  function requestShield() {
    if (!$("shield-control").disabled && !$("help-dialog").open) {
      state.shieldRequested = true;
      canvas.focus({ preventScroll: true });
    }
  }
  function renderCombatStatus(match, frame) {
    const own = match?.players?.find((player) => player.id === state.me?.playerId)?.slot ?? -1;
    const slots = [...new Set([own, state.focusSlot].filter((slot) => slot >= 0))];
    const tanks = slots
      .map((slot) => frame?.tanks?.find((tank) => tank.slot === slot))
      .filter(Boolean);
    $("combat-status").hidden = !tanks.length;
    $("arena-panel").classList.toggle("has-combat", tanks.length > 0);
    const root = $("combat-status-list");
    const key = JSON.stringify([
      match?.id,
      tanks.map((tank) => [
        tank.slot,
        tank.health,
        tank.cooldownTicks,
        tank.shieldTicks,
        tank.shieldCooldownTicks,
        playerForSlot(match, tank.slot)?.name,
      ]),
      own,
    ]);
    if (root.dataset.key === key) return;
    root.dataset.key = key;
    root.replaceChildren();
    for (const tank of tanks) {
      const status = combatStatus(tank, match.rules),
        card = element("div", "combat-tank" + (!status.alive ? " eliminated" : ""));
      const identity = element("div", "combat-identity");
      identity.append(
        slotBadge(tank.slot),
        element(
          "strong",
          "",
          safePlayerName(playerForSlot(match, tank.slot)) + (tank.slot === own ? " · вы" : ""),
        ),
      );
      const weapon = status.alive
        ? status.shieldActive
          ? "Заблокирована щитом"
          : status.fireReady
            ? "Готова"
            : gameSeconds(status.reloadSeconds)
        : isContinuous(match)
          ? "Возрождение"
          : "Танк выбыл";
      const shield = status.alive
        ? status.shieldActive
          ? "Активен " + gameSeconds(status.shieldSeconds)
          : status.shieldReady
            ? "Готов"
            : gameSeconds(status.shieldCooldownSeconds)
        : "—";
      card.append(identity);
      for (const [title, text, progress, style] of [
        ["Пушка", weapon, status.reloadProgress, "weapon"],
        ["Щит", shield, status.shieldProgress, status.shieldActive ? "shield active" : "shield"],
      ]) {
        const block = element("div", "combat-meter " + style),
          label = element("div");
        label.append(element("span", "", title), element("strong", "", text));
        const meter = element("progress");
        meter.max = 1;
        meter.value = status.alive ? progress : 0;
        meter.setAttribute("aria-label", title + ": " + text);
        block.append(label, meter);
        card.append(block);
      }
      root.append(card);
    }
  }
  function renderKillFeed(match, frame) {
    const rows = eliminationFeed(match, frame, state.focusSlot),
      root = $("kill-feed");
    const key = JSON.stringify([match?.id, Boolean(frame), rows]);
    $("kill-feed-count").textContent = isContinuous(match)
      ? rows.length
      : new Set(
          (frame?.eliminations || [])
            .filter((row) => row.tick <= frame.tick)
            .map((row) => row.slot),
        ).size;
    if (root.dataset.key === key) return;
    const scroll = root.scrollTop;
    root.dataset.key = key;
    root.replaceChildren();
    if (!rows.length)
      root.append(
        element(
          "p",
          "empty-note",
          frame ? "Пока все в строю." : "Здесь появятся попадания с уничтожением и другие выбытия.",
        ),
      );
    for (const row of rows) {
      const entry = element("div", "kill-row" + (row.selected ? " selected" : ""));
      const time = element(
        "span",
        "kill-time",
        gameSeconds(row.tick / (match?.rules?.tickRate || 20)),
      );
      time.title = "Игровое время · шаг " + row.tick;
      const detail = element("span", "kill-detail");
      if (row.actorName)
        detail.append(element("strong", "", row.actorName), element("span", "kill-arrow", " → "));
      detail.append(element("strong", "", row.name));
      entry.append(
        time,
        detail,
        element("span", "kill-reason", row.actorName ? "Уничтожение" : row.reason),
      );
      root.append(entry);
    }
    root.scrollTop = scroll;
  }

  function slotBadge(slot) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 28 28");
    svg.setAttribute("class", "slot-badge");
    svg.setAttribute("aria-hidden", "true");
    const body = document.createElementNS(svg.namespaceURI, "path");
    body.setAttribute("d", "M4 8h4v17H4zM20 8h4v17h-4z");
    body.setAttribute("fill", "#345269");
    const hull = document.createElementNS(svg.namespaceURI, "rect");
    for (const [key, value] of Object.entries({
      x: 8,
      y: 8,
      width: 12,
      height: 17,
      rx: 3,
      fill: palette[slot] || palette[0],
    }))
      hull.setAttribute(key, value);
    const turret = document.createElementNS(svg.namespaceURI, "circle");
    for (const [key, value] of Object.entries({
      cx: 14,
      cy: 15,
      r: 4,
      fill: "#ffffff50",
      stroke: "#24445b",
      "stroke-width": 1,
    }))
      turret.setAttribute(key, value);
    const barrel = document.createElementNS(svg.namespaceURI, "path");
    barrel.setAttribute("d", "M14 14V2");
    barrel.setAttribute("stroke", "#294b61");
    barrel.setAttribute("stroke-width", "3");
    svg.append(body, hull, turret, barrel);
    return svg;
  }
  function renderRoster(match, frame) {
    const root = $("roster-list");
    // Rebuild only when values displayed in the roster change, preserving scroll and focus.
    // Rolling replay offsets and combat events do not change this list. Including the whole
    // LiveView rebuilt every button on every server tick, even for a stationary roster.
    const rosterValues = frame?.tanks?.map((tank) => {
      const score = livePlayer(match, tank.slot);
      const remaining =
        tank.health <= 0 ? Math.max(0, (score?.respawnAtTick || frame.tick) - frame.tick) : 0;
      return [
        tank.slot,
        tank.health,
        tank.shieldTicks > 0,
        score?.points,
        score?.deaths,
        remaining,
      ];
    });
    const key = JSON.stringify([
      match?.id,
      Boolean(state.replay),
      state.me?.playerId,
      state.rosterFilter,
      state.focusSlot,
      rosterValues,
      frame?.eliminations,
      match?.status,
      match?.placements,
      match?.players,
    ]);
    if (root.dataset.key === key) return;
    const focusedSlot = root.contains(document.activeElement)
      ? document.activeElement.dataset.slot
      : null;
    const scrollTop = root.scrollTop;
    root.dataset.key = key;
    root.replaceChildren();
    const continuous = isContinuous(match),
      live = continuous && !state.replay;
    $("score-columns").hidden = !live;
    $("roster-count").textContent = frame
      ? continuous
        ? match.players.length
        : frame.tanks.length
      : "—";
    if (!frame) {
      root.append(element("p", "empty-note", "Пока никто не подключился."));
      return;
    }
    const ordered = frame.tanks
      .filter((tank) => !continuous || playerForSlot(match, tank.slot))
      .sort((a, b) =>
        live
          ? (livePlayer(match, b.slot)?.points || 0) - (livePlayer(match, a.slot)?.points || 0) ||
            a.slot - b.slot
          : a.slot - b.slot,
      );
    for (const tank of ordered) {
      const dead = tank.health <= 0;
      if ((state.rosterFilter === "alive" && dead) || (state.rosterFilter === "dead" && !dead))
        continue;
      const player = playerForSlot(match, tank.slot),
        mine = player?.id === state.me?.playerId;
      const row = button("", "roster-row" + (dead ? " dead" : "") + (mine ? " mine" : ""), () => {
        state.focusSlot = state.focusSlot === tank.slot ? -1 : tank.slot;
        updateMatchChrome();
      });
      row.setAttribute("aria-pressed", String(state.focusSlot === tank.slot));
      row.dataset.slot = tank.slot;
      row.title = safePlayerName(player);
      const text = element("span", "roster-text");
      text.append(element("strong", "", safePlayerName(player) + (mine ? " (вы)" : "")));
      const elimination = frame.eliminations?.find((e) => e.slot === tank.slot);
      const reasons = {
        DESTROYED: "уничтожен",
        ZONE: "зона",
        FORFEIT: "покинул раунд",
        COMMAND_TIMEOUT: "нет команд",
        REVOKED: "заблокирован",
        ADMIN_KICK: "снят преподавателем",
      };
      const place = match.placements?.find((p) => p.slot === tank.slot);
      const score = livePlayer(match, tank.slot);
      text.append(
        element(
          "small",
          "",
          live
            ? dead
              ? "Возрождение " +
                gameSeconds(
                  Math.max(0, (score?.respawnAtTick || frame.tick) - frame.tick) /
                    match.rules.tickRate,
                )
              : tank.health + " HP" + (tank.shieldTicks > 0 ? " · щит" : "")
            : dead
              ? (reasons[elimination?.reason] || "выбыл") + " · шаг " + (elimination?.tick ?? "—")
              : player?.bot
                ? "учебный бот"
                : "участник",
        ),
      );
      row.append(slotBadge(tank.slot), text);
      if (live) {
        const points = element("span", "roster-score", score?.points || 0),
          deaths = element("span", "roster-deaths", score?.deaths || 0);
        points.title = "Очки";
        deaths.title = "Смерти";
        row.append(points, deaths);
      } else
        row.append(
          element(
            "span",
            "roster-hp",
            dead ? (place ? place.rank + " м." : "выбыл") : tank.health + " HP",
          ),
        );
      root.append(row);
    }
    if (!root.childElementCount)
      root.append(element("p", "empty-note", "В этой группе пока никого."));
    root.scrollTop = scrollTop;
    if (focusedSlot !== null)
      root.querySelector(`[data-slot="${focusedSlot}"]`)?.focus({ preventScroll: true });
  }
  function renderResults(match) {
    const visible = match?.status === "FINISHED" && !state.replay;
    $("results-panel").hidden = !visible;
    if (!visible) return;
    const root = $("results-table");
    const resultKey = match.id + ":" + state.me?.playerId;
    if (root.dataset.match === resultKey) return;
    root.dataset.match = resultKey;
    root.replaceChildren();
    const table = element("table"),
      head = element("thead"),
      header = element("tr"),
      body = element("tbody");
    for (const title of ["Место", "Танк / участник", "Очки", "Выбытие"]) {
      const th = element("th", "", title);
      th.scope = "col";
      header.append(th);
    }
    head.append(header);
    for (const place of [...(match.placements || [])].sort(
      (a, b) => a.rank - b.rank || a.slot - b.slot,
    )) {
      const player = playerForSlot(match, place.slot),
        row = element("tr", player?.id === state.me?.playerId ? "mine" : "");
      const name = element("td");
      name.append(slotBadge(place.slot), element("span", "", safePlayerName(player)));
      row.append(
        element("td", "rank", place.rank),
        name,
        element("td", "rank-score", place.points),
        element("td", "", place.eliminatedTick < 0 ? "Выжил" : "Шаг " + place.eliminatedTick),
      );
      body.append(row);
    }
    table.append(head, body);
    root.append(table);
  }
  function renderLobby() {
    const lobby = state.lobby;
    if (!lobby) return;
    $("lobby-summary").textContent =
      "Постоянное поле · до " + lobby.capacity + " участников. Вход без ожидания нового раунда.";
    $("lobby-players").textContent = lobby.waiting.map(safePlayerName).join(", ");
  }

  function renderMatches() {
    updateEmptyState();
    const list = $("matches-list");
    list.replaceChildren();
    const all = Array.isArray(state.matches) ? state.matches : [];
    $("live-count").textContent = all.filter(isRunning).length;
    const filtered = all.filter(
      (match) =>
        state.filter === "all" || (state.filter === "live" ? isRunning(match) : isFinished(match)),
    );
    if (!filtered.length) {
      const message =
        state.filter === "live"
          ? "Сейчас боя нет. Следующий раунд появится здесь автоматически."
          : state.filter === "finished"
            ? "Завершённых раундов пока нет. После первого боя здесь появится повтор."
            : "Общих боёв пока нет. Войдите в очередь следующего раунда.";
      list.append(element("p", "empty-list", message));
      return;
    }
    for (const match of filtered.slice(0, 30)) {
      const live = isRunning(match),
        row = element("article", "match-row" + (state.matchId === match.id ? " selected" : ""));
      row.append(
        element(
          "span",
          "match-state" + (live ? " is-live" : ""),
          live
            ? "В эфире"
            : match.status === "FINISHED"
              ? "Завершён"
              : match.status === "SAVING"
                ? "Сохранение"
                : "Остановлен",
        ),
      );
      const detail = element("div");
      detail.append(
        element("div", "match-players", matchTitle(match)),
        element(
          "div",
          "match-meta",
          match.id + " · " + (match.ranked ? "Турнир" : "Свободный бой"),
        ),
      );
      const actions = element("div", "match-actions");
      actions.append(
        button(live ? "Смотреть" : "Открыть", "text-button", () =>
          runAction("select", () => selectMatch(match.id)),
        ),
      );
      if (
        match.status === "FINISHED" ||
        isContinuous(match) ||
        match.id === state.lobby?.activeMatchId
      )
        actions.append(
          button("Повтор", "text-button", () => runAction("replay", () => loadReplay(match.id))),
        );
      row.append(detail, actions);
      list.append(row);
    }
  }
  function renderLeaderboard(rows, live = false) {
    $("ranking-title").textContent = live ? "Общий счёт" : "Турнирная таблица";
    $("ranking-title").nextElementSibling.textContent = live
      ? "Очки сохраняются после смерти и выхода."
      : "Сумма очков официальных раундов.";
    document.querySelector(".ranking-note").textContent = live
      ? state.match?.state?.delivery
        ? "Доставленный груз — одно очко. Уничтожения не дают очков доставки. Равные очки — общее место."
        : "За уничтожение — одно очко. Смерть не снимает очки. При равенстве очков место общее."
      : "При равных очках место общее.";
    const root = $("leaderboard");
    root.replaceChildren();
    if (!Array.isArray(rows) || !rows.length) {
      root.append(element("p", "empty-list", "Результаты появятся после зачётного раунда."));
      return;
    }
    const table = element("table"),
      head = element("thead"),
      header = element("tr"),
      body = element("tbody");
    for (const title of live
      ? ["Место", "Участник", "Уничтожения", "Смерти", "Очки"]
      : ["Место", "Участник", "Раунды", "Первые", "Очки"]) {
      const th = element("th", "", title);
      th.scope = "col";
      header.append(th);
    }
    head.append(header);
    for (const row of rows) {
      const tr = element("tr", row.playerId === state.me?.playerId ? "mine" : "");
      tr.append(
        element("td", "rank", row.rank),
        element("td", "rank-name", row.name),
        element("td", "", live ? row.kills : row.rounds),
        element("td", "", live ? row.deaths : row.firstPlaces),
        element("td", "rank-score", row.points),
      );
      body.append(tr);
    }
    table.append(head, body);
    root.append(table);
  }
  async function loadReplay(id) {
    if (ownMatch() && isRunning(state.match) && state.manual) {
      notify("Сначала завершите свой бой или переключитесь в наблюдение, чтобы открыть повтор.");
      return;
    }
    const generation = ++state.replayGeneration;
    notify("Загружаем повтор…");
    let frames = [];
    let match = null;
    let from = 0;
    let total = 0;
    let start = 0;
    let roster = null;
    const ruleChanges = new Map();
    do {
      const page = await api(`/matches/${encodeURIComponent(id)}/replay?from=${from}&limit=32`);
      if (generation !== state.replayGeneration) return;
      const live = isContinuous(page.match),
        rosterKey = JSON.stringify(page.match?.players);
      if (!frames.length) {
        start = page.from;
        total = Number(page.total);
        match = page.match;
        roster = rosterKey;
      } else if (live && (page.from !== from || rosterKey !== roster))
        throw new ApiError(
          409,
          "REPLAY_EXPIRED",
          "Состав поля или окно записи изменились. Откройте повтор ещё раз.",
        );
      if (
        !Number.isSafeInteger(total) ||
        !Number.isSafeInteger(start) ||
        start < 0 ||
        total < start ||
        total - start > 30000 ||
        (total - start) * Math.max(1, match?.players?.length || 1) > 80000
      )
        throw new ApiError(
          0,
          "REPLAY_LIMIT",
          "Повтор слишком большой для просмотра в этой вкладке.",
        );
      if (
        !Array.isArray(page.frames) ||
        page.frames.length > 32 ||
        (!live && page.from !== from) ||
        !Number.isSafeInteger(page.from)
      )
        throw new ApiError(0, "REPLAY_FORMAT", "Неверная страница повтора.");
      if (!frames.length) from = start;
      frames.push(...page.frames.slice(0, Math.max(0, total - from)));
      for (const change of page.ruleChanges || [])
        if (change.fromTick < total) ruleChanges.set(change.revision, change);
      if (!page.frames.length && from < total)
        throw new ApiError(0, "REPLAY_INCOMPLETE", "Сервер вернул неполный повтор.");
      from += page.frames.length;
      $("notice-text").textContent =
        `Загружаем повтор: ${Math.min(from, total) - start} из ${total - start} шагов…`;
    } while (from < total);
    if (!frames.length)
      throw new ApiError(0, "REPLAY_EMPTY", "В этом матче пока нет записанных шагов.");
    setManual(false);
    state.autoFollow = false;
    state.matchId = id;
    state.match = match;
    state.selectionGeneration += 1;
    state.replay = {
      match,
      frames,
      ruleChanges: [...ruleChanges.values()],
      position: 0,
      playing: false,
      speed: Number($("replay-speed").value),
      at: 0,
    };
    clearEffects();
    state.focusSlot = -1;
    $("replay-seek").max = frames.length - 1;
    $("notice").hidden = true;
    updateReplayControls();
    updateMatchChrome();
    updateSession();
    renderMatches();
    $("arena-title").scrollIntoView({
      block: "start",
      behavior: reducedMotion.matches ? "auto" : "smooth",
    });
  }
  function exitReplay() {
    state.replayGeneration += 1;
    state.replay = null;
    state.autoFollow = true;
    state.dashboardAt = 0;
    clearEffects();
    updateMatchChrome();
    updateSession();
  }
  function updateReplayControls() {
    const replay = state.replay;
    if (!replay) return;
    $("replay-seek").value = Math.floor(replay.position);
    $("replay-position").textContent =
      `${replay.frames[Math.floor(replay.position)].tick} / ${replay.frames.at(-1).tick}`;
    $("replay-toggle").textContent = replay.playing ? "Ⅱ" : "▶";
    $("replay-toggle").setAttribute(
      "aria-label",
      replay.playing ? "Приостановить повтор" : "Воспроизвести повтор",
    );
  }

  async function refreshAdmin() {
    if (!state.adminKey || state.adminBusy) return;
    state.adminBusy = true;
    const key = state.adminKey;
    try {
      const players = await api("/admin/players", { admin: true });
      if (state.adminKey !== key) return;
      state.adminPlayers = players;
      renderAdminPlayers();
      const access = await api("/admin/access", { admin: true });
      if (state.adminKey !== key) return;
      renderServerAccess(access);
      if (!state.info?.reconEnabled) {
        const conditions = await api("/admin/rules", { admin: true });
        if (state.adminKey !== key) return;
        teacherRules.update(conditions);
        const tournament = await api("/admin/tournament", { admin: true });
        if (state.adminKey !== key) return;
        renderTournament(tournament);
      }
      const metrics = await api("/admin/metrics", { admin: true });
      if (state.adminKey !== key) return;
      renderMetrics(metrics);
      $("admin-form").hidden = true;
      $("admin-workspace").hidden = false;
      state.adminAt = Date.now();
    } catch (error) {
      if (state.adminKey === key && (error.status === 401 || error.status === 403)) closeAdmin();
      throw error;
    } finally {
      state.adminBusy = false;
    }
  }
  function closeAdmin() {
    invalidateAdminRequests();
    teacherRules.clear();
    state.adminKey = "";
    state.adminPlayers = [];
    $("admin-key").value = "";
    $("player-picker").replaceChildren();
    $("admin-players-list").replaceChildren();
    $("metrics").replaceChildren();
    $("tournament-status").replaceChildren();
    $("admin-form").hidden = false;
    $("admin-workspace").hidden = true;
    $("server-password").value = "";
    $("join-password").value = "";
    $("server-access-status").textContent = "Проверяем настройку…";
  }
  function renderServerAccess(access) {
    $("server-access-status").textContent = access.passwordConfigured
      ? "Пароль задан. Студенты могут регистрироваться."
      : "Пароль не задан. Сохраните его, чтобы студенты могли зарегистрироваться.";
  }
  async function adminLoop() {
    while (true) {
      if (
        state.adminKey &&
        !$("teacher-panel").hidden &&
        !document.hidden &&
        Date.now() - state.adminAt > 3000
      ) {
        try {
          await refreshAdmin();
        } catch (error) {
          if (Date.now() - state.noticeAt > 15000) showError(error);
        }
      }
      await sleep(Math.max(1000, state.serverBackoff - Date.now()));
    }
  }
  function renderAdminPlayers() {
    const picker = $("player-picker");
    const list = $("admin-players-list");
    const search = $("admin-search").value.trim().toLocaleLowerCase("ru"),
      filter = $("admin-filter").value;
    const pickerRows = new Map(
      Array.from(picker.querySelectorAll("[data-player]"), (row) => [row.dataset.player, row]),
    );
    const listRows = new Map(
      Array.from(list.querySelectorAll("[data-player]"), (row) => [row.dataset.player, row]),
    );
    const shown = new Set(),
      eligible = new Set();
    let visible = 0;
    picker.querySelectorAll(".empty-note").forEach((node) => node.remove());
    list.querySelectorAll(".empty-note").forEach((node) => node.remove());
    if (!state.adminPlayers.length) {
      picker.append(element("p", "empty-note", "Участники появятся после регистрации."));
      list.append(element("p", "empty-note", "Задайте пароль сервера и сообщите его студентам."));
    }
    for (const player of state.adminPlayers) {
      const approved = player.approved !== false,
        shortId = player.id.slice(0, 8),
        pending = !approved && !player.revoked;
      if (!player.revoked && approved) {
        eligible.add(player.id);
        let label = pickerRows.get(player.id);
        if (!label) {
          label = element("label", "player-check");
          label.dataset.player = player.id;
          const input = element("input");
          input.type = "checkbox";
          input.value = player.id;
          label.append(input, element("span"));
          picker.append(label);
        }
        label.lastElementChild.textContent = `${player.name} · ${shortId}${player.online ? " · на связи" : " · нет связи"}`;
      }
      const matchesFilter =
        filter === "all" ||
        (filter === "pending" && pending) ||
        (filter === "online" && player.online && !player.revoked) ||
        (filter === "queued" && player.queued) ||
        (filter === "playing" && player.matchId) ||
        (filter === "revoked" && player.revoked);
      const matchesSearch =
        !search || (player.name + " " + player.id).toLocaleLowerCase("ru").includes(search);
      shown.add(player.id);
      if (matchesFilter && matchesSearch) visible += 1;
      let row = listRows.get(player.id);
      if (!row) {
        row = element("div", "admin-player");
        row.dataset.player = player.id;
        list.append(row);
      }
      row.hidden = !(matchesFilter && matchesSearch);
      const playingSlot =
        state.match?.id === player.matchId
          ? state.match.players.find((item) => item.id === player.id)?.slot
          : null;
      const eliminated =
        playingSlot != null &&
        state.match.state?.tanks?.some((tank) => tank.slot === playingSlot && tank.health <= 0);
      const signature = JSON.stringify([
        player.name,
        player.revoked,
        approved,
        player.online,
        player.queued,
        player.matchId,
        eliminated,
      ]);
      if (row.dataset.signature === signature) continue;
      const focusedAction = row.contains(document.activeElement)
        ? document.activeElement.dataset.action
        : null;
      row.dataset.signature = signature;
      row.replaceChildren();
      const identity = element("div", "admin-player-identity");
      identity.append(element("strong", "", player.name), element("small", "", shortId));
      const status = player.revoked
        ? "Заблокирован"
        : pending
          ? "Ждёт допуска"
          : eliminated
            ? isContinuous()
              ? "Возрождается"
              : "Выбыл, наблюдает"
            : player.matchId
              ? "В бою"
              : player.queued
                ? "В очереди"
                : "Допущен";
      const badges = element("div", "admin-player-status");
      badges.append(
        element(
          "span",
          "player-status" + (pending ? " pending" : player.revoked ? " blocked" : ""),
          status,
        ),
      );
      if (!player.revoked)
        badges.append(
          element(
            "span",
            "presence" + (player.online ? " online" : ""),
            player.online ? "На связи" : "Нет связи",
          ),
        );
      const actions = element("div", "admin-player-actions");
      function action(label, operation, style, confirmation, message) {
        const control = button(label, style, () =>
          runAction("player-" + player.id, async () => {
            if (confirmation && !window.confirm(confirmation)) return;
            control.disabled = true;
            try {
              await api(`/admin/players/${encodeURIComponent(player.id)}/${operation}`, {
                method: "POST",
                admin: true,
              });
              await refreshAdmin();
              await refreshDashboard(true);
              notify(message);
            } finally {
              if (control.isConnected) control.disabled = false;
            }
          }),
        );
        control.dataset.action = operation;
        actions.append(control);
        return control;
      }
      if (player.revoked)
        action(
          "Восстановить",
          "restore",
          "secondary-button",
          null,
          `Доступ «${player.name}» восстановлен. Участник может войти с прежним токеном.`,
        );
      else {
        if (pending)
          action("Принять", "approve", "primary-button", null, `«${player.name}» допущен к арене.`);
        else if (!player.queued && !player.matchId) {
          const queue = action(
            "Подключить",
            "queue",
            "secondary-button",
            null,
            `«${player.name}» подключается к полю. Его Java-клиент должен быть запущен; в браузере можно включить управление.`,
          );
          queue.disabled = !player.online;
          if (!player.online)
            queue.title =
              "Сначала участник должен подключиться в браузере или запустить Main в IDE";
        }
        if (player.queued || player.matchId)
          action(
            "Снять с игры",
            "kick",
            "danger-button",
            `Снять «${player.name}» (${shortId}) с игры? Участник покинет поле. Сможет подключиться снова; допуск и очки сохранятся.`,
            `«${player.name}» снят с игры. Допуск сохранён.`,
          );
        action(
          "Заблокировать",
          "revoke",
          "danger-button",
          `Заблокировать «${player.name}» (${shortId})? Его танк выбывает, вход на поле закрывается до восстановления доступа.`,
          `Доступ «${player.name}» заблокирован.`,
        );
      }
      row.append(identity, badges, actions);
      if (focusedAction)
        row.querySelector(`[data-action="${focusedAction}"]`)?.focus({ preventScroll: true });
    }
    for (const [id, row] of pickerRows) if (!eligible.has(id)) row.remove();
    for (const [id, row] of listRows) if (!shown.has(id)) row.remove();
    if (state.adminPlayers.length && !eligible.size)
      picker.append(element("p", "empty-note", "Сначала примите участников в списке ниже."));
    if (state.adminPlayers.length && !visible)
      list.append(element("p", "empty-note", "По этим условиям никого нет."));
    $("admin-player-count").textContent = `${visible} из ${state.adminPlayers.length}`;
  }
  function renderTournament(tournament) {
    const root = $("tournament-status");
    root.replaceChildren();
    $("cancel-tournament").hidden =
      !tournament || !["RUNNING", "ACTIVE", "PENDING"].includes(tournament.status);
    if (!tournament) {
      root.append(element("p", "empty-note", "Турнир ещё не запущен."));
      return;
    }
    const names = {
      RUNNING: "Турнир идёт",
      FINISHED: "Турнир завершён",
      COMPLETE: "Турнир завершён",
      INTERRUPTED: "Турнир прерван",
      CANCELLED: "Турнир остановлен",
      PENDING: "Ожидание",
      FAILED: "Ошибка сохранения",
    };
    const summary = element(
      "p",
      "tournament-summary",
      names[tournament.status] || tournament.status,
    );
    summary.append(
      element("strong", "", tournament.completed + " / " + tournament.total),
      element("span", "", "Общих раундов завершено. Ожидают: " + tournament.pending),
    );
    root.append(summary);
    const rounds = element("div", "fixture-list");
    for (const round of tournament.rounds || []) {
      const row = element("div", "tournament-round");
      const heading = element("div", "tournament-fixture");
      if (round.matchId)
        heading.append(
          button("Раунд " + round.number, "text-button", () =>
            runAction("select", () => selectMatch(round.matchId)),
          ),
        );
      else heading.append(element("strong", "", "Раунд " + round.number));
      heading.append(
        element(
          "span",
          "",
          {
            PENDING: "Ожидает",
            RUNNING: "На поле",
            FINISHED: "Завершён",
            COMPLETE: "Завершён",
            CANCELLED: "Отменён",
            FAILED: "Ошибка",
          }[round.status] || round.status,
        ),
      );
      const order = element("details", "spawn-order");
      order.append(element("summary", "", "Порядок стартов"));
      const roster = element("ol");
      for (const id of round.spawnOrder || [])
        roster.append(element("li", "", state.adminPlayers.find((p) => p.id === id)?.name || id));
      order.append(roster);
      row.append(heading, order);
      rounds.append(row);
    }
    root.append(rounds);
  }
  function renderMetrics(metrics) {
    const labels = {
      players: "Участники",
      activeMatches: "Активные матчи",
      queuedPlayers: "В очереди",
      storedMatches: "Сохранённые матчи",
      totalMatches: "Матчи всего",
      requests: "Запросы",
      rateLimited: "Отклонено по частоте",
      uptimeSeconds: "Время работы, с",
      maxActiveMatches: "Лимит активных матчей",
      connections: "Соединения",
      profile: "Профиль",
      tickRate: "Шагов в секунду",
      accounts: "Аккаунты",
      queued: "В очереди",
      archivedMatches: "Сохранённые матчи",
      simulationSteps: "Выполнено шагов физики",
      elapsedActiveMillis: "Активное время, мс",
      actualSimulationHz: "Фактическая физика, шаг/с",
      nominalSimulationHz: "Игровых шагов в секунде",
      acceptedDecisions: "Принято решений",
      lateCommands: "Опоздавшие команды",
      duplicateCommands: "Повторные отправки",
      schedulerResyncs: "Перезапуски расписания после задержки",
    };
    const root = $("metrics");
    root.replaceChildren();
    for (const [key, value] of Object.entries(metrics || {})) {
      root.append(
        element("dt", "", labels[key] || key),
        element(
          "dd",
          "",
          typeof value === "object"
            ? JSON.stringify(value)
            : typeof value === "number" && !Number.isInteger(value)
              ? value.toFixed(2)
              : value,
        ),
      );
    }
  }

  function clearEffects() {
    state.effects = [];
    state.lastEventKey = "";
    state.eventUntil = 0;
    $("arena-event").textContent = "";
  }
  function captureEffects(frame) {
    if (!frame) return;
    const key = `${displayMatch()?.id}:${frame.tick}`;
    if (key === state.lastEventKey) return;
    state.lastEventKey = key;
    for (const event of frame.events || []) {
      if (!event.position) continue;
      state.effects.push({
        ...event,
        born: performance.now(),
        tick: frame.tick,
      });
      if (["HIT", "DESTROYED", "SHIELD_BLOCK"].includes(event.type)) {
        const actor = safePlayerName(playerForSlot(displayMatch(), event.actor));
        const target = safePlayerName(playerForSlot(displayMatch(), event.target));
        $("arena-event").textContent =
          event.type === "DESTROYED"
            ? `${target} выбыл`
            : event.type === "SHIELD_BLOCK"
              ? `${target}: щит отбил снаряд`
              : `${actor}: попадание по ${target}`;
        state.eventUntil = performance.now() + 1100;
      }
    }
    if (state.effects.length > 60) state.effects.splice(0, state.effects.length - 60);
  }

  function drawArena(now) {
    state.dirty = false;
    const match = displayMatch(),
      replay = state.replay;
    const replayIndex = replay ? Math.floor(replay.position) : 0;
    const previous = replay ? null : state.previousFrame;
    const frame = displayState();
    const nextFrame =
      replay && !reducedMotion.matches
        ? replay.frames[Math.min(replayIndex + 1, replay.frames.length - 1)]
        : null;
    const mode =
      state.manual && ownMatch() && !replay && isRunning(match)
        ? "manual"
        : replay
          ? "replay"
          : "watch";
    const viewKey = JSON.stringify([
      match?.id,
      frame?.tick,
      previous?.tick,
      Boolean(nextFrame),
      state.me?.playerId,
      state.focusSlot,
      mode,
      state.aim,
    ]);
    if (viewKey !== cachedViewKey) {
      cachedViewKey = viewKey;
      cachedView = createFrameModel({
        match,
        frame,
        previousFrame: previous,
        previousLive: previous ? state.previousLive : null,
        nextFrame,
        selfId: state.me?.playerId,
        focusSlot: state.focusSlot,
        aim: state.aim,
        mode,
      });
    }
    const progress = replay
      ? replay.position - replayIndex
      : Math.min(1, Math.max(0, (now - state.snapshotAt) / state.snapshotSpan));
    renderer.render(cachedView, {
      time: now,
      progress,
      reducedMotion: reducedMotion.matches,
    });
  }
  function animationFrame(now) {
    const replay = state.replay;
    if (replay?.playing && !document.hidden) {
      const before = Math.floor(replay.position),
        delta = Math.min(100, now - (replay.at || now));
      replay.position = Math.min(
        replay.frames.length - 1,
        replay.position + (delta / 1000) * (replay.match.rules?.tickRate || 20) * replay.speed,
      );
      if (replay.position >= replay.frames.length - 1) replay.playing = false;
      if (Math.floor(replay.position) !== before || !replay.playing) {
        captureEffects(displayState());
        updateReplayControls();
        updateMatchChrome();
      }
    }
    if (replay) replay.at = now;
    if (state.eventUntil && now >= state.eventUntil) {
      $("arena-event").textContent = "";
      state.eventUntil = 0;
    }
    const fading = state.effects.length > 0;
    state.effects = state.effects.filter((effect) => now - effect.born < 750);
    const moving = fading || state.replay?.playing || now - state.snapshotAt < state.snapshotSpan;
    if (
      !document.hidden &&
      (state.dirty || moving) &&
      shouldDraw(now, state.frameAt, reducedMotion.matches)
    ) {
      drawArena(now);
      state.frameAt = now;
    }
    requestAnimationFrame(animationFrame);
  }

  async function copyText(value, success) {
    try {
      await navigator.clipboard.writeText(value);
      notify(success);
    } catch (_) {
      const field = element("textarea");
      field.value = value;
      field.className = "clipboard-fallback";
      document.body.append(field);
      field.select();
      let copied = false;
      try {
        copied = document.execCommand("copy");
      } catch (_) {
        /* Keep the user informed if unavailable. */
      }
      field.remove();
      if (copied) notify(success);
      else
        notify("Браузер запретил копирование. Откройте страницу через HTTPS или localhost.", true);
    }
  }
  function openHelp() {
    clearInput();
    const rules = displayMatch()?.rules;
    if (rules?.shieldDurationTicks)
      $("combat-rules").textContent =
        `После выстрела пушка перезаряжается ${gameSeconds(rules.reloadTicks / rules.tickRate)}. Щит блокирует снаряды ${gameSeconds(rules.shieldDurationTicks / rules.tickRate)}; следующая активация — через ${gameSeconds(rules.shieldCooldownTicks / rules.tickRate)} от предыдущей. Пока щит активен, стрелять нельзя. ${isContinuous(displayMatch()) ? "После возрождения танк получает краткую защиту." : "Зона наносит урон сквозь щит."} Все интервалы указаны в игровых секундах.`;
    if (!$("help-dialog").open) $("help-dialog").showModal();
  }
  function showTeacher(show) {
    $("teacher-panel").hidden = !show;
    $("play-layout").hidden = show;
    $("archive-panel").hidden = show;
    if (show) setManual(false);
    $("teacher-tab").classList.toggle("active", show);
    $("arena-tab").classList.toggle("active", !show);
    $("teacher-tab").setAttribute("aria-current", show ? "page" : "false");
    $("arena-tab").setAttribute("aria-current", show ? "false" : "page");
    if (show) {
      clearInput();
      $("teacher-title").scrollIntoView({ block: "start" });
    }
  }
  $("notice-close").addEventListener("click", () => {
    $("notice").hidden = true;
  });
  for (const id of ["help-open", "java-help", "footer-help"])
    $(id).addEventListener("click", openHelp);
  $("help-close").addEventListener("click", () => $("help-dialog").close());
  $("help-dialog").addEventListener("close", () => {
    if (state.manual && ownAlive()) canvas.focus({ preventScroll: true });
  });
  $("help-dialog").addEventListener("click", (event) => {
    if (event.target === $("help-dialog")) {
      const rect = event.target.getBoundingClientRect();
      if (
        event.clientX < rect.left ||
        event.clientX > rect.right ||
        event.clientY < rect.top ||
        event.clientY > rect.bottom
      )
        event.target.close();
    }
  });
  $("archive-open").addEventListener("click", () => {
    showTeacher(false);
    $("archive-panel").open = true;
    $("archive-panel").scrollIntoView({
      block: "start",
      behavior: reducedMotion.matches ? "auto" : "smooth",
    });
  });
  $("teacher-tab").addEventListener("click", () => showTeacher(true));
  $("arena-tab").addEventListener("click", () => showTeacher(false));
  $("teacher-close").addEventListener("click", () => showTeacher(false));
  $("enroll-form").addEventListener("submit", (event) => {
    event.preventDefault();
    runAction("enroll", async () => {
      const password = $("join-password").value;
      $("join-password").value = "";
      const enrolled = await api("/enroll", {
        method: "POST",
        token: "",
        body: { password, name: $("player-name").value.trim() },
      });
      saveToken(enrolled.token);
      state.me = {
        playerId: enrolled.playerId,
        name: enrolled.name,
        queued: false,
        revoked: false,
        approved: false,
        matchId: null,
      };
      notify(`Регистрация завершена, ${enrolled.name}. Дождитесь допуска преподавателя.`);
      await refreshDashboard(true);
    });
  });
  $("token-login-open").addEventListener("click", () => {
    $("token-login-form").hidden = false;
    updateSession();
    $("player-token").focus();
  });
  $("token-login-cancel").addEventListener("click", () => {
    $("token-login-form").hidden = true;
    $("player-token").value = "";
    invalidatePlayerRequests();
    updateSession();
  });
  $("player-token").addEventListener("change", async () => {
    const token = $("player-token").value.trim();
    if (!$("token-name").value.trim() && token) {
      try {
        const me = await api("/me", { token });
        if (token === $("player-token").value.trim() && !$("token-name").value.trim())
          $("token-name").value = me.name;
      } catch (_) {
        /* Submission reports authentication errors. */
      }
    }
  });
  $("token-login-form").addEventListener("submit", (event) => {
    event.preventDefault();
    runAction("session", async () => {
      const token = $("player-token").value.trim(),
        generation = state.sessionGeneration;
      const me = await api("/me", { token });
      if (generation !== state.sessionGeneration) return;
      if (me.revoked) throw new ApiError(403, "REVOKED", "Доступ участника отозван.");
      const name = $("token-name").value.trim();
      if (name !== me.name) await api("/me/name", { method: "POST", token, body: { name } });
      saveToken(token);
      state.me = { ...me, name };
      $("player-token").value = "";
      $("token-login-form").hidden = true;
      await syncMe();
      notify(`Вы вошли как ${me.name}.`);
    });
  });
  $("logout").addEventListener("click", () => {
    saveToken("");
    state.me = null;
    setManual(false);
    notify("Вы вышли. Матч можно продолжить из Java-клиента с действующим токеном.");
  });
  $("copy-token").addEventListener("click", () => {
    if (state.token)
      copyText(state.token, "Токен скопирован. Передавайте его только своему Java-клиенту.");
  });
  $("join").addEventListener("click", () =>
    runAction("queue", async () => {
      const token = state.token;
      const name = $("tank-name").value.trim();
      if (!name) {
        $("tank-name").reportValidity();
        return;
      }
      if (name !== state.me?.name) await api("/me/name", { method: "POST", body: { name } });
      const result = await api("/queue", {
        method: "POST",
        body: { mode: "JOIN", opponent: "", map: "commons" },
      });
      if (!token || state.token !== token) return;
      state.autoFollow = true;
      state.me = {
        ...state.me,
        queued: result.queued,
        matchId: result.matchId,
      };
      if (result.matchId) await selectMatch(result.matchId, false);
      await refreshDashboard(true);
      $("account-panel").open = false;
      notify(
        result.queued || !result.matchId
          ? "Заявка на вход принята."
          : "Вы на арене. Включите управление или оставьте играть Java-клиент.",
      );
    }),
  );
  $("leave-queue").addEventListener("click", () =>
    runAction("queue", async () => {
      await api("/queue", { method: "DELETE" });
      await syncMe();
      await refreshDashboard(true);
      notify("Подключение отменено.");
    }),
  );
  for (const filter of ["all", "alive", "dead"])
    $("roster-" + filter).addEventListener("click", () => {
      state.rosterFilter = filter;
      for (const kind of ["all", "alive", "dead"]) {
        $("roster-" + kind).classList.toggle("active", filter === kind);
        $("roster-" + kind).setAttribute("aria-pressed", String(filter === kind));
      }
      renderRoster(displayMatch(), displayState());
    });
  $("forfeit").addEventListener("click", () =>
    runAction("queue", async () => {
      const id = state.me?.matchId;
      if (!id) return;
      await api(`/matches/${encodeURIComponent(id)}/forfeit`, {
        method: "POST",
      });
      setManual(false);
      await syncMe();
      await selectMatch(id, false);
      $("account-panel").open = true;
      notify("Вы покинули поле. Можно войти снова; очки сохранены.");
    }),
  );
  $("manual-toggle").addEventListener("click", () => {
    setManual(!state.manual);
    if (state.manual) {
      canvas.focus({ preventScroll: true });
      if (window.matchMedia("(pointer:coarse)").matches)
        $("arena-panel").scrollIntoView({ block: "start" });
    }
  });
  $("shield-control").addEventListener("click", requestShield);
  $("locate-self").addEventListener("click", () => {
    state.focusSlot =
      displayMatch()?.players?.find((player) => player.id === state.me?.playerId)?.slot ?? -1;
    updateMatchChrome();
    $("arena-panel").scrollIntoView({
      block: "start",
      behavior: reducedMotion.matches ? "auto" : "smooth",
    });
  });
  $("fullscreen").addEventListener("click", () =>
    runAction("fullscreen", async () => {
      if (document.fullscreenElement) await document.exitFullscreen();
      else await $("arena-panel").requestFullscreen();
    }),
  );
  for (const filter of document.querySelectorAll("[data-filter]"))
    filter.addEventListener("click", () => {
      state.filter = filter.dataset.filter;
      for (const node of document.querySelectorAll("[data-filter]")) {
        node.classList.toggle("active", node === filter);
        node.setAttribute("aria-pressed", String(node === filter));
      }
      renderMatches();
    });
  $("refresh-matches").addEventListener("click", () =>
    runAction("refresh", () => refreshDashboard(true)),
  );
  $("empty-replay").addEventListener("click", () =>
    runAction("replay", async () => {
      const last = state.matches.find((match) => match.status === "FINISHED");
      if (!last) return;
      await loadReplay(last.id);
      if (state.replay?.match.id === last.id) {
        state.replay.playing = true;
        state.replay.at = performance.now();
        updateReplayControls();
      }
    }),
  );
  $("result-replay").addEventListener("click", () =>
    runAction("replay", () => loadReplay(state.matchId)),
  );
  $("replay-toggle").addEventListener("click", () => {
    if (!state.replay) return;
    if (state.replay.position >= state.replay.frames.length - 1) {
      state.replay.position = 0;
      clearEffects();
      updateMatchChrome();
    }
    state.replay.playing = !state.replay.playing;
    state.replay.at = performance.now();
    updateReplayControls();
  });
  $("replay-seek").addEventListener("input", () => {
    if (!state.replay) return;
    state.replay.position = Number($("replay-seek").value);
    clearEffects();
    updateReplayControls();
    updateMatchChrome();
  });
  $("replay-speed").addEventListener("change", () => {
    if (state.replay) state.replay.speed = Number($("replay-speed").value);
  });
  $("replay-exit").addEventListener("click", exitReplay);
  $("admin-form").addEventListener("submit", (event) => {
    event.preventDefault();
    runAction("admin", async () => {
      invalidateAdminRequests();
      state.adminKey = $("admin-key").value.trim();
      $("admin-key").value = "";
      await refreshAdmin();
    });
  });
  $("admin-logout").addEventListener("click", closeAdmin);
  $("admin-refresh").addEventListener("click", () => runAction("admin", refreshAdmin));
  $("admin-search").addEventListener("input", renderAdminPlayers);
  $("admin-filter").addEventListener("change", renderAdminPlayers);
  $("server-password-form").addEventListener("submit", (event) => {
    event.preventDefault();
    runAction("access", async () => {
      const adminKey = state.adminKey;
      const password = $("server-password").value;
      $("server-password").value = "";
      if (password.length < 8 || password.length > 128)
        throw new ApiError(400, "INVALID_PASSWORD", "Проверьте длину пароля.");
      await api("/admin/access", {
        method: "POST",
        admin: true,
        body: { password },
      });
      if (!adminKey || state.adminKey !== adminKey) return;
      renderServerAccess({ passwordConfigured: true });
      notify("Пароль сервера сохранён. Сообщите его студентам.");
    });
  });
  $("tournament-form").addEventListener("submit", (event) => {
    event.preventDefault();
    runAction("tournament", async () => {
      const playerIds = Array.from(
        $("player-picker").querySelectorAll("input:checked"),
        (input) => input.value,
      );
      if (playerIds.length > (state.info?.maxParticipants || 32))
        throw new ApiError(400, "TOO_MANY_PLAYERS", "Состав превышает вместимость сервера.");
      if (!Number.isSafeInteger(Number($("tournament-seed").value)))
        throw new ApiError(400, "INVALID_SEED", "Seed должен быть целым безопасным числом.");
      if (playerIds.length < 2)
        throw new ApiError(400, "PLAYERS_REQUIRED", "Выберите минимум двух участников.");
      await api("/admin/tournament", {
        method: "POST",
        admin: true,
        body: {
          playerIds,
          map: "commons",
          rounds: Number($("tournament-rounds").value),
          seed: Number($("tournament-seed").value),
        },
      });
      await refreshAdmin();
      notify("Турнир запущен. Клиенты выбранных участников должны быть подключены.");
    });
  });
  $("cancel-tournament").addEventListener("click", () =>
    runAction("tournament", async () => {
      if (!window.confirm("Остановить текущий турнир?")) return;
      await api("/admin/tournament/cancel", { method: "POST", admin: true });
      await refreshAdmin();
      notify("Турнир остановлен.");
    }),
  );
  function typingTarget(target) {
    return (
      target instanceof Element &&
      Boolean(target.closest("input,textarea,select,[contenteditable=true],dialog"))
    );
  }
  window.addEventListener("keydown", (event) => {
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    if (
      document.activeElement !== canvas ||
      typingTarget(event.target) ||
      !state.manual ||
      !ownAlive() ||
      state.replay
    )
      return;
    if (event.code === "KeyE") {
      event.preventDefault();
      if (!event.repeat) requestShield();
    }
    if (
      ["KeyW", "KeyA", "KeyS", "KeyD", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(
        event.code,
      )
    ) {
      event.preventDefault();
      state.keys.add(event.code);
    }
  });
  window.addEventListener("keyup", (event) => {
    state.keys.delete(event.code);
  });
  window.addEventListener("resize", () => {
    state.dirty = true;
  });
  document.addEventListener("fullscreenchange", () => {
    state.dirty = true;
    $("fullscreen").textContent = document.fullscreenElement ? "Свернуть" : "На весь экран";
    $("fullscreen").setAttribute("aria-pressed", String(Boolean(document.fullscreenElement)));
  });
  reducedMotion.addEventListener("change", () => {
    state.dirty = true;
  });
  window.addEventListener("blur", clearInput);
  document.addEventListener("visibilitychange", clearInput);
  document.addEventListener("focusin", (event) => {
    if (typingTarget(event.target)) clearInput();
  });
  function updateAim(event) {
    const rect = canvas.getBoundingClientRect();
    // Fullscreen uses a contained canvas, so translate through its actual image rectangle.
    const ratio = Math.min(rect.width / canvas.width, rect.height / canvas.height);
    const left = rect.left + (rect.width - canvas.width * ratio) / 2;
    const top = rect.top + (rect.height - canvas.height * ratio) / 2;
    state.aim = {
      x: Math.max(0, Math.min(canvas.width, (event.clientX - left) / ratio)),
      y: Math.max(0, Math.min(canvas.height, (event.clientY - top) / ratio)),
    };
    if (state.manual) state.dirty = true;
  }
  canvas.addEventListener("pointermove", updateAim);
  canvas.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) return;
    if (state.manual && ownAlive() && !state.replay) {
      updateAim(event);
      if (event.pointerType !== "touch") state.fireInput.press();
      canvas.focus({ preventScroll: true });
      canvas.setPointerCapture(event.pointerId);
    } else if (!state.manual) {
      // Picking a tank is local observation only: it never sends a control command.
      updateAim(event);
      const match = displayMatch(),
        frame = displayState(),
        radius = (match?.rules?.tankRadius || 18) * 1.9;
      const nearest = (frame?.tanks || [])
        .filter((tank) => playerForSlot(match, tank.slot))
        .map((tank) => ({
          slot: tank.slot,
          distance: Math.hypot(tank.position.x - state.aim.x, tank.position.y - state.aim.y),
        }))
        .filter((tank) => tank.distance <= radius)
        .sort((a, b) => a.distance - b.distance)[0];
      if (nearest) {
        state.focusSlot = state.focusSlot === nearest.slot ? -1 : nearest.slot;
        updateMatchChrome();
      }
    }
  });
  const stopPointerFire = (event) => {
    if (event.pointerType !== "touch") state.fireInput.release();
  };
  canvas.addEventListener("pointerup", stopPointerFire);
  canvas.addEventListener("pointercancel", stopPointerFire);
  canvas.addEventListener("lostpointercapture", stopPointerFire);
  for (const control of document.querySelectorAll("[data-drive],#touch-fire")) {
    control.addEventListener("pointerdown", (event) => {
      if (!state.manual || !ownAlive() || state.replay || $("help-dialog").open) return;
      event.preventDefault();
      control.setPointerCapture(event.pointerId);
      if (control.dataset.drive) state.keys.add(control.dataset.drive);
      else state.fireInput.press();
      control.classList.add("pressed");
    });
    const release = () => {
      if (control.dataset.drive) state.keys.delete(control.dataset.drive);
      else state.fireInput.release();
      control.classList.remove("pressed");
    };
    for (const type of ["pointerup", "pointercancel", "lostpointercapture"])
      control.addEventListener(type, release);
  }
  canvas.addEventListener("contextmenu", (event) => event.preventDefault());
  updateSession();
  updateMatchChrome();
  if (location.hash === "#teacher") showTeacher(true);
  requestAnimationFrame(animationFrame);
  dashboardLoop();
  matchLoop();
  adminLoop();
})();
