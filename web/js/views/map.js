// Map: open litter spots for now. Clusters, zones, Plogger / Scout and the bottom card are in web/README.md, "Screens".

import { api } from "../api.js";
import { esc, LITTER, ago } from "../ui.js";
import { createMap, spotMarker } from "../map.js";

export default async function render(el) {
  el.innerHTML = `<div class="map" id="map"></div>
    <div class="map-overlay card" style="left:24px;bottom:24px;min-width:280px">
      <div class="eyebrow">Litter nearby</div>
      <div><span class="big" id="open-count">…</span><span class="unit">spots to clean</span></div>
    </div>`;
  const map = createMap(el.querySelector("#map"));

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
