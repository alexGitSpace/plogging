// Test users and test data for the screens the API cannot fill yet (web/README.md, "What the API does not have").
// - PEOPLE: full names and neighbourhoods for the nicknames of api/sim.py.
// - FRIENDS: who "you" follow, for the Friends tab and the feed.
// - ELSEWHERE: people from other cities, so the Global tab is more than Espoo.
// - outings() / outing() / leaderboard(): the same shapes as the API, made up from a fixed seed.
//   game.js uses them when the API cannot be reached, or always with ?mock in the page address.

export const PLACES = {
  Otaniemi: [60.1841, 24.8301],
  Tapiola: [60.1756, 24.805],
  Leppävaara: [60.219, 24.813],
  Matinkylä: [60.16, 24.738],
  Niittykumpu: [60.17, 24.764],
};

// user_id → who it is. The ids are the nicknames the simulator sends.
export const PEOPLE = {
  "Anya K.": { name: "Anya Koskinen", area: "Otaniemi", role: "plogger", pace: 3 },
  "Mikko L.": { name: "Mikko Lahtinen", area: "Tapiola", role: "plogger", pace: 3 },
  "Sara V.": { name: "Sara Virtanen", area: "Otaniemi", role: "plogger", pace: 2 },
  "Juho N.": { name: "Juho Niemi", area: "Tapiola", role: "plogger", pace: 2 },
  "Liisa P.": { name: "Liisa Peltola", area: "Leppävaara", role: "plogger", pace: 2 },
  "Otto H.": { name: "Otto Hakala", area: "Matinkylä", role: "plogger", pace: 2 },
  "Emma S.": { name: "Emma Salo", area: "Niittykumpu", role: "plogger", pace: 1 },
  "Veera K.": { name: "Veera Korhonen", area: "Otaniemi", role: "scout", pace: 2 },
  "Ilkka R.": { name: "Ilkka Rantanen", area: "Otaniemi", role: "scout", pace: 1 },
  demo: { name: "Demo Plogger", area: "Otaniemi", role: "plogger", pace: 2 },
};

export const FRIENDS = ["Anya K.", "Mikko L.", "Sara V.", "Juho N.", "Veera K."];

// Other cities: only totals, as the leaderboard returns them. [user_id, name, city, items this week, km this week, items all time, km all time]
export const ELSEWHERE = [
  ["Lena M.", "Lena Mäkinen", "Helsinki", 164, 21.4, 1320, 160],
  ["Tomás R.", "Tomás Rivera", "Tampere", 131, 17.2, 940, 122],
  ["Aino S.", "Aino Saarinen", "Oulu", 92, 11.8, 610, 80],
  ["Jonas B.", "Jonas Berg", "Turku", 58, 8.1, 1480, 190],
  ["Mei T.", "Mei Tanaka", "Helsinki", 37, 5.2, 260, 33],
];

const LITTER_MIX = { cigarette_butt: 35, wrapper: 20, can: 15, bottle: 15, paper: 10, other: 5 };
const DAY = 86400000;

function rng(seed) {
  // mulberry32: the same test data on every load
  return () => {
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function hash(s) {
  let h = 2166136261;
  for (const c of String(s)) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return h >>> 0;
}

function pickType(r) {
  let x = r() * 100;
  for (const [type, w] of Object.entries(LITTER_MIX)) if ((x -= w) < 0) return type;
  return "other";
}

function walk(r, [lat, lon], points) {
  const route = [];
  let heading = r() * Math.PI * 2;
  for (let i = 0; i < points; i++) {
    route.push([+lat.toFixed(6), +lon.toFixed(6)]);
    heading += (r() - 0.5) * 0.9;
    lat += Math.cos(heading) * 0.0004;
    lon += Math.sin(heading) * 0.0008;
  }
  return route;
}

let cache = null;

// Outings of the last three weeks for everyone in PEOPLE (and "you", if you are someone else), newest first.
function all(extraUser) {
  const key = `${extraUser}|${new Date().toDateString()}`;
  if (cache?.key === key) return cache.list;
  const now = Date.now();
  const today = new Date(now).setHours(0, 0, 0, 0);
  const users = { ...PEOPLE };
  if (extraUser && !users[extraUser]) users[extraUser] = { area: "Otaniemi", role: "plogger", pace: 2 };
  const list = [];
  for (const [user_id, p] of Object.entries(users)) {
    const r = rng(hash(user_id));
    for (let d = 20; d >= 0; d--) {
      // Busier people go out more often; everyone with pace 2+ went out today, so the feed has fresh posts.
      if (!(d === 0 && p.pace >= 2) && r() > p.pace * 0.22) continue;
      const hour = 8 + Math.floor(r() * 10);
      let started = today - d * DAY + hour * 3600000 + Math.floor(r() * 60) * 60000;
      const minutes = 20 + Math.floor(r() * 45);
      if (started + minutes * 60000 > now) started = now - minutes * 60000 - 30 * 60000;
      const scout = p.role === "scout";
      const picks = scout ? Math.floor(r() * 4) : 6 + Math.floor(r() * 30);
      const litter = {};
      let cleaned = 0;
      for (let i = 0; i < picks; i++) {
        if (r() < 0.35) continue; // a pick that cleaned no spot
        const t = pickType(r);
        litter[t] = (litter[t] || 0) + 1;
        cleaned++;
      }
      const distance = Math.round(minutes * 60 * (0.9 + r() * 0.5));
      const id = `mock-${hash(user_id + d + hour).toString(16)}`;
      list.push({
        session_id: id,
        user_id,
        role: p.role,
        started_at: started,
        ended_at: started + minutes * 60000,
        live: false,
        stats: {
          duration_s: minutes * 60,
          distance_m: distance,
          picks,
          spots_cleaned: cleaned,
          spots_pinned: scout ? 3 + Math.floor(r() * 8) : Math.floor(r() * 2),
          litter,
        },
        route: walk(r, PLACES[p.area] || PLACES.Otaniemi, 24 + Math.floor(r() * 16)),
      });
    }
  }
  list.sort((a, b) => b.started_at - a.started_at);
  cache = { key, list };
  return list;
}

const card = ({ route, ...summary }) => summary;

export function outings({ user_id, since, limit = 50 } = {}, you) {
  return all(you)
    .filter((o) => (!user_id || o.user_id === user_id) && (!since || o.started_at >= since))
    .slice(0, limit)
    .map(card);
}

export function outing(id, you) {
  const o = all(you).find((x) => x.session_id === id);
  if (!o) throw new Error(`No test outing ${id}`);
  return { ...o, picks: [], pinned: [] };
}

export function leaderboard({ since } = {}, you) {
  const by = {};
  for (const o of all(you)) {
    if (since && o.started_at < since) continue;
    const u = (by[o.user_id] ??= { user_id: o.user_id, outings: 0, stats: { duration_s: 0, distance_m: 0, picks: 0, spots_cleaned: 0, spots_pinned: 0, litter: {} } });
    u.outings++;
    for (const k of ["duration_s", "distance_m", "picks", "spots_cleaned", "spots_pinned"]) u.stats[k] += o.stats[k];
  }
  return Object.values(by)
    .sort((a, b) => b.stats.picks - a.stats.picks || b.stats.distance_m - a.stats.distance_m)
    .map((u, i) => ({ rank: i + 1, ...u }));
}
