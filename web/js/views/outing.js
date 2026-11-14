// Live outing: polls the outing every 1.5 s and draws its route and picks.
// The nearest spot, the "just picked" toast and quest progress come in step 6 of PLAN.md.

import { api } from "../api.js";
import { clock, km } from "../ui.js";
import { createMap } from "../map.js";

const L = window.L;
const POLL_MS = 1500;

export default async function render(el, sessionId) {
  el.innerHTML = `<div class="map" id="map"></div>
    <div class="map-overlay card" style="left:24px;right:24px;top:24px;max-width:520px;display:flex;justify-content:space-between">
      <div><div class="eyebrow">Time</div><div class="big" id="time">–</div></div>
      <div><div class="eyebrow">Distance</div><div class="big"><span id="dist">–</span><span class="unit">km</span></div></div>
      <div><div class="eyebrow">Picked</div><div class="big" id="picked">–</div></div>
    </div>
    <div class="map-overlay" style="left:24px;top:150px"><span class="pill dark" id="state">Loading…</span></div>`;
  const map = createMap(el.querySelector("#map"), { zoom: 17 });
  const route = L.polyline([], { color: "#1db35b", weight: 5 }).addTo(map);
  const picks = L.layerGroup().addTo(map);
  let timer = null;
  let fitted = false;
  let stopped = false;

  async function tick() {
    try {
      const o = await api.outing(sessionId);
      if (stopped) return;
      el.querySelector("#time").textContent = clock(o.stats.duration_s);
      el.querySelector("#dist").textContent = km(o.stats.distance_m);
      el.querySelector("#picked").textContent = o.stats.picks;
      el.querySelector("#state").textContent = o.live ? `Recording · ${o.user_id ?? ""}` : "Finished";
      route.setLatLngs(o.route);
      picks.clearLayers();
      for (const p of o.picks) if (p.lat !== null) L.circleMarker([p.lat, p.lon], { radius: 7, color: "#fff", weight: 2, fillColor: "#141414", fillOpacity: 1 }).addTo(picks);
      if (o.route.length && !fitted) {
        map.fitBounds(route.getBounds(), { padding: [80, 80], maxZoom: 17 });
        fitted = true;
      }
      if (o.live) timer = setTimeout(tick, POLL_MS);
    } catch (err) {
      if (stopped) return;
      el.querySelector("#state").textContent = err.message;
      timer = setTimeout(tick, POLL_MS * 2);
    }
  }
  tick();

  return () => {
    stopped = true;
    clearTimeout(timer);
    map.remove();
  };
}
