const fields = [
  { key: "moveSpeed", group: "drive", label: "Скорость танка", unit: "ед./с", scale: 1 },
  { key: "turnSpeed", group: "drive", label: "Поворот корпуса", unit: "рад/с", scale: 1 },
  { key: "turretSpeed", group: "drive", label: "Поворот башни", unit: "рад/с", scale: 1 },
  { key: "bulletSpeed", group: "weapon", label: "Скорость снаряда", unit: "ед./с", scale: 1 },
  { key: "reloadMillis", group: "weapon", label: "Перезарядка", unit: "с", scale: 0.001 },
  {
    key: "shieldDurationMillis",
    group: "shield",
    label: "Длительность щита",
    unit: "с",
    scale: 0.001,
  },
  {
    key: "shieldCooldownMillis",
    group: "shield",
    label: "Между включениями щита",
    unit: "с",
    scale: 0.001,
  },
  { key: "respawnMillis", group: "return", label: "До респавна", unit: "с", scale: 0.001 },
];
const number = new Intl.NumberFormat("ru", { maximumFractionDigits: 3 });
const different = (a, b) =>
  !Number.isFinite(a) || !Number.isFinite(b) || Math.abs(a - b) > 0.000001;

export function changedConditions(before, after) {
  return fields.filter(({ key }) => different(before[key], after[key])).map(({ key }) => key);
}

export function validateConditions(settings, limits) {
  for (const field of fields) {
    const value = settings[field.key];
    const { min, max } = limits[field.key];
    if (!Number.isFinite(value) || value < min || value > max) {
      return `${field.label}: введите число от ${number.format(min * field.scale)} до ${number.format(max * field.scale)} ${field.unit}.`;
    }
  }
  if (settings.shieldCooldownMillis < settings.shieldDurationMillis) {
    return "Время между включениями щита должно быть не меньше длительности самого щита.";
  }
  return "";
}

export function conditionPreset(name, defaults, limits, tickRate) {
  const result = { ...defaults };
  if (name === "maneuver") {
    result.moveSpeed *= 1.4;
    result.bulletSpeed *= 0.7;
    result.turretSpeed *= 1.2;
  } else if (name === "rapid") {
    result.reloadMillis *= 0.5;
    result.shieldCooldownMillis *= 0.5;
  } else if (name !== "default") {
    throw new RangeError("Unknown conditions preset");
  }
  for (const { key } of fields) {
    const { min, max } = limits[key];
    result[key] = Math.min(max, Math.max(min, result[key]));
    if (key.endsWith("Millis")) {
      result[key] = Math.min(
        max,
        (Math.ceil((result[key] * tickRate) / 1000 - 1e-9) * 1000) / tickRate,
      );
    }
  }
  result.shieldCooldownMillis = Math.max(result.shieldDurationMillis, result.shieldCooldownMillis);
  return result;
}

