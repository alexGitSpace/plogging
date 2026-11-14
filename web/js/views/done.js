// Outing summary ("Challenge complete"): placeholder until step 6 in PLAN.md.
// The 220x220 box is where the Three.js character goes (js/character.js).

import { api } from "../api.js";
import { esc, failed, km, loading } from "../ui.js";

export default async function render(el, sessionId) {
  loading(el, "Outing done");
  let o;
  try {
    o = await api.outing(sessionId);
  } catch (err) {
    return failed(el, "Outing done", err);
  }
  el.innerHTML = `<div style="max-width:480px;margin:0 auto;text-align:center;display:grid;gap:20px">
      <div class="placeholder" style="width:220px;height:220px;margin:0 auto;display:grid;place-items:center;border-radius:50%">Character</div>
      <h1 style="font-size:32px;font-weight:800">Outing done!</h1>
      <div class="card grid grid-3">
        <div><div class="eyebrow">Spots</div><div class="big" style="font-size:28px">${o.stats.spots_cleaned}</div></div>
        <div><div class="eyebrow">Distance</div><div class="big" style="font-size:28px">${km(o.stats.distance_m)}<span class="unit">km</span></div></div>
        <div><div class="eyebrow">Time</div><div class="big" style="font-size:28px">${Math.round(o.stats.duration_s / 60)}<span class="unit">min</span></div></div>
      </div>
      <a class="btn" href="#/feed">Done</a>
      <p class="muted">${esc(o.user_id)}</p>
    </div>`;
}
