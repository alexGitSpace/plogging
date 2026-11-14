// Quests: placeholder until step 5 in PLAN.md.

import { api, currentUser } from "../api.js";
import { esc, failed, loading } from "../ui.js";

export default async function render(el) {
  loading(el, "Quests");
  let outings;
  try {
    outings = await api.outings({ user_id: currentUser(), limit: 500 });
  } catch (err) {
    return failed(el, "Quests", err);
  }
  el.innerHTML = `<header class="page-head"><h1>Quests</h1></header>
    <p class="muted" style="margin-bottom:20px">${outings.length} outings of ${esc(currentUser())} to compute quests from</p>
    <div class="placeholder">Day streak, daily quests with progress and the weekly quest.</div>`;
}
