// Game rules, in one place so every screen agrees (web/README.md, "Conventions"): points, streaks, quests,
// badges and the leaderboard scopes. Everything is computed from the API's outings and leaderboard.
// What the API does not know (names, friends, cities, claimed quests) comes from mock.js and the browser.

import { api, currentUser, weekStart } from "./api.js";
import * as mock from "./mock.js";

export const DAY = 86400000;
export const POINTS_PER_ITEM = 10;
export const POINTS_PER_PIN = 5;
export const GOAL_ITEMS = 500;

// ---- Data: the API, or the test data when the API is down or the address has ?mock ----

const forceMock = new URLSearchParams(location.search).has("mock");
export let usingMock = forceMock;

async function either(fromApi, fromMock) {
  if (!forceMock) {
    try {
      return await fromApi();
    } catch (err) {
      console.warn("API unreachable, showing test data:", err.message);
    }
  }
  usingMock = true;
  return fromMock();
}

export const data = {
  outings: (opts = {}) => either(() => api.outings(opts), () => mock.outings(opts, currentUser())),
  outing: (id) =>
    id.startsWith("mock-") ? Promise.resolve(mock.outing(id, currentUser())) : either(() => api.outing(id), () => mock.outing(id, currentUser())),
  leaderboard: (opts = {}) => either(() => api.leaderboard(opts), () => mock.leaderboard(opts, currentUser())),
};

// ---- People ----

export function person(id) {
  const p = mock.PEOPLE[id];
  const other = mock.ELSEWHERE.find((e) => e[0] === id);
  if (p) return { id, name: p.name, area: p.area, city: "Espoo", role: p.role };
  if (other) return { id, name: other[1], area: other[2], city: other[2], role: "plogger" };
  return { id, name: id || "You", area: "Espoo", city: "Espoo", role: "plogger" };
}

export const isFriend = (id) => mock.FRIENDS.includes(id);

// ---- Time ----

export function dayStart(t = Date.now()) {
  return new Date(t).setHours(0, 0, 0, 0);
}

export function monthStart(t = Date.now()) {
  const d = new Date(t);
  return new Date(d.getFullYear(), d.getMonth(), 1).getTime();
}

const dayKey = (t) => new Date(t).toLocaleDateString("sv"); // 2026-11-14

// ---- Totals, days and streaks ----

export function totals(outings) {
  const t = { outings: outings.length, duration_s: 0, distance_m: 0, picks: 0, spots_cleaned: 0, spots_pinned: 0, litter: {} };
  for (const o of outings) {
    for (const k of ["duration_s", "distance_m", "picks", "spots_cleaned", "spots_pinned"]) t[k] += o.stats[k] || 0;
    for (const [type, n] of Object.entries(o.stats.litter || {})) t.litter[type] = (t.litter[type] || 0) + n;
  }
  return t;
}

// Totals for each of the 7 days from Monday of this week.
export function week(outings, now = Date.now()) {
  const start = weekStart(new Date(now));
  return Array.from({ length: 7 }, (_, i) => {
    const from = start + i * DAY;
    // Days are 23 or 25 h long when the clock changes, so find the next midnight rather than adding 24 h.
    const to = dayStart(from + DAY + 3600000);
    return { from, today: now >= from && now < to, future: from > now, ...totals(outings.filter((o) => o.started_at >= from && o.started_at < to)) };
  });
}

// Days in a row with an outing, up to today. A streak survives until the end of today without an outing.
export function streak(outings, now = Date.now()) {
  const days = new Set(outings.map((o) => dayKey(o.started_at)));
  let t = dayStart(now);
  if (!days.has(dayKey(t))) t = dayStart(t - DAY / 2);
  let n = 0;
  while (days.has(dayKey(t))) {
    n++;
    t = dayStart(t - DAY / 2);
  }
  return n;
}

// ---- Quests ----

export const DAILY = [
  { id: "clean-3", title: "Clean 3 litter spots", icon: "pin", target: 3, pts: 50, value: (t) => t.spots_cleaned },
  { id: "items-15", title: "Pick up 15 items", icon: "grabber", target: 15, pts: 50, value: (t) => t.picks },
  { id: "km-2", title: "Plog for 2 km", icon: "route", target: 2000, pts: 40, value: (t) => t.distance_m, unit: "km" },
  { id: "butts-5", title: "Pick 5 cigarette butts", icon: "butt", target: 5, pts: 30, value: (t) => t.litter.cigarette_butt || 0 },
  { id: "pin-3", title: "Pin 3 litter spots", icon: "glasses", target: 3, pts: 30, value: (t) => t.spots_pinned, note: "Scout" },
];

