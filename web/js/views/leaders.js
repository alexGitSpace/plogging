// Leaderboard: This week / All time, Friends / Espoo / Global, the podium, "You're #N" and the list.
// The ranking is the API's; friends, cities, the other cities' people and the up/down arrows are test data (game.js).

import { currentUser, weekStart } from "../api.js";
import { board, SCOPES } from "../game.js";
import { esc, failed, km, loading } from "../ui.js";
import { avatar, ico } from "../widgets.js";

let scope = "Espoo";
let period = "week";

function short(u) {
  return u.name.split(" ")[0] + (u.name.includes(" ") ? ` ${u.name.split(" ")[1][0]}.` : "");
}

function podium(top, me) {
  const order = [top[1], top[0], top[2]].filter(Boolean);
  return `<div class="podium">${order
    .map(
      (u) => `<div class="podium-place p${u.rank}">
        ${u.rank === 1 ? ico("crown", 26, "green crown") : ""}
        <div class="podium-ava">${avatar(u.name, u.rank === 1 ? 92 : 68, u.rank === 1 ? "first" : "")}<span class="rank-badge ${u.rank === 1 ? "green-bg" : ""}">${u.rank}</span></div>
        <b>${u.user_id === me ? "You" : esc(short(u))}</b>
        <div><span class="${u.rank === 1 ? "green" : ""} podium-n">${u.picks}</span><span class="unit muted">pcs</span></div>
      </div>`,
    )
    .join("")}</div>`;
}

function row(u, me) {
  const isMe = u.user_id === me;
  return `<li class="lb-row ${isMe ? "me" : ""}">
    <span class="lb-rank ${isMe ? "green" : ""}">${u.rank}</span>
    ${avatar(u.name, 44)}
    <div class="grow"><b>${isMe ? "You" : esc(u.name)}</b><div class="muted small">${esc(u.area)}</div></div>
    <div class="lb-n"><b>${u.picks}</b><span class="unit muted">pcs</span><div class="muted small">${km(u.distance_m)} km</div></div>
    <span class="trend ${u.trend}">${u.trend === "up" ? ico("chevUp", 18) : u.trend === "down" ? ico("chevDown", 18) : ""}</span>
  </li>`;
}

export default async function render(el) {
  loading(el, "Leaderboard");
  const me = currentUser();

  const draw = async () => {
    let users;
    try {
      users = await board(scope, period === "week" ? weekStart() : undefined);
    } catch (err) {
      return failed(el, "Leaderboard", err);
    }
    const you = users.find((u) => u.user_id === me);
    const ahead = you && users[you.rank - 2];
    const where = scope === "Friends" ? "among friends" : `in ${scope}`;
    const toPass = ahead ? ahead.picks - you.picks + 1 : 0;
    el.innerHTML = `<header class="page-head"><h1>Leaderboard</h1>
        <button class="chip" id="period">${ico("chevDown", 16)}<b>${period === "week" ? "This week" : "All time"}</b></button></header>
      <div class="tabs" role="tablist">${SCOPES.map((s) => `<button role="tab" class="${s === scope ? "active" : ""}" data-scope="${s}">${s}</button>`).join("")}</div>
      <div class="grid grid-2 lb-grid">
        <section>
          ${podium(users.slice(0, 3), me)}
          ${
            you
              ? `<div class="you-card">${ico("up", 28, "green")}<div><b>You're #${you.rank} ${esc(where)}</b>
                  <div class="muted small">${ahead ? `${toPass} more item${toPass === 1 ? "" : "s"} to pass ${esc(ahead.name.split(" ")[0])}` : "You're on top. Keep it up!"}</div></div></div>`
              : ""
          }
        </section>
        <ol class="lb-list">${users.slice(3).map((u) => row(u, me)).join("") || `<li class="muted">Only ${users.length} here so far.</li>`}</ol>
      </div>`;
    el.querySelector("#period").onclick = () => {
      period = period === "week" ? "all" : "week";
      draw();
    };
    for (const b of el.querySelectorAll("[data-scope]")) {
      b.onclick = () => {
        scope = b.dataset.scope;
        draw();
      };
    }
  };
  await draw();
}