/** Owns only the teacher's draft; the server owns accepted and active rules. */
export function createTeacherRules({ document, api, session, onError, onNotice }) {
  const $ = (id) => document.getElementById(id);
  const form = $("rules-form");
  const inputs = new Map();
  let latest = null;
  let baseRevision = null;
  let baseline = null;
  let dirty = false;
  let saving = false;
  let generation = 0;

  for (const field of fields) {
    const row = document.createElement("div");
    row.className = "condition-field";
    const label = document.createElement("label");
    label.htmlFor = `rule-${field.key}`;
    label.textContent = field.label;
    const control = document.createElement("div");
    control.className = "condition-value";
    const input = document.createElement("input");
    input.id = label.htmlFor;
    input.name = field.key;
    input.type = "number";
    input.step = "any";
    input.required = true;
    input.setAttribute("aria-describedby", `rule-unit-${field.key}`);
    const unit = document.createElement("span");
    unit.id = `rule-unit-${field.key}`;
    unit.textContent = field.unit;
    const active = document.createElement("small");
    active.id = `rule-current-${field.key}`;
    control.append(input, unit);
    row.append(label, control, active);
    $(`rules-${field.group}`).append(row);
    inputs.set(field.key, input);
  }

  function readDraft() {
    return Object.fromEntries(
      fields.map(({ key, scale }) => {
        const raw = inputs.get(key).value;
        return [key, raw === "" ? NaN : Number(raw) / scale];
      }),
    );
  }

  function writeDraft(settings) {
    for (const { key } of fields)
      inputs.get(key).value = key.endsWith("Millis") ? settings[key] / 1000 : settings[key];
  }

  function acceptCurrent() {
    if (!latest) return;
    baseline = { ...(latest.pending || latest.current) };
    baseRevision = latest.revision;
    writeDraft(baseline);
    dirty = false;
    $("rules-error").textContent = "";
    render();
  }

  function render() {
    const enabled = Boolean(latest?.enabled);
    const conflict = dirty && baseRevision !== latest?.revision;
    $("rules-fields").disabled = !enabled || saving;
    for (const button of document.querySelectorAll("[data-rules-preset]"))
      button.disabled = !enabled || saving;
    $("rules-apply").disabled = !enabled || saving || !dirty || conflict;
    $("rules-discard").disabled = saving || !dirty;
    $("rules-reset").disabled = !enabled || saving || conflict;
    $("rules-apply").textContent = saving ? "Применяем…" : "Применить ко всем";
    if (!latest) {
      $("rules-status").textContent = "Загружаем текущие условия…";
      $("rules-draft-status").textContent = "";
      return;
    }
    $("rules-status").textContent = !enabled
      ? "Изменения доступны в режиме постоянной общей арены. Условия официального турнира фиксированы."
      : latest.pending
        ? `Изменение №${latest.revision} принято. Вступит в силу с шага ${latest.effectiveFromTick}.`
        : `Действуют условия №${latest.appliedRevision} · с шага ${latest.appliedAtTick}.`;
    const edits = baseline ? changedConditions(baseline, readDraft()).length : 0;
    $("rules-draft-status").textContent = conflict
      ? "Условия уже изменили в другом окне. Ваш черновик сохранён. Нажмите «Загрузить текущие», прежде чем менять их снова."
      : dirty
        ? `Изменено полей: ${edits}. Пока это черновик — на арене действуют прежние условия.`
        : "Выберите вариант или измените значения. Кнопка «Применить ко всем» отправит изменения на сервер.";
    $("rules-draft-status").classList.toggle("condition-conflict", conflict);
    $("rules-discard").textContent = conflict ? "Загрузить текущие" : "Отменить правки";
    for (const field of fields) {
      const input = inputs.get(field.key);
      const range = latest.limits[field.key];
      input.min = range.min * field.scale;
      input.max = range.max * field.scale;
      $(`rule-current-${field.key}`).textContent =
        `Сейчас: ${number.format(latest.current[field.key] * field.scale)} ${field.unit}`;
    }
  }

  function receive(value) {
    // Requests can finish out of order, including a POST after a newer background GET.
    if (
      latest &&
      (value.revision < latest.revision || value.appliedRevision < latest.appliedRevision)
    )
      return false;
    latest = value;
    return true;
  }

  function update(value) {
    if (!receive(value)) return;
    if (!dirty && !saving && baseRevision !== value.revision) acceptCurrent();
    else render();
  }

  function clear() {
    generation += 1;
    latest = null;
    baseline = null;
    baseRevision = null;
    dirty = false;
    saving = false;
    form.reset();
    $("rules-error").textContent = "";
    for (const field of fields) $(`rule-current-${field.key}`).textContent = "";
    render();
  }

  async function submit(reset) {
    if (!latest?.enabled || saving || !session()) return;
    const settings = readDraft();
    const error = reset ? "" : validateConditions(settings, latest.limits);
    $("rules-error").textContent = error;
    if (error) return;
    const key = session();
    const requestGeneration = generation;
    const active = () => generation === requestGeneration && session() === key;
    saving = true;
    render();
    try {
      const value = await api(reset ? "/admin/rules/reset" : "/admin/rules", {
        method: "POST",
        admin: true,
        retries: 0,
        body: reset
          ? { expectedRevision: latest.revision }
          : { expectedRevision: baseRevision, settings },
      });
      if (!active()) return;
      receive(value);
      dirty = false;
      saving = false;
      acceptCurrent();
      onNotice(
        latest.revision > value.revision
          ? "Условия приняты, но уже изменены в другом окне. Показаны текущие значения."
          : latest.pending
            ? `Условия приняты для всех, начиная с шага ${latest.effectiveFromTick}.`
            : "Условия применены ко всем участникам.",
      );
    } catch (error) {
      if (!active()) return;
      onError(error);
      if (!active()) return;
      try {
        const value = await api("/admin/rules", { admin: true });
        if (active()) update(value);
      } catch (_) {
        // Keep the unsent draft available; the normal admin refresh will retry.
      }
    } finally {
      if (active()) {
        saving = false;
        render();
      }
    }
  }

  form.addEventListener("input", () => {
    if (!baseline) return;
    dirty = changedConditions(baseline, readDraft()).length > 0;
    $("rules-error").textContent = "";
    render();
  });
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    void submit(false);
  });
  for (const button of document.querySelectorAll("[data-rules-preset]")) {
    button.addEventListener("click", () => {
      if (!latest?.enabled || saving) return;
      writeDraft(
        conditionPreset(
          button.dataset.rulesPreset,
          latest.defaults,
          latest.limits,
          latest.tickRate,
        ),
      );
      dirty = changedConditions(baseline, readDraft()).length > 0;
      $("rules-error").textContent = "";
      render();
    });
  }
  $("rules-discard").addEventListener("click", acceptCurrent);
  $("rules-reset").addEventListener("click", () => void submit(true));
  render();
  return { update, clear };
}
