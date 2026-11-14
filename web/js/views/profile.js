// Profile: placeholder; what goes here is in web/README.md, "Screens".

import { api, currentUser } from "../api.js";
import { esc, failed, initials, loading } from "../ui.js";

export default async function render(el) {
  loading(el, "Profile");
  let outings;
  try {
    outings = await api.outings({ user_id: currentUser(), limit: 500 });
  } catch (err) {
    return failed(el, "Profile", err);
  }
  const picks = outings.reduce((n, o) => n + o.stats.picks, 0);
  el.innerHTML = `<header class="page-head"><h1>Profile</h1></header>
    <div style="display:flex;align-items:center;gap:16px;margin-bottom:20px">
      <span class="avatar">${esc(initials(currentUser()))}</span>
      <div><h2>${esc(currentUser())}</h2><p class="muted">${outings.length} outings · ${picks} items</p></div>
    </div>
    <div class="placeholder">The week by day, the 500 goal ring, badges and the character.</div>`;
}
