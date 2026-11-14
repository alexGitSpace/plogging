// Records an outing in this browser and sends it as events (contract/README.md, "Sending events"):
// session.start, then track.points every few seconds, then session.end. It follows the API's /record page
// (api/app/record.html), so the map can record in place instead of sending people to another page.
// Events wait in localStorage until the API accepts them and keep their event_id when resent, so a dropped
// connection loses nothing, and a reload carries the outing on.
// Pause is the app's own: the timer stops and no track is recorded. The contract has no pause event, so the
// API's duration_s still counts the pause.
// ?fake in the page address makes up a walk around Otaniemi instead of reading the GPS, to try it indoors.

import { API_URL } from "./api.js";

const FLUSH_MS = 5000; // Track points go out in one event this often
const MAX_BATCH = 100; // Events per request, as in the contract
const MAX_ACCURACY_M = 30; // Same distance rule as the API (api/app/outings.py): inaccurate points do not count...
const STEP_M = 10; // ...and the distance grows only after moving this far from the last counted point
const DEVICES = ["grabber-1", "glasses-1"]; // The demo's grabber and glasses
const FAKE = new URLSearchParams(location.search).has("fake");

const store = {
  get(key, fallback) {
    try {
      const v = localStorage.getItem(`plogging.rec.${key}`);
      return v === null ? fallback : JSON.parse(v);
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(`plogging.rec.${key}`, JSON.stringify(value));
    } catch {}
  },
  remove(key) {
    try {
      localStorage.removeItem(`plogging.rec.${key}`);
    } catch {}
  },
};

function uuid() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

function metres(a, b) {
  const r = Math.PI / 180;
  const h = Math.sin(((b.lat - a.lat) * r) / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(((b.lon - a.lon) * r) / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.sqrt(h));
}

const deviceId = store.get("device", null) || (() => {
  const id = `phone-${uuid().slice(0, 8)}`;
  store.set("device", id);
  return id;
})();

// session: { session_id, user_id, role, device_ids, started_at, paused_at, paused_ms, distance, anchor, track }
// track is a list of segments, one per stretch between pauses, each a list of [lat, lon] for the map.
let session = store.get("session", null);
let queue = store.get("queue", []); // Events the API has not accepted yet
let pending = session ? store.get("pending", []) : []; // Track points not yet put into an event
let lastFix = null; // { lat, lon, accuracy, t }: the latest fix, accurate or not, for the blue dot
let gpsError = null;
let offline = false;
let watchId = null;
let fakeTimer = null;
let wakeLock = null;
let inflight = null;
const listeners = new Set();

function changed() {
  for (const fn of listeners) fn();
}

// Calls fn now and on every change; returns a function that stops it.
export function subscribe(fn) {
  listeners.add(fn);
  fn();
  return () => listeners.delete(fn);
}

// The outing being recorded in this browser, or null.
export function current() {
  if (!session) return null;
  const now = session.paused_at ?? Date.now();
  return {
    session_id: session.session_id,
    user_id: session.user_id,
    paused: !!session.paused_at,
    elapsed_s: Math.max(0, Math.floor((now - session.started_at - session.paused_ms) / 1000)),
    distance_m: session.distance,
    track: session.track,
    fix: lastFix,
    gpsError,
    offline: offline && queue.length > 0,
  };
}

function save() {
  store.set("session", session);
}

function enqueue(type, fields) {
  queue.push({ event_id: uuid(), type, device_id: deviceId, t: Date.now(), ...fields });
  store.set("queue", queue);
}

// Sends the queue; one request at a time, and events queued meanwhile go out in the same run.
function send() {
  inflight ||= post().finally(() => {
    inflight = null;
  });
  return inflight;
}

async function post() {
  while (queue.length) {
    const batch = queue.slice(0, MAX_BATCH);
    let res;
    try {
      res = await fetch(`${API_URL}/api/events`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ events: batch }),
      });
    } catch (err) {
      console.warn("recorder: send failed", err);
      offline = true; // Keep the events and try again on the next tick
      return changed();
    }
    if (res.status >= 500) {
      offline = true;
      return changed();
    }
    // After a 200 the events are done with, rejected ones too: resending would not help (contract, "Response").
    // Any other 4xx means the batch itself is broken, and resending would block the queue forever.
    const sent = new Set(batch.map((e) => e.event_id));
    queue = queue.filter((e) => !sent.has(e.event_id));
    store.set("queue", queue);
    offline = false;
    const body = await res.json().catch(() => null);
    if (!res.ok || body?.rejected?.length) console.warn("recorder: API answered", res.status, body);
  }
  changed();
}

function flushPoints() {
  if (!session || !pending.length) return;
  enqueue("track.points", { session_id: session.session_id, points: pending });
  pending = [];
  store.set("pending", pending);
}

