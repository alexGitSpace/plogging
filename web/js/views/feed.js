// Feed: placeholder; what goes here is in web/README.md, "Screens".

import { api, currentUser, weekStart } from "../api.js";
import { esc, failed, loading } from "../ui.js";

export default async function render(el) {
  loading(el, "Feed");
  let outings;
  try {
    outings = await api.outings({ user_id: currentUser(), since: weekStart() });
  } catch (err) {
    return failed(el, "Feed", err);
  }
  const picks = outings.reduce((n, o) => n + o.stats.picks, 0);
  el.innerHTML = `<header class="page-head"><h1>Feed</h1></header>
    <p class="muted" style="margin-bottom:20px">${outings.length} outings and ${picks} items this week for ${esc(currentUser())}</p>
    <div class="placeholder">This week, the Clean Otaniemi challenge and other people's outings with a mini map.</div>`;
}
