// Live outing: polls the outing and draws its route and picks; stops polling once the outing is over.
// #/outing/live finds the live outing of "you" by itself, waits for one if there is none yet,
// and goes to its summary when it ends, so the screen can be opened on the laptop before the walk.
// To do (web/README.md, "Screens"): the nearest spot, the "just picked +10 pts" toast, quest progress, open spots around.

import { api, currentUser, liveOuting, poll, recordUrl } from "../api.js";
import { clock, esc, icon, km } from "../ui.js";
import { createMap } from "../map.js";

const L = window.L;

export default async function render(el, param) {
  const follow = param === "live";
  let sessionId = follow ? null : param;
  el.innerHTML = `<div class="map" id="map"></div>
    <div class="map-overlay card" id="stats" style="left:24px;right:24px;top:24px;max-width:520px;display:flex;justify-content:space-between">
      <div><div class="eyebrow">Time</div><div class="big" id="time">–</div></div>
      <div><div class="eyebrow">Distance</div><div class="big"><span id="dist">–</span><span class="unit">km</span></div></div>
      <div><div class="eyebrow">Picked</div><div class="big" id="picked">–</div></div>
    </div>
    <div class="map-overlay card" id="waiting" style="left:24px;right:24px;top:24px;max-width:520px" hidden>
      <div class="eyebrow">Waiting for an outing</div>
      <p style="margin:8px 0 0">No live outing for <b>${esc(currentUser() || "anyone")}</b> yet. Start one on the phone: it shows up here by itself.</p>
      <div class="map-actions">
        <a class="btn" href="${esc(recordUrl())}" target="_blank" rel="noopener">${icon("play", 18)}Start outing</a>
      </div>
    </div>
    <div class="map-overlay" style="left:24px;top:150px"><span class="pill dark" id="state">Loading…</span></div>`;
  const $ = (sel) => el.querySelector(sel);
  const map = createMap($("#map"), { zoom: 17 });
  const route = L.polyline([], { color: "#1db35b", weight: 5 }).addTo(map);
  const picks = L.layerGroup().addTo(map);
  let fitted = false;

  const stop = poll(async () => {
    if (!sessionId) {
      const live = await liveOuting();
      $("#waiting").hidden = !!live;
      $("#stats").hidden = !live;
      $("#state").parentElement.hidden = !live;
      if (!live) return true; // look again on the next tick
      sessionId = live.session_id;
    }
    let o;
    try {
      o = await api.outing(sessionId);
    } catch (err) {
      $("#state").textContent = err.message;
      throw err; // poll() retries later
    }
    if (follow && !o.live) {
      location.hash = `#/done/${encodeURIComponent(sessionId)}`;
      return false;
    }
    $("#time").textContent = clock(o.stats.duration_s);
    $("#dist").textContent = km(o.stats.distance_m);
    $("#picked").textContent = o.stats.picks;
    $("#state").textContent = o.live ? `Recording · ${o.user_id ?? ""}` : "Finished";
    route.setLatLngs(o.route);
    picks.clearLayers();
    for (const p of o.picks) {
      if (p.lat !== null) L.circleMarker([p.lat, p.lon], { radius: 7, color: "#fff", weight: 2, fillColor: "#141414", fillOpacity: 1 }).addTo(picks);
    }
    if (o.route.length && !fitted) {
      map.fitBounds(route.getBounds(), { padding: [80, 80], maxZoom: 17 });
      fitted = true;
    }
    return o.live; // false stops polling
  });

  return () => {
    stop();
    map.remove();
  };
}
