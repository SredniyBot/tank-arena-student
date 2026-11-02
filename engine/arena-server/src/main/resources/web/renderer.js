/** Canvas-only presentation: no transport, credentials or gameplay decisions. */
export function createArenaRenderer(canvas) {
  const ctx = canvas.getContext("2d", { alpha: false });
  if (!ctx) throw new Error("Браузер не поддерживает Canvas 2D.");
  const ground = document.createElement("canvas");
  let groundKey = "",
    frameKey = "",
    lastTick = -1,
    effects = [],
    previousTanks = new Map(),
    disposed = false;
  const tau = Math.PI * 2;
  const mix = (a, b, t) => a + (b - a) * t;
  const angle = (a, b, t) => a + Math.atan2(Math.sin(b - a), Math.cos(b - a)) * t;
  const rect = (c, x, y, w, h, r = 2) => {
    c.beginPath();
    c.roundRect(x, y, w, h, r);
  };
  const circle = (c, x, y, r) => {
    c.beginPath();
    c.arc(x, y, Math.max(0.1, r), 0, tau);
  };
  const polygon = (c, points) => {
    c.beginPath();
    points.forEach(([x, y], i) => (i ? c.lineTo(x, y) : c.moveTo(x, y)));
    c.closePath();
  };
  const location = (tank, t) => ({
    x: tank.previous ? mix(tank.previous.position.x, tank.position.x, t) : tank.position.x,
    y: tank.previous ? mix(tank.previous.position.y, tank.position.y, t) : tank.position.y,
  });

  function paintCover(c, wall, recon = false) {
    const { x, y, width: w, height: h } = wall;
    // Only the top rectangle is solid cover. The offset is an ordinary cast shadow.
    c.fillStyle = "#334b5c18";
    rect(c, x + 7, y + 8, w + 3, h + 3, 3);
    c.fill();
    c.fillStyle = "#334b5c24";
    rect(c, x + 4, y + 5, w + 1, h + 1, 2);
    c.fill();
    c.fillStyle = recon ? "#455c68" : "#4d6879";
    rect(c, x, y, w, h, 2);
    c.fill();
    c.fillStyle = recon ? "#819393" : "#7e99a7";
    rect(c, x + 2, y + 2, w - 4, h - 5, 1);
    c.fill();
    c.fillStyle = recon ? "#b9c6bd" : "#b3c5cd";
    c.fillRect(x + 3, y + 3, w - 6, 3);
    c.fillStyle = "#496574";
    c.fillRect(x + 3, y + h - 5, w - 6, 3);
    c.fillStyle = recon ? "#a1b0a8" : "#91a9b4";
    c.fillRect(x + 4, y + 7, 3, h - 15);
    c.fillStyle = recon ? "#687e81" : "#698595";
    c.fillRect(x + w - 7, y + 7, 3, h - 15);
    c.strokeStyle = "#526f8180";
    c.lineWidth = 1.3;
    const count = Math.max(1, Math.ceil(Math.max(w, h) / 64));
    for (let i = 1; i < count; i++) {
      c.beginPath();
      if (w >= h) {
        const xx = x + (w * i) / count;
        c.moveTo(xx, y + 7);
        c.lineTo(xx, y + h - 7);
      } else {
        const yy = y + (h * i) / count;
        c.moveTo(x + 7, yy);
        c.lineTo(x + w - 7, yy);
      }
      c.stroke();
    }
    for (const px of [x + 10, x + w - 10])
      for (const py of [y + 10, y + h - 10]) {
        c.fillStyle = "#436173";
        circle(c, px, py, 2);
        c.fill();
        c.fillStyle = "#bdcdd3";
        c.fillRect(px - 1, py - 1, 1.5, 1.5);
      }
    // Worn yellow paint makes the cover boundary visible without extending it.
    c.save();
    rect(c, x + 8, y + 8, Math.min(28, w - 16), 5, 0);
    c.clip();
    c.fillStyle = "#d9b75d";
    c.fillRect(x + 8, y + 8, 28, 5);
    c.strokeStyle = "#596c72";
    c.lineWidth = 4;
    for (let n = 0; n < 4; n++) {
      c.beginPath();
      c.moveTo(x + 10 + n * 10, y + 7);
      c.lineTo(x + 15 + n * 10, y + 14);
      c.stroke();
    }
    c.restore();
  }

  function paintGround(model) {
    const key = JSON.stringify([model.width, model.height, model.walls, Boolean(model.recon)]);
    if (key === groundKey) return;
    groundKey = key;
    ground.width = model.width;
    ground.height = model.height;
    const c = ground.getContext("2d", { alpha: false }),
      w = model.width,
      h = model.height;
    c.fillStyle = model.recon ? "#d9dfd9" : "#dbe3e7";
    c.fillRect(0, 0, w, h);
    for (let y = 0; y < h; y += 160)
      for (let x = 0; x < w; x += 160) {
        const shade = ((x / 160) * 7 + (y / 160) * 11) % 4;
        c.fillStyle = (
          model.recon
            ? ["#dce1da", "#d6ddd8", "#d7deda", "#e0e4dc"]
            : ["#dae2e6", "#dce4e8", "#d8e1e5", "#dce5e8"]
        )[shade];
        c.fillRect(x + 1, y + 1, 158, 158);
        c.fillStyle = "#71899515";
        c.fillRect(x, y, 1, 160);
        c.fillRect(x, y, 160, 1);
        c.fillStyle = "#ffffff48";
        c.fillRect(x + 2, y + 2, 156, 1);
      }
    // Fixed grain, built once; no random work or growing trail buffer in the animation loop.
    let seed = 32749;
    for (let i = 0; i < 6500; i++) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      const x = seed % w;
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      const y = seed % h;
      c.fillStyle = i % 2 ? "#ffffff19" : "#5068770b";
      c.fillRect(x, y, i % 5 === 0 ? 3 : 1, 1);
    }
    c.strokeStyle = "#fbfcf2aa";
    c.lineWidth = 3;
    c.setLineDash([18, 18]);
    c.strokeRect(33, 33, w - 66, h - 66);
    c.setLineDash([]);
    c.fillStyle = model.recon ? "#62777b" : "#698494";
    c.fillRect(0, 0, w, 15);
    c.fillRect(0, h - 15, w, 15);
    c.fillRect(0, 0, 15, h);
    c.fillRect(w - 15, 0, 15, h);
    c.fillStyle = "#a9bec8";
    c.fillRect(0, 0, w, 4);
    c.fillRect(0, 0, 4, h);
    c.fillStyle = "#436273";
    c.fillRect(0, h - 4, w, 4);
    c.fillRect(w - 4, 0, 4, h);
    for (let p = 80; p < Math.max(w, h); p += 160) {
      c.fillStyle = "#b2c4cc";
      if (p < w) {
        c.fillRect(p, 3, 18, 7);
        c.fillRect(p, h - 12, 18, 7);
      }
      if (p < h) {
        c.fillRect(3, p, 7, 18);
        c.fillRect(w - 12, p, 7, 18);
      }
    }
    c.strokeStyle = model.recon ? "#f8fbf64d" : "#f8fbf6a0";
    c.lineWidth = 3;
    circle(c, w / 2, h / 2, 66);
    c.stroke();
    c.beginPath();
    c.moveTo(w / 2 - 14, h / 2);
    c.lineTo(w / 2 + 14, h / 2);
    c.moveTo(w / 2, h / 2 - 14);
    c.lineTo(w / 2, h / 2 + 14);
    c.stroke();
    c.fillStyle = "#8ea5b055";
    c.font = "600 18px Bahnschrift, sans-serif";
    c.textAlign = "center";
    c.fillText("TANK ARENA", w / 2, h - 52);
    for (const wall of model.walls) paintCover(c, wall, Boolean(model.recon));
  }

  function drawVisibility(model, pixel) {
    if (!model.visibility) return;
    const points = model.visibility.polygon;
    ctx.save();
    ctx.fillStyle = "#304a5b85";
    ctx.beginPath();
    ctx.rect(0, 0, model.width, model.height);
    if (points.length) {
      ctx.moveTo(points[0].x, points[0].y);
      for (const point of points.slice(1)) ctx.lineTo(point.x, point.y);
      ctx.closePath();
    }
    ctx.fill("evenodd");
    if (points.length) {
      ctx.strokeStyle = "#f6fcf2bd";
      ctx.lineWidth = 1.2 * pixel;
      ctx.setLineDash([6 * pixel, 5 * pixel]);
      ctx.beginPath();
      ctx.moveTo(points[0].x, points[0].y);
      for (const point of points.slice(1)) ctx.lineTo(point.x, point.y);
      ctx.closePath();
      ctx.stroke();
    }
    ctx.restore();
  }

  function drawTank(tank, model, t, now, pixel, reducedMotion) {
    const { x, y } = location(tank, t),
      before = tank.previous;
    const heading = before ? angle(before.heading, tank.heading, t) : tank.heading;
    const gun = before ? angle(before.gunHeading, tank.gunHeading, t) : tank.gunHeading;
    const alive = tank.health > 0,
      s = model.tankRadius / 18;
    const shot = effects.find(
      (e) => e.type === "SHOT" && e.actor === tank.slot && now - e.born < 130,
    );
    const hit = effects.some(
      (e) => e.type === "HIT" && e.target === tank.slot && now - e.born < 130,
    );
    const moving =
      before &&
      Math.hypot(before.position.x - tank.position.x, before.position.y - tank.position.y) > 0.2;
    ctx.save();
    ctx.translate(x, y);
    ctx.scale(s, s);
    if (alive && (tank.self || tank.focused)) {
      ctx.strokeStyle = "#244c6c";
      ctx.lineWidth = (2 * pixel) / s;
      ctx.setLineDash([(8 * pixel) / s, (4 * pixel) / s]);
      circle(ctx, 0, 0, 29);
      ctx.stroke();
      ctx.setLineDash([]);
    }
    ctx.save();
    ctx.translate(3, 5);
    ctx.rotate(heading);
    ctx.fillStyle = alive ? "#233c5035" : "#233c5020";
    rect(ctx, -24, -20, 48, 40, 8);
    ctx.fill();
    ctx.restore();
    if (alive && tank.shieldTicks > 0) {
      ctx.fillStyle = "#d1f7ff38";
      circle(ctx, 0, 0, 32);
      ctx.fill();
      ctx.strokeStyle = "#4bbed5";
      ctx.lineWidth = (3 * pixel) / s;
      circle(ctx, 0, 0, 32);
      ctx.stroke();
      ctx.strokeStyle = "#eaffff";
      ctx.lineWidth = pixel / s;
      ctx.beginPath();
      ctx.arc(0, 0, 34, -2.8, -0.8);
      ctx.stroke();
      ctx.strokeStyle = "#177c9c";
      ctx.lineWidth = (1.1 * pixel) / s;
      ctx.beginPath();
      ctx.arc(0, 0, 32, 0.25, 1.85);
      ctx.stroke();
    }
    ctx.save();
    ctx.rotate(heading);
    const tread =
      moving && !reducedMotion ? (x * Math.cos(heading) + y * Math.sin(heading)) % 6 : 0;
    for (const yy of [-18, 11]) {
      ctx.fillStyle = alive ? "#253e4f" : "#687780";
      rect(ctx, -22, yy, 44, 8, 3);
      ctx.fill();
      ctx.save();
      rect(ctx, -21, yy + 1, 42, 6, 2);
      ctx.clip();
      ctx.strokeStyle = alive ? "#7992a1" : "#8b989e";
      ctx.lineWidth = 1.4;
      for (let xx = -28 + tread; xx < 28; xx += 6) {
        ctx.beginPath();
        ctx.moveTo(xx, yy + 1);
        ctx.lineTo(xx, yy + 7);
        ctx.stroke();
      }
      ctx.restore();
    }
    ctx.fillStyle = alive ? (hit && !reducedMotion ? "#fff4c8" : tank.color) : "#8c999e";
    polygon(ctx, [
      [-18, -12],
      [13, -12],
      [20, -7],
      [20, 7],
      [13, 12],
      [-18, 12],
      [-21, 7],
      [-21, -7],
    ]);
    ctx.fill();
    ctx.strokeStyle = alive ? "#243e50" : "#66747b";
    ctx.lineWidth = 1.5;
    ctx.stroke();
    ctx.fillStyle = "#ffffff52";
    polygon(ctx, [
      [-17, -10],
      [13, -10],
      [17, -6],
      [-17, -6],
    ]);
    ctx.fill();
    ctx.fillStyle = "#1532433c";
    ctx.fillRect(-18, 7, 32, 3);
    ctx.fillRect(-16, -4, 9, 9);
    ctx.strokeStyle = "#17334677";
    ctx.lineWidth = 1.2;
    for (let xx = -15; xx < -7; xx += 3) {
      ctx.beginPath();
      ctx.moveTo(xx, -3);
      ctx.lineTo(xx, 4);
      ctx.stroke();
    }
    ctx.fillStyle = alive ? "#fff3c7" : "#aab1ad";
    ctx.fillRect(15, -7, 4, 3);
    ctx.fillRect(15, 4, 4, 3);
    if (moving && !reducedMotion) {
      ctx.fillStyle = "#71889425";
      circle(ctx, -28, -12, 4);
      ctx.fill();
      circle(ctx, -29, 12, 3);
      ctx.fill();
    }
    ctx.restore();
    ctx.save();
    ctx.rotate(gun);
    const recoil = shot && !reducedMotion ? 4 * (1 - (now - shot.born) / 130) : 0;
    ctx.translate(-recoil, 0);
    ctx.fillStyle = "#233f53";
    rect(ctx, 4, -4, 28, 8, 1);
    ctx.fill();
    ctx.fillStyle = alive ? "#b4ccd7" : "#8d9ba1";
    ctx.fillRect(10, -2.5, 19, 4);
    ctx.fillStyle = alive ? tank.color : "#818f96";
    ctx.fillRect(27, -4.5, 8, 9);
    ctx.fillStyle = "#263d4b";
    ctx.fillRect(32, -3.5, 3, 7);
    ctx.fillStyle = "#19384c40";
    rect(ctx, -10, -9, 26, 22, 6);
    ctx.fill();
    ctx.fillStyle = alive ? (hit && !reducedMotion ? "#fff3c0" : tank.color) : "#849399";
    polygon(ctx, [
      [-10, -6],
      [-5, -11],
      [8, -11],
      [14, -5],
      [14, 5],
      [8, 10],
      [-6, 10],
      [-10, 6],
    ]);
    ctx.fill();
    ctx.strokeStyle = "#254357";
    ctx.lineWidth = 1.4;
    ctx.stroke();
    ctx.fillStyle = "#ffffff60";
    polygon(ctx, [
      [-5, -9],
      [7, -9],
      [10, -5],
      [-7, -5],
    ]);
    ctx.fill();
    ctx.fillStyle = alive ? "#294c6088" : "#53666f";
    circle(ctx, 0, 2, 4.2);
    ctx.fill();
    ctx.strokeStyle = "#e7f4f570";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(0, 2, 4.2, Math.PI, Math.PI * 1.75);
    ctx.stroke();
    if (!alive) {
      ctx.strokeStyle = "#26394499";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(-7, -6);
      ctx.lineTo(7, 7);
      ctx.moveTo(8, -5);
      ctx.lineTo(-5, 5);
      ctx.stroke();
    }
    ctx.restore();
    ctx.restore();
    if (alive) {
      const gap = 2 * pixel,
        segment = 6 * pixel,
        w = model.hitPoints * (gap + segment) - gap,
        top = y < 40 * s ? y + 24 * s : y - 26 * s;
      ctx.fillStyle = "#f7fbfbe0";
      rect(ctx, x - w / 2 - 2 * pixel, top - 2 * pixel, w + 4 * pixel, 6 * pixel, 2 * pixel);
      ctx.fill();
      for (let hp = 0; hp < model.hitPoints; hp++) {
        ctx.fillStyle = hp < tank.health ? (tank.health === 1 ? "#b84b47" : "#355d67") : "#b5c5cb";
        rect(ctx, x - w / 2 + hp * (segment + gap), top, segment, 2.5 * pixel, pixel);
        ctx.fill();
      }
    }
  }

  function drawNames(model, t, pixel) {
    const occupied = (model.delivery?.cargo || [])
      .filter((cargo) => cargo.available)
      .map((cargo) => {
        const p = cargoPosition(cargo, model, t, pixel);
        return {
          x: p.x - 12 * pixel,
          y: p.y - 12 * pixel,
          width: 24 * pixel,
          height: 24 * pixel,
        };
      });
    const names = [...model.tanks].sort(
      (a, b) =>
        Number(b.self || b.focused) - Number(a.self || a.focused) ||
        b.health - a.health ||
        a.slot - b.slot,
    );
    for (const tank of names) {
      const { x, y } = location(tank, t),
        full = tank.self || tank.focused,
        points = Array.from(tank.name),
        limit = full ? 32 : 14;
      ctx.save();
      ctx.font = `${full ? 650 : 600} ${12 * pixel}px Segoe UI, sans-serif`;
      const shown = points.slice(0, limit);
      let shortened = points.length > shown.length;
      let label = shown.join("") + (shortened ? "…" : "");
      const maxWidth = model.width - 36,
        maxTextWidth = Math.max(0, maxWidth - 14 * pixel);
      // Code-point count alone cannot bound wide Unicode names on a small screen.
      while (shown.length && ctx.measureText(label).width > maxTextWidth) {
        shown.pop();
        shortened = true;
        label = shown.join("") + "…";
      }
      if (ctx.measureText(label).width > maxTextWidth) label = "";
      const width = Math.min(maxWidth, ctx.measureText(label).width + 14 * pixel),
        height = 21 * pixel;
      const top = y - (32 * model.tankRadius) / 18 - height;
      const below = y + (34 * model.tankRadius) / 18;
      const candidates = [
        [x - width / 2, top],
        [x - width / 2, below],
        [x + 31, y - height / 2],
        [x - width - 31, y - height / 2],
        [x - width / 2, top - height - 4],
      ];
      let chosen = null,
        best = Infinity;
      for (const [cx, cy] of candidates) {
        const r = {
          x: Math.max(18, Math.min(model.width - width - 18, cx)),
          y: Math.max(18, Math.min(model.height - height - 18, cy)),
          width,
          height,
        };
        const overlapArea = (o) =>
          Math.max(0, Math.min(r.x + width, o.x + o.width) - Math.max(r.x, o.x)) *
          Math.max(0, Math.min(r.y + height, o.y + o.height) - Math.max(r.y, o.y));
        // Prefer a displaced name to one that hides a tank against the field boundary.
        const bodyPenalty = model.tanks.reduce((sum, other) => {
          const p = location(other, t),
            radius = model.tankRadius * 1.4;
          return (
            sum +
            overlapArea({
              x: p.x - radius,
              y: p.y - radius,
              width: 2 * radius,
              height: 2 * radius,
            }) *
              2
          );
        }, 0);
        const overlap = occupied.reduce((n, o) => n + overlapArea(o), 0) + bodyPenalty;
        if (overlap < best) {
          chosen = r;
          best = overlap;
        }
        if (overlap === 0) break;
      }
      occupied.push(chosen);
      const { x: lx, y: ly } = chosen;
      if (Math.abs(lx + width / 2 - x) > 5 || Math.abs(ly - top) > 5) {
        ctx.strokeStyle = "#3d617b66";
        ctx.lineWidth = pixel;
        ctx.beginPath();
        ctx.moveTo(x, y);
        ctx.lineTo(lx + width / 2, ly + height / 2);
        ctx.stroke();
      }
      ctx.fillStyle = full ? "#244b66" : tank.health > 0 ? "#f8fcf8ee" : "#dbe4e8d9";
      rect(ctx, lx, ly, width, height, 4 * pixel);
      ctx.fill();
      ctx.fillStyle = tank.health > 0 ? tank.color : "#84969f";
      circle(ctx, lx + 5 * pixel, ly + height / 2, 2 * pixel);
      ctx.fill();
      ctx.fillStyle = full ? "#fff" : tank.health > 0 ? "#203b4e" : "#4f6878";
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText(label, lx + width / 2 + 2 * pixel, ly + height / 2);
      ctx.restore();
    }
  }

  function updateEffects(model, time, reducedMotion) {
    const key = model.id + ":" + model.tick;
    if (key !== frameKey) {
      const discontinuity =
        !frameKey.startsWith(model.id + ":") || model.tick < lastTick || model.tick > lastTick + 12;
      if (discontinuity) {
        effects = [];
        previousTanks.clear();
      }
      if (!reducedMotion) {
        for (const event of model.events) effects.push({ ...event, born: time, tick: model.tick });
        if (!discontinuity)
          for (const tank of model.tanks) {
            const old = previousTanks.get(tank.slot);
            if (tank.health > 0 && old && (old.health <= 0 || tank.life !== old.life))
              effects.push({
                type: "SPAWN",
                position: tank.position,
                born: time,
                tick: model.tick,
              });
          }
      }
      previousTanks = new Map(
        model.tanks.map((tank) => [tank.slot, { health: tank.health, life: tank.life }]),
      );
      effects = effects.slice(-96);
      frameKey = key;
      lastTick = model.tick;
    }
    if (reducedMotion) effects = [];
    effects = effects.filter(
      (e) => time - e.born < (e.type === "DESTROYED" ? 750 : e.type === "SPAWN" ? 600 : 240),
    );
  }

  function drawEffects(model, now, pixel) {
    for (const event of effects) {
      const duration = event.type === "DESTROYED" ? 750 : event.type === "SPAWN" ? 600 : 240,
        p = Math.max(0, (now - event.born) / duration),
        x = event.position.x,
        y = event.position.y;
      ctx.save();
      ctx.translate(x, y);
      if (event.type === "SPAWN") {
        ctx.globalAlpha = (1 - p) * 0.65;
        ctx.strokeStyle = "#287f9f";
        ctx.lineWidth = 2 * pixel;
        circle(ctx, 0, 0, 18 + p * 38);
        ctx.stroke();
        ctx.restore();
        continue;
      }
      if (event.type === "SHOT") {
        const owner = model.tanks.find((tank) => tank.slot === event.actor);
        ctx.rotate(owner?.gunHeading || 0);
        ctx.translate((33 * model.tankRadius) / 18, 0);
        ctx.globalAlpha = 1 - p;
        ctx.fillStyle = "#efb44c";
        polygon(ctx, [
          [0, -6],
          [8, -3],
          [14, -5],
          [10, 0],
          [18, 3],
          [6, 4],
          [0, 6],
        ]);
        ctx.fill();
        ctx.fillStyle = "#fff5c6";
        polygon(ctx, [
          [0, -3],
          [11, 0],
          [0, 3],
        ]);
        ctx.fill();
      } else if (event.type === "DESTROYED") {
        ctx.globalAlpha = (1 - p) * 0.55;
        ctx.fillStyle = "#425663";
        for (let i = 0; i < 5; i++) {
          const a = i * 2.399 + event.tick;
          circle(ctx, Math.cos(a) * p * 25, Math.sin(a) * p * 18 - p * 20, 8 + p * 18);
          ctx.fill();
        }
        if (p < 0.45) {
          ctx.globalAlpha = (1 - p / 0.45) * 0.95;
          ctx.fillStyle = "#f3b85b";
          circle(ctx, 0, 0, 7 + p * 38);
          ctx.fill();
          ctx.fillStyle = "#fff4b7";
          circle(ctx, -3, -3, 4 + p * 18);
          ctx.fill();
        }
        ctx.globalAlpha = (1 - p) * 0.9;
        ctx.strokeStyle = "#d48d41";
        ctx.lineWidth = 2 * pixel;
        for (let i = 0; i < 9; i++) {
          const a = i * 2.399 + event.tick * 0.4,
            d = 8 + p * 55;
          ctx.beginPath();
          ctx.moveTo(Math.cos(a) * d, Math.sin(a) * d);
          ctx.lineTo(Math.cos(a) * (d + 5), Math.sin(a) * (d + 5));
          ctx.stroke();
        }
      } else if (event.type === "SHIELD_BLOCK") {
        ctx.globalAlpha = 1 - p;
        ctx.strokeStyle = "#087c9e";
        ctx.lineWidth = 2.5 * pixel;
        circle(ctx, 0, 0, 4 + p * 24);
        ctx.stroke();
        ctx.fillStyle = "#e7ffff";
        circle(ctx, 0, 0, 5 * (1 - p));
        ctx.fill();
      } else if (event.type === "HIT" || event.type === "WALL" || event.type === "ZONE") {
        ctx.globalAlpha = 1 - p;
        ctx.strokeStyle = event.type === "ZONE" ? "#be4d4a" : "#b87c37";
        ctx.lineWidth = 1.5 * pixel;
        for (let i = 0; i < 6; i++) {
          const a = i * 2.399 + event.tick * 0.4,
            d = 3 + p * 22;
          ctx.beginPath();
          ctx.moveTo(Math.cos(a) * d, Math.sin(a) * d);
          ctx.lineTo(Math.cos(a) * (d + 6 * (1 - p)), Math.sin(a) * (d + 6 * (1 - p)));
          ctx.stroke();
        }
        ctx.fillStyle = "#fff7d0";
        circle(ctx, 0, 0, 5 * (1 - p));
        ctx.fill();
      }
      ctx.restore();
    }
  }

  function cargoPosition(cargo, model, t, pixel) {
    const tank = model.tanks.find((item) => item.slot === cargo.carrierSlot);
    const p = tank ? location(tank, t) : cargo.position;
    const margin = 12 * pixel;
    const x = tank ? p.x + (p.x + 42 * pixel > model.width ? -30 : 30) * pixel : p.x;
    return {
      x: Math.max(margin, Math.min(model.width - margin, x)),
      y: Math.max(margin, Math.min(model.height - margin, p.y)),
    };
  }

  function drawDepots(model, pixel) {
    if (!model.delivery) return;
    const letters = ["A", "B", "C", "D"];
    ctx.save();
    for (const depot of model.delivery.depots) {
      const base = model.recon && depot.id < model.recon.teams;
      const source = model.recon && !base;
      const teamColor = ["#268fbc", "#d98c2d", "#835dc0", "#279e90"][depot.id];
      if (source && model.controlMode) {
        const zone = model.controlZones.find(
          (item) => item.position.x === depot.position.x && item.position.y === depot.position.y,
        );
        const owner = zone?.status === "controlled" ? zone.team : -1;
        const contested = zone?.status === "contested";
        const color =
          owner >= 0
            ? ["#268fbc", "#d98c2d", "#835dc0", "#279e90"][owner]
            : contested
              ? "#b45946"
              : "#526675";
        ctx.fillStyle = color + (owner >= 0 ? "20" : "0b");
        ctx.strokeStyle = color;
        ctx.lineWidth = (owner >= 0 || contested ? 3 : 1.5) * pixel;
        ctx.setLineDash(contested ? [6 * pixel, 5 * pixel] : []);
        circle(ctx, depot.position.x, depot.position.y, 100);
        ctx.fill();
        ctx.stroke();
        ctx.setLineDash([]);
        ctx.font = `600 ${14 * pixel}px system-ui`;
        ctx.textAlign = "center";
        ctx.fillStyle = color;
        ctx.fillText(
          contested
            ? "СПОР"
            : owner >= 0
              ? `ОТРЯД ${["А", "Б", "В", "Г"][owner]}`
              : `ТОЧКА ${depot.id - model.recon.teams + 1}`,
          depot.position.x,
          depot.position.y - 100 - 12 * pixel,
        );
        continue;
      }
      ctx.fillStyle = base ? teamColor + "1f" : source ? "#52667507" : "#f3e5ba90";
      ctx.strokeStyle = base ? teamColor : source ? "#657776" : "#a38b4f";
      ctx.lineWidth = (source ? 1.25 : 2) * pixel;
      circle(ctx, depot.position.x, depot.position.y, depot.radius);
      ctx.fill();
      ctx.setLineDash(base ? [] : [7 * pixel, 5 * pixel]);
      ctx.stroke();
      ctx.setLineDash([]);
      if (base) {
        ctx.lineWidth = 5 * pixel;
        for (let quadrant = 0; quadrant < 4; quadrant++) {
          ctx.beginPath();
          ctx.arc(
            depot.position.x,
            depot.position.y,
            depot.radius,
            (quadrant * Math.PI) / 2 - 0.12,
            (quadrant * Math.PI) / 2 + 0.12,
          );
          ctx.stroke();
        }
      }
      // A public source marker never signals whether unseen cargo is present.
      if (source) continue;
      ctx.font = `600 ${16 * pixel}px system-ui`;
      ctx.textAlign = "center";
      ctx.fillStyle = base ? "#344e5c" : "#6c603f";
      ctx.fillText(
        model.recon ? `База ${["А", "Б", "В", "Г"][depot.id]}` : letters[depot.id],
        depot.position.x,
        base && depot.position.y < model.height / 2
          ? depot.position.y + depot.radius + 23 * pixel
          : depot.position.y - depot.radius - 12 * pixel,
      );
    }
    ctx.restore();
  }

  function drawDelivery(model, t, pixel) {
    if (!model.delivery) return;
    const letters = ["A", "B", "C", "D"];
    ctx.save();
    for (const cargo of model.delivery.cargo) {
      if (!cargo.available) continue;
      const tank = model.tanks.find((item) => item.slot === cargo.carrierSlot);
      const p = cargoPosition(cargo, model, t, pixel);
      const selected = tank && (tank.focused || tank.self);
      const destination = model.delivery.depots.find((depot) => depot.id === cargo.destinationId);
      if (selected && destination) {
        ctx.strokeStyle = "#876f3f80";
        ctx.lineWidth = 1.5 * pixel;
        ctx.setLineDash([4 * pixel, 6 * pixel]);
        ctx.beginPath();
        ctx.moveTo(p.x, p.y);
        ctx.lineTo(destination.position.x, destination.position.y);
        ctx.stroke();
        ctx.setLineDash([]);
      }
      // The destination letter remains readable without relying on colour.
      const y = p.y,
        size = 10 * pixel;
      ctx.fillStyle = "#5b503626";
      rect(ctx, p.x - size + 2, y - size + 3, size * 2, size * 2, 2);
      ctx.fill();
      ctx.fillStyle = "#f4d878";
      ctx.strokeStyle = "#796236";
      ctx.lineWidth = 1.2 * pixel;
      rect(ctx, p.x - size, y - size, size * 2, size * 2, 2);
      ctx.fill();
      ctx.stroke();
      ctx.fillStyle = "#fff0a3";
      ctx.fillRect(p.x - size + pixel, y - size + pixel, size * 2 - pixel * 2, pixel * 2);
      ctx.fillStyle = "#ba8a36";
      ctx.fillRect(p.x - size + pixel, y + size - 3 * pixel, size * 2 - 2 * pixel, pixel * 2);
      ctx.fillStyle = "#4d432e";
      ctx.font = `700 ${12 * pixel}px system-ui`;
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText(
        model.recon
          ? cargo.carrierSlot >= 0
            ? ["А", "Б", "В", "Г"][cargo.destinationId]
            : "◆"
          : letters[cargo.destinationId],
        p.x,
        y + pixel,
      );
      ctx.textBaseline = "alphabetic";
    }
    ctx.restore();
  }

  function render(
    model,
    { time = performance.now(), progress = 1, reducedMotion = false, camera = null } = {},
  ) {
    if (disposed) return;
    const t = reducedMotion ? 1 : Math.max(0, Math.min(1, progress));
    if (canvas.width !== model.width || canvas.height !== model.height) {
      canvas.width = model.width;
      canvas.height = model.height;
    }
    const zoom = camera ? model.width / camera.width : 1;
    ctx.save();
    if (camera) ctx.setTransform(zoom, 0, 0, zoom, -camera.x * zoom, -camera.y * zoom);
    paintGround(model);
    ctx.drawImage(ground, 0, 0);
    if (!model.hasFrame) {
      ctx.restore();
      return;
    }
    updateEffects(model, time, reducedMotion);
    if (!model.continuous) {
      const safe = Math.max(0, mix(model.previousSafeRadius, model.safeRadius, t));
      ctx.save();
      ctx.fillStyle = "#a5435260";
      ctx.beginPath();
      ctx.rect(0, 0, model.width, model.height);
      ctx.arc(model.width / 2, model.height / 2, safe, 0, tau, true);
      ctx.fill("evenodd");
      ctx.strokeStyle = "#fff8e1";
      ctx.lineWidth = 4;
      circle(ctx, model.width / 2, model.height / 2, safe);
      ctx.stroke();
      ctx.strokeStyle = "#b65551";
      ctx.lineWidth = 2;
      ctx.setLineDash([10, 9]);
      ctx.stroke();
      ctx.restore();
    }
    const box = canvas.getBoundingClientRect(),
      pixel = Math.max(
        1,
        model.width /
          (zoom * Math.max(1, Math.min(box.width, (box.height * model.width) / model.height))),
      );
    drawVisibility(model, pixel);
    drawDepots(model, pixel);
    for (const supply of model.supplies || []) {
      ctx.save();
      ctx.translate(supply.position.x, supply.position.y);
      const colors = { AMMUNITION: "#926b25", REPAIR: "#2f7653", TURRET_MODULE: "#3c6598" };
      ctx.fillStyle = supply.health > 0 ? colors[supply.kind] : "#6f7470";
      ctx.strokeStyle = "#f9fbef";
      ctx.lineWidth = 2 * pixel;
      ctx.fillRect(-12, -12, 24, 24);
      ctx.strokeRect(-12, -12, 24, 24);
      ctx.fillStyle = "#ffffff";
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.font = `bold ${Math.max(14, 10 * pixel)}px system-ui`;
      ctx.fillText(
        supply.health === 0
          ? "×"
          : supply.kind === "REPAIR"
            ? "+"
            : supply.kind === "TURRET_MODULE"
              ? "◆"
              : "•",
        0,
        0,
      );
      ctx.fillStyle = "#203239";
      ctx.font = `${Math.max(12, 9 * pixel)}px system-ui`;
      ctx.fillText(`${supply.stock}/${supply.capacity}`, 0, 24 * pixel);
      ctx.restore();
    }
    for (const tank of model.tanks)
      if (tank.health <= 0) drawTank(tank, model, t, time, pixel, reducedMotion);
    for (const tank of model.tanks)
      if (tank.health > 0) drawTank(tank, model, t, time, pixel, reducedMotion);
    drawDelivery(model, t, pixel);
    for (const shot of model.projectiles) {
      const x = shot.previous ? mix(shot.previous.x, shot.position.x, t) : shot.position.x,
        y = shot.previous ? mix(shot.previous.y, shot.position.y, t) : shot.position.y;
      const length = Math.hypot(shot.velocity.x, shot.velocity.y) || 1,
        vx = shot.velocity.x / length,
        vy = shot.velocity.y / length;
      ctx.save();
      ctx.lineCap = "round";
      ctx.lineWidth = 4 * pixel;
      ctx.strokeStyle = "#263c4c26";
      ctx.beginPath();
      ctx.moveTo(x - vx * 18, y - vy * 18 + 2);
      ctx.lineTo(x, y + 2);
      ctx.stroke();
      ctx.lineWidth = 2.6 * pixel;
      ctx.strokeStyle = shot.color;
      ctx.beginPath();
      ctx.moveTo(x - vx * 18, y - vy * 18);
      ctx.lineTo(x, y);
      ctx.stroke();
      ctx.lineWidth = 1.1 * pixel;
      ctx.strokeStyle = "#fff6cc";
      ctx.beginPath();
      ctx.moveTo(x - vx * 8, y - vy * 8);
      ctx.lineTo(x, y);
      ctx.stroke();
      ctx.fillStyle = "#fff7cc";
      circle(ctx, x, y, Math.max(model.bulletRadius, 1.8 * pixel));
      ctx.fill();
      ctx.restore();
    }
    drawEffects(model, time, pixel);
    drawNames(model, t, pixel);
    if (model.aim) {
      ctx.save();
      ctx.strokeStyle = "#1d536f";
      ctx.lineWidth = 1.5 * pixel;
      circle(ctx, model.aim.x, model.aim.y, 7 * pixel);
      ctx.stroke();
      for (let i = 0; i < 4; i++) {
        const a = (i * Math.PI) / 2;
        ctx.beginPath();
        ctx.moveTo(model.aim.x + Math.cos(a) * 11 * pixel, model.aim.y + Math.sin(a) * 11 * pixel);
        ctx.lineTo(model.aim.x + Math.cos(a) * 15 * pixel, model.aim.y + Math.sin(a) * 15 * pixel);
        ctx.stroke();
      }
      ctx.restore();
    }
    ctx.restore();
  }
  return Object.freeze({
    render,
    dispose() {
      disposed = true;
      effects = [];
      previousTanks.clear();
      ground.width = 0;
      ground.height = 0;
    },
  });
}
