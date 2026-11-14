// Talks to the API (contract/README.md, "Reading") and keeps the two settings: API address and "you".

const query = new URLSearchParams(location.search);

// ?name=value sets a setting and remembers it in the browser; ?name=default forgets it.
function remembered(name, fallback) {
  const fromQuery = query.get(name);
  try {
    if (fromQuery === "default") localStorage.removeItem(`plogging.${name}`);
    else if (fromQuery) localStorage.setItem(`plogging.${name}`, fromQuery);
    return (fromQuery !== "default" && fromQuery) || localStorage.getItem(`plogging.${name}`) || fallback;
  } catch {
    return (fromQuery !== "default" && fromQuery) || fallback;
  }
}

export const API_URL = remembered("api", window.PLOGGING_CONFIG?.apiUrl || "http://localhost:8000").replace(/\/$/, "");

let user = remembered("user", "");

export function currentUser() {
  return user;
}

export function setCurrentUser(id) {
  user = id;
  try {
    localStorage.setItem("plogging.user", id);
  } catch {}
}

async function get(path, params = {}) {
  const url = new URL(API_URL + path);
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, v);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} on ${path}`);
  return res.json();
}

export const api = {
  spots: () => get("/api/spots").then((d) => d.spots),
  outings: ({ user_id, since, limit } = {}) => get("/api/outings", { user_id, since, limit }).then((d) => d.outings),
  outing: (id) => get(`/api/outings/${encodeURIComponent(id)}`),
  leaderboard: ({ since } = {}) => get("/api/leaderboard", { since }).then((d) => d.users),
};

// Calls fn now and then every `ms` until the returned stop() is called or fn returns false.
// Waits for each call before scheduling the next, so slow responses never pile up; after an error it waits twice as long.
// The contract asks live screens to poll every 1–2 s.
export function poll(fn, ms = 1500) {
  let stopped = false;
  let timer = null;
  async function run() {
    let delay = ms;
    try {
      if ((await fn()) === false) return;
    } catch (err) {
      console.warn("poll:", err);
      delay = ms * 2;
    }
    if (!stopped) timer = setTimeout(run, delay);
  }
  run();
  return () => {
    stopped = true;
    clearTimeout(timer);
  };
}

// Start of this week (Monday 00:00 local time) in Unix ms, for `since`.
export function weekStart(now = new Date()) {
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d.getTime();
}
