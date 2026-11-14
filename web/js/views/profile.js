// Profile: the raccoon, your totals and points, this week by day (items as a plogger, pins as a scout),
// the 500 items goal ring and badges. Totals come from your outings in the API; the rules are in game.js.

import { currentUser } from "../api.js";
import { badges, data, GOAL_ITEMS, person, points, totals, week } from "../game.js";
import { duration, esc, failed, km, loading } from "../ui.js";
import { hexBadge, ico, ring } from "../widgets.js";

const DAYS = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"];
let mode = "plogger";

export default async function render(el) {
  loading(el, "Profile");
  const me = currentUser();
  let outings;
  try {
    outings = await data.outings({ user_id: me, limit: 500 });
  } catch (err) {
    return failed(el, "Profile", err);
  }
  const p = person(me);
  const all = totals(outings);
  const days = week(outings);
  const thisWeek = totals(outings.filter((o) => o.started_at >= days[0].from));
  const list = badges(outings);

  const draw = () => {
    const value = (d) => (mode === "scout" ? d.spots_pinned : d.picks);
    const max = Math.max(1, ...days.map(value));
    el.innerHTML = `<header class="page-head"><h1>Profile</h1><button class="round-btn plain" title="Settings">${ico("settings", 22)}</button></header>
      <div class="grid grid-2 profile-grid">
        <section class="profile-hero">
          <img class="raccoon" src="assets/raccoon_idle.webp" alt="Your raccoon" width="200" height="200">
          <h2>${esc(p.name)}</h2>
          <p class="muted">${esc(p.city === "Espoo" ? "Espoo, Finland" : p.city)}</p>
          <div class="profile-stats">
            <div><b>${all.outings}</b><span class="muted small">outings</span></div>
            <div><b>${all.picks}</b><span class="muted small">items</span></div>
            <div><b>${points(outings)}</b><span class="muted small">points</span></div>
            <button class="round-btn soft" title="Add friends">${ico("addUser", 22)}</button>
          </div>
        </section>
        <section>
          <div class="section-title first">This week</div>
          <div class="card week-bars">
            <div class="mode" role="tablist">
              <button class="${mode === "plogger" ? "active" : ""}" data-mode="plogger" title="Plogger: items picked">${ico("grabber", 24)}</button>
              <button class="${mode === "scout" ? "active" : ""}" data-mode="scout" title="Scout: spots pinned">${ico("glasses", 24)}</button>
            </div>
            <div class="week-card flat">
              <div class="side">${ico("clock", 20, "green")}<b>${esc(duration(thisWeek.duration_s))}</b></div>
              <div class="center"><span class="big">${mode === "scout" ? thisWeek.spots_pinned : thisWeek.picks}</span><span class="unit">${mode === "scout" ? "pins" : "pcs"}</span></div>
              <div class="side">${ico("up", 20, "green")}<b>${km(thisWeek.distance_m)} km</b></div>
            </div>
            <div class="bars">${days
              .map(
                (d, i) => `<div class="bar-day ${d.today ? "today" : ""}" title="${value(d)}">
                  <i style="height:${Math.max(6, (value(d) / max) * 64)}px" class="${value(d) ? "" : "zero"}"></i><small>${DAYS[i]}</small></div>`,
              )
              .join("")}</div>
          </div>
          <div class="goal-row">
            ${ring(all.picks / GOAL_ITEMS, 128, `<small class="muted">GOAL</small><b>${GOAL_ITEMS}</b><small>${Math.min(all.picks, GOAL_ITEMS)} pcs</small>`)}
            <div class="badges">${list.map((b) => hexBadge(b, 52)).join("")}</div>
          </div>
        </section>
      </div>`;
    for (const b of el.querySelectorAll("[data-mode]")) {
      b.onclick = () => {
        mode = b.dataset.mode;
        draw();
      };
    }
  };
  draw();
}
