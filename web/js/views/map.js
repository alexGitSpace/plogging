// Map: open litter spots for now. Clusters, zones, Plogger / Scout and the bottom card are in web/README.md, "Screens".

import { api } from "../api.js";
import { esc, LITTER, ago, icon } from "../ui.js";
import { createMap, spotMarker } from "../map.js";

export default async function render(el) {
  el.innerHTML = `<div class="map" id="map"></div>
    <div class="map-overlay card" style="left:24px;bottom:24px;min-width:280px">
      <div class="eyebrow">Litter nearby</div>
      <div><span class="big" id="open-count">…</span><span class="unit">spots to clean</span></div>
    </div>
    <button class="map-overlay locate-btn" id="locate" title="Show where I am" aria-label="Show where I am">${icon("locate", 22)}</button>`;
  const map = createMap(el.querySelector("#map"));

  // "Find me": the browser's location (needs HTTPS or localhost), as a blue dot with its accuracy circle.
  let me = null;
  const locateBtn = el.querySelector("#locate");
  locateBtn.onclick = () => {
    if (!navigator.geolocation) return (locateBtn.title = "Location is not available in this browser");
    locateBtn.classList.add("busy");
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
        locateBtn.classList.remove("busy");
        const at = [coords.latitude, coords.longitude];
        me?.remove();
        me = window.L.layerGroup([
          window.L.circle(at, { radius: coords.accuracy, color: "#2f80ed", weight: 1, fillOpacity: 0.1 }),
          window.L.circleMarker(at, { radius: 8, color: "#fff", weight: 3, fillColor: "#2f80ed", fillOpacity: 1 }),
        ]).addTo(map);
        map.flyTo(at, Math.max(map.getZoom(), 16));
      },
      (err) => {
        locateBtn.classList.remove("busy");
        locateBtn.title = `Could not get your location: ${err.message}`;
      },
      { enableHighAccuracy: true, timeout: 10000 },
    );
  };

  try {
    const open = (await api.spots()).filter((s) => !s.cleaned_at);
    el.querySelector("#open-count").textContent = open.length;
    for (const s of open) {
      spotMarker(s)
        .bindPopup(`<b>${esc(LITTER[s.litter_type] || s.litter_type)}</b><br>Spotted ${esc(ago(s.spotted_at))}`)
        .addTo(map);
    }
  } catch (err) {
    el.querySelector("#open-count").textContent = "–";
    el.querySelector(".map-overlay").insertAdjacentHTML("beforeend", `<p class="muted">${esc(err.message)}</p>`);
  }

  return () => map.remove();
}
