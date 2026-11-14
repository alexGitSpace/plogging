// Quests: the day streak, today's quests with progress and Claim, and the weekly quest.
// Progress comes from your outings in the API; claimed rewards stay in this browser (game.js).

import { currentUser } from "../api.js";
import { claim, dailyQuests, data, dayStart, DAY, streak, week, weeklyQuest } from "../game.js";
import { esc, failed, km, loading } from "../ui.js";
import { bar, hexBadge, ico } from "../widgets.js";

const LETTERS = ["M", "T", "W", "T", "F", "S", "S"];

function untilMidnight() {
  const left = dayStart(Date.now() + DAY + 3600000) - Date.now();
  const h = Math.floor(left / 3600000);
  const m = Math.floor((left % 3600000) / 60000);
  return `${h}h ${m}m`;
}

function amount(q, v) {
  return q.unit === "km" ? km(v) : String(v);
}

function questCard(q) {
  const status = q.claimed ? "claimed" : q.complete ? "ready to claim" : q.note || "";
  const right = q.claimed
    ? `<span class="pts done">${ico("check", 16)} +${q.pts} pts</span>`
    : q.complete
      ? `<button class="btn small" data-claim="${esc(q.key)}" data-pts="${q.pts}">Claim</button>`
      : `<span class="pts">+${q.pts} pts</span>`;
  return `<div class="card quest ${q.claimed ? "is-claimed" : ""}">
    <div class="quest-icon">${ico(q.icon, 26)}</div>
    <div class="grow">
      <div class="quest-head"><b>${esc(q.title)}</b>${right}</div>
      ${bar(q.progress)}
      <div class="muted small">${amount(q, Math.min(q.value, q.target))} / ${amount(q, q.target)}${q.unit ? ` ${q.unit}` : ""}${status ? ` · ${esc(status)}` : ""}</div>
    </div>
  </div>`;
}

export default async function render(el) {
  loading(el, "Quests");
  let outings;
  try {
    outings = await data.outings({ user_id: currentUser(), limit: 500 });
  } catch (err) {
    return failed(el, "Quests", err);
  }

  const draw = () => {
    const days = week(outings);
    const n = streak(outings);
    const weekly = weeklyQuest(outings);
    el.innerHTML = `<header class="page-head"><h1>Quests</h1><span class="chip" title="New daily quests in">${ico("clock", 18, "green")}<b id="left">${untilMidnight()}</b></span></header>
      <div class="grid grid-2 quests-grid">
        <section>
          <div class="card streak">
            <div class="streak-n">${ico("flame", 30, "green")}<div><span class="big">${n}</span><div class="muted small">day streak</div></div></div>
            <div class="streak-days">${days
              .map(
                (d, i) => `<div class="sday"><span class="dot-day ${d.outings ? "on" : ""} ${d.today ? "today" : ""} ${d.future ? "future" : ""}">${d.outings ? ico("check", 16) : ""}</span>
                  <small class="${d.today ? "green" : ""}">${LETTERS[i]}</small></div>`,
              )
              .join("")}</div>
          </div>
          <div class="section-title">Weekly</div>
          <div class="weekly">
            <div class="grow">
              <h2>${esc(weekly.title)}</h2>
              <p>${esc(weekly.text)}</p>
              ${bar(weekly.progress, "on-green")}
              <p class="small"><b>${Math.min(weekly.value, weekly.target)} / ${weekly.target} days · ${weekly.daysLeft} days left</b></p>
              ${
                weekly.claimed
                  ? `<span class="tag">${ico("check", 14)} +${weekly.pts} pts claimed</span>`
                  : weekly.complete
                    ? `<button class="btn white small" data-claim="${esc(weekly.key)}" data-pts="${weekly.pts}">Claim +${weekly.pts} pts</button>`
                    : `<span class="tag">+${weekly.pts} pts</span>`
              }
            </div>
            ${hexBadge({ id: "eye", title: weekly.title, text: weekly.text, earned: true }, 76)}
          </div>
        </section>
        <section>
          <div class="section-title first">Daily</div>
          <div class="quests">${dailyQuests(outings).map(questCard).join("")}</div>
        </section>
      </div>`;
  };
  draw();

  el.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-claim]");
    if (!btn) return;
    claim(btn.dataset.claim, Number(btn.dataset.pts));
    draw();
  });

  const timer = setInterval(() => {
    const left = el.querySelector("#left");
    if (left) left.textContent = untilMidnight();
  }, 30000);
  return () => clearInterval(timer);
}
