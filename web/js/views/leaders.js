// Leaderboard: placeholder; what goes here is in web/README.md, "Screens".

import { api, currentUser, weekStart } from "../api.js";
import { failed, loading } from "../ui.js";

export default async function render(el) {
  loading(el, "Leaderboard");
  let users;
  try {
    users = await api.leaderboard({ since: weekStart() });
  } catch (err) {
    return failed(el, "Leaderboard", err);
  }
  const me = users.find((u) => u.user_id === currentUser());
  el.innerHTML = `<header class="page-head"><h1>Leaderboard</h1></header>
    <p class="muted" style="margin-bottom:20px">${users.length} people this week${me ? `, you are #${me.rank}` : ""}</p>
    <div class="placeholder">This week / All time, the podium, "You're #N" and the list.</div>`;
}