function onFix(pos) {
  if (!session || session.paused_at) return;
  const c = pos.coords;
  const p = { t: Math.round(pos.timestamp || Date.now()), lat: c.latitude, lon: c.longitude, accuracy: Math.round(c.accuracy * 10) / 10 };
  if (lastFix && p.t <= lastFix.t) return; // Some phones repeat the last fix
  lastFix = p;
  gpsError = null;
  pending.push(p);
  store.set("pending", pending);
  if (p.accuracy <= MAX_ACCURACY_M) {
    session.track[session.track.length - 1].push([p.lat, p.lon]);
    if (!session.anchor) session.anchor = p;
    else {
      const d = metres(session.anchor, p);
      if (d >= STEP_M) {
        session.distance += d;
        session.anchor = p;
      }
    }
  }
  save();
  changed();
}

function onGpsError(err) {
  gpsError = err.code === 1 ? "Location is off: allow it for this site, then reload" : "Looking for GPS…";
  changed();
}

function startGps() {
  stopGps();
  if (FAKE) {
    // A walk at 1.3 m/s from Otaniemi with a wandering heading, one fix a second.
    const last = session.track.flat().pop();
    let at = lastFix || (last ? { lat: last[0], lon: last[1] } : { lat: 60.1841, lon: 24.8301 });
    let heading = Math.random() * 360;
    fakeTimer = setInterval(() => {
      heading += (Math.random() - 0.5) * 40;
      const r = Math.PI / 180;
      at = { lat: at.lat + (1.3 * Math.cos(heading * r)) / 111320, lon: at.lon + (1.3 * Math.sin(heading * r)) / (111320 * Math.cos(at.lat * r)) };
      onFix({ timestamp: Date.now(), coords: { latitude: at.lat, longitude: at.lon, accuracy: 5 } });
    }, 1000);
    return;
  }
  if (!navigator.geolocation) {
    gpsError = "This browser has no location";
    return changed();
  }
  watchId = navigator.geolocation.watchPosition(onFix, onGpsError, { enableHighAccuracy: true, maximumAge: 0, timeout: 30000 });
}

function stopGps() {
  if (watchId !== null) navigator.geolocation.clearWatch(watchId);
  clearInterval(fakeTimer);
  watchId = null;
  fakeTimer = null;
}

// Phones stop reporting the location when the screen locks, so ask to keep it on.
async function keepScreenOn() {
  if (!session || session.paused_at || wakeLock || !("wakeLock" in navigator) || document.visibilityState !== "visible") return;
  try {
    wakeLock = await navigator.wakeLock.request("screen");
    wakeLock.addEventListener("release", () => {
      wakeLock = null;
    });
  } catch (err) {
    console.warn("recorder: wake lock", err);
  }
}

function releaseScreen() {
  wakeLock?.release().catch(() => {});
  wakeLock = null;
}

export function start(user, role = "plogger") {
  if (session) return;
  session = {
    session_id: uuid(),
    user_id: user,
    role,
    device_ids: DEVICES,
    started_at: Date.now(),
    paused_at: null,
    paused_ms: 0,
    distance: 0,
    anchor: null,
    track: [[]],
  };
  save();
  pending = [];
  store.set("pending", pending);
  lastFix = null;
  gpsError = null;
  enqueue("session.start", { session_id: session.session_id, user_id: user, role, device_ids: DEVICES });
  startGps();
  keepScreenOn();
  send();
  changed();
}

export function pause() {
  if (!session || session.paused_at) return;
  flushPoints();
  stopGps();
  releaseScreen();
  session.paused_at = Date.now();
  save();
  send();
  changed();
}

export function resume() {
  if (!session || !session.paused_at) return;
  session.paused_ms += Date.now() - session.paused_at;
  session.paused_at = null;
  session.anchor = null; // Walking away during the pause does not count as distance
  session.track.push([]);
  save();
  startGps();
  keepScreenOn();
  changed();
}

// Ends the outing, waits a few seconds for the API to take the last events, and returns the outing's id.
// Events that did not make it stay queued and go out later.
export async function finish() {
  if (!session) return null;
  const id = session.session_id;
  flushPoints();
  enqueue("session.end", { session_id: id });
  stopGps();
  releaseScreen();
  session = null;
  store.remove("session");
  store.remove("pending");
  changed();
  await Promise.race([send(), new Promise((r) => setTimeout(r, 5000))]);
  return id;
}

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState !== "visible" || !session || session.paused_at) return;
  startGps(); // Some phones silently stop watching the location while the page is hidden
  keepScreenOn();
  send();
});
window.addEventListener("online", send);
setInterval(() => {
  flushPoints();
  if (queue.length) send();
}, FLUSH_MS);

// A reload in the middle of an outing carries on.
if (session && !session.paused_at) {
  startGps();
  keepScreenOn();
}
send();
