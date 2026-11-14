// The shell: side navigation, "you" selector and a router on location.hash.
// Each view is a module with a default export render(el, param) that may return a cleanup function.

import { api, API_URL, currentUser, setCurrentUser } from "./api.js";
import { esc, icon } from "./ui.js";
import { PEOPLE } from "./mock.js";
import * as recorder from "./recorder.js"; // Loaded here so an outing keeps recording on every screen

const TABS = [
  ["feed", "Feed"],
  ["quests", "Quests"],
  ["map", "Map"],
  ["leaders", "Leaders"],
  ["profile", "Profile"],
];

const VIEWS = {
  feed: () => import("./views/feed.js"),
  quests: () => import("./views/quests.js"),
  map: () => import("./views/map.js"),
  leaders: () => import("./views/leaders.js"),
  profile: () => import("./views/profile.js"),
  done: () => import("./views/done.js"),
};

const viewEl = document.getElementById("view");
const navEl = document.getElementById("nav");
let cleanup = null;
let routeId = 0;

navEl.innerHTML = TABS.map(([id, label]) => `<a href="#/${id}" data-tab="${id}">${icon(id)}<span>${label}</span></a>`).join("");
// A red dot on Map while an outing is being recorded in this browser.
recorder.subscribe(() => navEl.querySelector('[data-tab="map"]').classList.toggle("recording", !!recorder.current()));

async function route() {
  const [name, param] = location.hash.replace(/^#\/?/, "").split("/");
  const view = VIEWS[name] ? name : "map";
  const tab = view === "done" ? "map" : view;
  for (const a of navEl.querySelectorAll("a")) a.classList.toggle("active", a.dataset.tab === tab);

  const id = ++routeId;
  if (typeof cleanup === "function") cleanup();
  cleanup = null;
  viewEl.className = `view view-${view}`;
  // Each view gets its own element, so a view still loading after we left it writes into a detached node.
  const host = document.createElement("div");
  host.className = "view-host";
  viewEl.replaceChildren(host);
  const mod = await VIEWS[view]();
  if (id !== routeId) return; // a newer navigation won
  const done = await mod.default(host, param ? decodeURIComponent(param) : undefined);
  if (id !== routeId) {
    if (typeof done === "function") done(); // left the view while it was loading
    return;
  }
  cleanup = done;
}

async function initUsers() {
  const select = document.getElementById("user-select");
  const status = document.getElementById("api-status");
  status.title = API_URL;
  try {
    const users = await api.leaderboard();
    status.classList.add("ok");
    const ids = users.map((u) => u.user_id);
    if (!currentUser() && ids.length) setCurrentUser(ids[0]);
    if (currentUser() && !ids.includes(currentUser())) ids.unshift(currentUser());
    select.innerHTML = ids.map((id) => `<option${id === currentUser() ? " selected" : ""}>${esc(id)}</option>`).join("");
  } catch (err) {
    status.classList.add("down");
    status.querySelector("span").textContent = "API unreachable · test data";
    // Feed, Quests, Leaders and Profile fall back to the test users of mock.js (game.js), so offer them here.
    const ids = Object.keys(PEOPLE);
    if (!currentUser()) setCurrentUser(ids[0]);
    if (!ids.includes(currentUser())) ids.unshift(currentUser());
    select.innerHTML = ids.map((id) => `<option${id === currentUser() ? " selected" : ""}>${esc(id)}</option>`).join("");
  }
  select.addEventListener("change", () => {
    setCurrentUser(select.value);
    route();
  });
}

window.addEventListener("hashchange", route);
initUsers().then(route);