export const WEEKLY = { id: "days-3", title: "Three-day trail", text: "Go plogging on 3 different days", target: 3, pts: 150 };

function claimsKey(user) {
  return `plogging.claims.${user}`;
}

export function claims(user = currentUser()) {
  try {
    return JSON.parse(localStorage.getItem(claimsKey(user))) || {};
  } catch {
    return {};
  }
}

export function claim(questKey, pts, user = currentUser()) {
  const c = claims(user);
  c[questKey] = pts;
  try {
    localStorage.setItem(claimsKey(user), JSON.stringify(c));
  } catch {}
}

export function claimedPoints(user = currentUser()) {
  return Object.values(claims(user)).reduce((a, b) => a + b, 0);
}

// Today's quests with progress. key is what claim() stores, so a quest can be claimed once a day.
export function dailyQuests(outings, now = Date.now()) {
  const today = totals(outings.filter((o) => o.started_at >= dayStart(now)));
  const done = claims();
  return DAILY.map((q) => {
    const key = `${dayKey(now)}:${q.id}`;
    const value = q.value(today);
    return { ...q, key, value, progress: Math.min(1, value / q.target), complete: value >= q.target, claimed: key in done };
  });
}

export function weeklyQuest(outings, now = Date.now()) {
  const from = weekStart(new Date(now));
  const days = new Set(outings.filter((o) => o.started_at >= from).map((o) => dayKey(o.started_at))).size;
  const key = `${dayKey(from)}:${WEEKLY.id}`;
  const left = Math.max(0, Math.ceil((from + 7 * DAY - now) / DAY));
  return { ...WEEKLY, key, value: days, progress: Math.min(1, days / WEEKLY.target), complete: days >= WEEKLY.target, claimed: key in claims(), daysLeft: left };
}

// ---- Points and badges ----

export function points(outings, user = currentUser()) {
  const t = totals(outings);
  return t.picks * POINTS_PER_ITEM + t.spots_pinned * POINTS_PER_PIN + claimedPoints(user);
}

export function badges(outings) {
  const t = totals(outings);
  const s = streak(outings);
  return [
    { id: "first", title: "First steps", text: "Go on your first outing", earned: t.outings >= 1 },
    { id: "trail", title: "Trailblazer", text: "Plog 10 km in total", earned: t.distance_m >= 10000 },
    { id: "eye", title: "Sharp eye", text: "Pin 10 litter spots", earned: t.spots_pinned >= 10 },
    { id: "hundred", title: "Century", text: "Pick up 100 items", earned: t.picks >= 100 },
    { id: "fire", title: "On fire", text: "Keep a 5-day streak", earned: s >= 5 },
  ];
}

// ---- Leaderboard ----

export const SCOPES = ["Friends", "Espoo", "Global"];

// Ranked users of one scope. Global adds the people from other cities (mock.ELSEWHERE); "you" are always in the list.
export async function board(scope, since) {
  const me = currentUser();
  let users = (await data.leaderboard({ since })).map((u) => ({ user_id: u.user_id, picks: u.stats.picks, distance_m: u.stats.distance_m }));
  if (scope === "Global") {
    const weekly = since !== undefined;
    for (const [id, , , wItems, wKm, aItems, aKm] of mock.ELSEWHERE) {
      users.push({ user_id: id, picks: weekly ? wItems : aItems, distance_m: (weekly ? wKm : aKm) * 1000 });
    }
  }
  if (scope === "Friends") users = users.filter((u) => isFriend(u.user_id) || u.user_id === me);
  if (scope === "Espoo") users = users.filter((u) => person(u.user_id).city === "Espoo");
  if (me && !users.some((u) => u.user_id === me)) users.push({ user_id: me, picks: 0, distance_m: 0 });
  users.sort((a, b) => b.picks - a.picks || b.distance_m - a.distance_m);
  return users.map((u, i) => ({ ...u, rank: i + 1, ...person(u.user_id), trend: trend(u.user_id, since) }));
}

// Up or down since last time: the API keeps no history of ranks, so this is made up, but stable.
function trend(id, since) {
  return ["up", "down", "up", ""][mock.hash(`${id}${since ?? ""}`) % 4];
}
