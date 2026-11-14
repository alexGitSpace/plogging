// Feed: your week, the "Clean Otaniemi" challenge and friends' outings with a mini map of the route.
// Outings, routes and your items this month come from the API; names, places, likes and "Join" are test data (game.js, mock.js).

import { currentUser, weekStart } from "../api.js";
import { data, isFriend, monthStart, person, totals } from "../game.js";
import { hash } from "../mock.js";
import { duration, esc, failed, km, loading } from "../ui.js";
import { avatar, bar, ico, litterStrip, miniRoute } from "../widgets.js";

const CHALLENGE = { id: "clean-otaniemi", title: "Clean Otaniemi", text: "Pick up 500 items in November", target: 500 };

function stored(key, fallback) {
  try {
    return JSON.parse(localStorage.getItem(key)) ?? fallback;
  } catch {
    return fallback;
  }
}

function store(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {}
}

function when(t) {
  const d = new Date(t);
  const today = new Date().setHours(0, 0, 0, 0);
  const day = t >= today ? "Today" : t >= today - 86400000 ? "Yesterday" : d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short" });
  return `${day}, ${d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}`;
}

function post(o) {
  const p = person(o.user_id);
  const likesKey = `plogging.like.${o.session_id}`;
  const liked = stored(likesKey, false);
  const likes = (hash(o.session_id) % 9) + (liked ? 1 : 0);
  return `<article class="card post" data-id="${esc(o.session_id)}">
    <header class="post-head">
      ${avatar(p.name, 48)}
      <div class="grow"><b>${esc(p.name)}</b><div class="muted small">${esc(when(o.started_at))} · ${esc(p.area)}</div></div>
      <span class="pill">${o.role === "scout" ? "Scout" : "Plogger"}</span>
    </header>
    <div class="post-map">${miniRoute(o.route)}</div>
    <div class="post-stats">
      <div><b>${o.stats.picks}</b><span class="unit">pcs</span><div class="muted small">items</div></div>
      <div><b>${km(o.stats.distance_m)}</b><span class="unit">km</span><div class="muted small">distance</div></div>
      <div><b>${Math.round(o.stats.duration_s / 60)}</b><span class="unit">min</span><div class="muted small">time</div></div>
    </div>
    ${litterStrip(o.stats.litter)}
    <footer class="post-foot">
      <button class="like ${liked ? "on" : ""}" data-like="${esc(o.session_id)}" aria-pressed="${liked}">${ico("heart", 20)}<span>${likes}</span></button>
      <span class="muted small">${o.stats.spots_cleaned} spots cleaned${o.stats.spots_pinned ? ` · ${o.stats.spots_pinned} pinned` : ""}</span>
    </footer>
  </article>`;
}

export default async function render(el) {
  loading(el, "Feed");
  const me = currentUser();
  let mine, recent, month;
  try {
    [mine, recent, month] = await Promise.all([
      data.outings({ user_id: me, since: weekStart() }),
      data.outings({ limit: 40 }),
      data.leaderboard({ since: monthStart() }),
    ]);
  } catch (err) {
    return failed(el, "Feed", err);
  }
  const week = totals(mine);
  const others = recent.filter((o) => o.user_id !== me && !o.live);
  const friends = others.filter((o) => isFriend(o.user_id));
  const posts = (friends.length ? friends : others).slice(0, 8);
  const monthPicks = month.find((u) => u.user_id === me)?.stats.picks || 0;
  const joinKey = `plogging.joined.${CHALLENGE.id}.${me}`;

  el.innerHTML = `<header class="page-head"><h1>Feed</h1><button class="round-btn" title="Notifications">${ico("bell", 20)}<i class="dot"></i></button></header>
    <div class="grid grid-2 feed-top">
      <section>
        <div class="section-title">This week</div>
        <div class="card week-card">
          <div class="side">${ico("clock", 20, "green")}<b>${esc(duration(week.duration_s))}</b></div>
          <div class="center"><span class="big">${week.picks}</span><span class="unit">pcs</span><div class="muted small">items</div></div>
          <div class="side">${ico("up", 20, "green")}<b>${km(week.distance_m)} km</b></div>
        </div>
      </section>
      <section>
        <div class="section-title">Challenge</div>
        <div class="challenge" id="challenge"></div>
      </section>
    </div>
    <div class="section-title">${friends.length ? "Friends" : "Recent outings"}</div>
    <div class="grid grid-2 posts">${posts.map(post).join("") || `<div class="placeholder">No outings yet. Start one on the Map.</div>`}</div>`;

  const challengeEl = el.querySelector("#challenge");
  const drawChallenge = () => {
    const joined = stored(joinKey, false);
    const end = new Date(monthStart());
    end.setMonth(end.getMonth() + 1, 0);
    challengeEl.innerHTML = `<h2>${esc(CHALLENGE.title)}</h2>
      <p>${ico("grabber", 18)} ${esc(CHALLENGE.text)}</p>
      ${joined ? `${bar(monthPicks / CHALLENGE.target, "on-green")}<p class="small">${monthPicks} / ${CHALLENGE.target} items this month</p>` : ""}
      <div class="challenge-foot">
        <span class="tag">${new Date().toLocaleDateString("en-GB", { month: "short" })} 1 – ${end.getDate()}</span>
        <button class="btn white" id="join">${joined ? `${ico("check", 18)} Joined` : "Join"}</button>
      </div>`;
    challengeEl.querySelector("#join").onclick = () => {
      store(joinKey, !joined);
      drawChallenge();
    };
  };
  drawChallenge();

  el.querySelector(".posts").addEventListener("click", (e) => {
    const btn = e.target.closest("[data-like]");
    if (!btn) return;
    const key = `plogging.like.${btn.dataset.like}`;
    const on = !stored(key, false);
    store(key, on);
    btn.classList.toggle("on", on);
    btn.setAttribute("aria-pressed", on);
    const n = btn.querySelector("span");
    n.textContent = Number(n.textContent) + (on ? 1 : -1);
  });

  // Routes are not in the outings list: fetch each post's outing and draw its mini map.
  for (const o of posts) {
    data
      .outing(o.session_id)
      .then((full) => {
        const box = el.querySelector(`.post[data-id="${CSS.escape(o.session_id)}"] .post-map`);
        if (box) box.innerHTML = miniRoute(full.route);
      })
      .catch(() => {});
  }
}
