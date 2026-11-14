// Outing summary ("Challenge complete"): the raccoon's victory jump (raccoon.js, Three.js) and the outing's stats.
// What else goes here is in web/README.md, "Screens".

import { api } from "../api.js";
import { esc, failed, km, loading } from "../ui.js";
import { mountRaccoon } from "../raccoon.js";

export default async function render(el, sessionId) {
  loading(el, "Outing done");
  let o;
  try {
    o = await api.outing(sessionId);
  } catch (err) {
    return failed(el, "Outing done", err);
  }
  el.innerHTML = `<div style="max-width:480px;margin:0 auto;text-align:center;display:grid;gap:20px">
      <div class="raccoon-stage" id="raccoon" title="Tap the raccoon"></div>
      <h1 style="font-size:32px;font-weight:800">Outing done!</h1>
      <div class="card grid grid-3">
        <div><div class="eyebrow">Spots</div><div class="big" style="font-size:28px">${o.stats.spots_cleaned}</div></div>
        <div><div class="eyebrow">Distance</div><div class="big" style="font-size:28px">${km(o.stats.distance_m)}<span class="unit">km</span></div></div>
        <div><div class="eyebrow">Time</div><div class="big" style="font-size:28px">${Math.round(o.stats.duration_s / 60)}<span class="unit">min</span></div></div>
      </div>
      <a class="btn" href="#/feed">Done</a>
      <p class="muted">${esc(o.user_id)}</p>
    </div>`;

  // The 3D raccoon loads in the background; without WebGL the animated picture stands in.
  const stage = el.querySelector("#raccoon");
  let stop = null;
  let left = false;
  mountRaccoon(stage, { win: true })
    .then((s) => (left ? s() : (stop = s)))
    .catch((err) => {
      console.warn("raccoon:", err);
      stage.innerHTML = `<img src="assets/raccoon_idle.webp" alt="" style="width:100%;height:100%;object-fit:contain">`;
    });
  return () => {
    left = true;
    stop?.();
  };
}
