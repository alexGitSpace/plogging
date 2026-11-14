// Talks to the API (contract/README.md, "Reading") and keeps the two settings: API address and "you".

const query = new URLSearchParams(location.search);

function remembered(name, fallback) {
  const fromQuery = query.get(name);
  try {
    if (fromQuery) localStorage.setItem(`plogging.${name}`, fromQuery);
    return fromQuery || localStorage.getItem(`plogging.${name}`) || fallback;
  } catch {
    return fromQuery || fallback;
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

// Start of this week (Monday 00:00 local time) in Unix ms, for `since`.
export function weekStart(now = new Date()) {
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d.getTime();
}
