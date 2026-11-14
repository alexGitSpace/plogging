// Live outing: polls the outing and draws its route and picks; stops polling once the outing is over.
// To do (web/README.md, "Screens"): the nearest spot, the "just picked +10 pts" toast, quest progress, open spots around.

import { api, poll } from "../api.js";
import { clock, km } from "../ui.js";
import { createMap } from "../map.js";

const L = window.L;

export default async function render(el, sessionId) {
  el.innerHTML = `<div class="map" id="map"></div>
    <div class="map-overlay card" style="left:24px;right:24px;top:24px;max-width:520px;display:flex;justify-content:space-between">
      <div><div class="eyebrow">Time</div><div class="big" id="time">–</div></div>
      <div><div class="eyebrow">Distance</div><div class="big"><span id="dist">–</span><span class="unit">km</span></div></div>
      <div><div class="eyebrow">Picked</div><div class="big" id="picked">–</div></div>
    </div>
    <div class="map-overlay" style="left:24px;top:150px"><span class="pill dark" id="state">Loading…</span></div>`;
  const $ = (sel) => el.querySelector(sel);
  const map = createMap($("#map"), { zoom: 17 });
  const route = L.polyline([], { color: "#1db35b", weight: 5 }).addTo(map);
  const picks = L.layerGroup().addTo(map);
  let fitted = false;

  const stop = poll(async () => {
    let o;
    try {
      o = await api.outing(sessionId);
    } catch (err) {
      $("#state").textContent = err.message;
      throw err; // poll() retries later
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
