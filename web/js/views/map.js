// Map: open litter spots and the start button. Start records the outing right here, like Strava:
// the map follows you and draws your route, a panel shows time, distance and points, with Pause and Finish.
// Recording itself is in recorder.js; points come from the picks the API puts on the outing (game.js rules).
// Clusters, zones and Plogger / Scout are in web/README.md, "Screens".

import { api, currentUser, poll } from "../api.js";
import { esc, LITTER, ago, clock, icon } from "../ui.js";
import { createMap, spotMarker } from "../map.js";
import { POINTS_PER_ITEM, POINTS_PER_PIN } from "../game.js";
import * as recorder from "../recorder.js";

const L = window.L;

export default async function render(el) {
  el.innerHTML = `<div class="map" id="map"></div>
    <div class="map-overlay card nearby" id="idle">
      <div class="eyebrow">Litter nearby</div>
      <div><span class="big" id="open-count">…</span><span class="unit">spots to clean</span></div>
      <div class="map-actions">
        <button class="btn" id="start">${icon("play", 18)}Start outing</button>
      </div>
      <p class="muted" id="idle-note" hidden></p>
    </div>
    <div class="map-overlay rec" id="rec" hidden>
      <div class="rec-card">
        <div class="rec-state" id="rec-state">Recording</div>
        <div class="rec-stats">
          <div><div class="rec-num" id="rec-time">00:00</div><div class="rec-label">Time</div></div>
          <div><div class="rec-num" id="rec-points">0</div><div class="rec-label">Points</div></div>
          <div><div class="rec-num" id="rec-dist">0.00</div><div class="rec-label">Distance (km)</div></div>
        </div>
      </div>
      <div class="rec-actions">
        <button class="btn" id="rec-pause"></button>
        <button class="btn light" id="rec-finish">Finish</button>
      </div>
    </div>
    <div class="map-overlay toast" id="toast" hidden></div>
    <button class="map-overlay locate-btn" id="locate" title="Show where I am" aria-label="Show where I am">${icon("locate", 22)}</button>`;
  const $ = (sel) => el.querySelector(sel);
  const map = createMap($("#map"));

  // ---- You on the map: a blue dot with its accuracy circle ----

  // The circle shows GPS accuracy; capped at 25 m so a poor fix does not cover the spots around you.
  const ME_CIRCLE_MAX_M = 25;
  const meCircle = L.circle([0, 0], { radius: 1, stroke: false, fillColor: "#2f80ed", fillOpacity: 0.06, interactive: false });
  const meDot = L.circleMarker([0, 0], { radius: 8, color: "#fff", weight: 3, fillColor: "#2f80ed", fillOpacity: 1, interactive: false });
  function showMe(lat, lon, accuracy) {
    meCircle.setLatLng([lat, lon]).setRadius(Math.min(accuracy, ME_CIRCLE_MAX_M)).addTo(map);
    meDot.setLatLng([lat, lon]).addTo(map);
  }

  // While recording, or after "Find me", the map follows you until you drag it; "Find me" brings that back.
  let follow = true;
  map.on("dragstart", () => (follow = false));

  const locateBtn = $("#locate");
  let watchId = null; // Our own position watch, started by "Find me" when not recording
  let lastFix = null;
  locateBtn.onclick = () => {
    follow = true;
    const fix = recorder.current()?.fix;
    if (fix) return map.flyTo([fix.lat, fix.lon], Math.max(map.getZoom(), 17));
    // Needs HTTPS or localhost. Not recording: watch the position ourselves, so the dot moves as you walk.
    if (!navigator.geolocation) return (locateBtn.title = "Location is not available in this browser");
    if (lastFix) map.flyTo([lastFix.lat, lastFix.lon], Math.max(map.getZoom(), 16));
    if (watchId !== null) return;
    locateBtn.classList.add("busy");
    watchId = navigator.geolocation.watchPosition(
      ({ coords }) => {
        locateBtn.classList.remove("busy");
        const first = !lastFix;
        lastFix = { lat: coords.latitude, lon: coords.longitude };
        if (recorder.current()) return; // While recording, update() shows the recorder's fix
        showMe(coords.latitude, coords.longitude, coords.accuracy);
        if (first) map.flyTo([lastFix.lat, lastFix.lon], Math.max(map.getZoom(), 16));
        else if (follow) map.panTo([lastFix.lat, lastFix.lon], { animate: true });
      },
      (err) => {
        locateBtn.classList.remove("busy");
        locateBtn.title = `Could not get your location: ${err.message}`;
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 30000 },
    );
  };

  // ---- Open spots ----
  // Polled, so a spot cleaned by a grabber pick vanishes within seconds and new sightings appear.

  const spotLayers = new Map(); // spot_id → marker of an open spot on the map

  function vanish(marker) {
    // The cleaned spot turns into a growing, fading ring, then goes.
    marker.closePopup().unbindPopup();
    let step = 0;
    const timer = setInterval(() => {
      step++;
      marker.setStyle({ fillOpacity: 1 - step / 12, opacity: 1 - step / 12 }).setRadius(6 + step * 1.5);
      if (step >= 12) {
        clearInterval(timer);
        marker.remove();
      }
    }, 50);
  }

  async function refreshSpots() {
    let spots;
    try {
      spots = await api.spots();
    } catch (err) {
      if (!spotLayers.size) {
        $("#open-count").textContent = "–";
        $("#idle-note").textContent = err.message;
        $("#idle-note").hidden = false;
      }
      throw err; // poll() waits longer after an error
    }
    $("#idle-note").hidden = true;
    const open = spots.filter((s) => !s.cleaned_at);
    const openIds = new Set(open.map((s) => s.spot_id));
    for (const [id, marker] of spotLayers) {
      if (!openIds.has(id)) {
        spotLayers.delete(id);
        vanish(marker);
      }
    }
    for (const s of open) {
      if (spotLayers.has(s.spot_id)) continue;
      const marker = spotMarker(s)
        .bindPopup(`<b>${esc(LITTER[s.litter_type] || s.litter_type)}</b><br>Spotted ${esc(ago(s.spotted_at))}`)
        .addTo(map);
      spotLayers.set(s.spot_id, marker);
    }
    $("#open-count").textContent = open.length;
    return true;
  }
  const stopSpots = poll(refreshSpots, 2000);

  // ---- Recording ----

  const route = L.polyline([], { color: "#16b364", weight: 6, opacity: 0.9 }).addTo(map);
  const picksLayer = L.layerGroup().addTo(map);
  let shownFix = null;
  let finishArmed = null;
  let finishing = false;
  let points = null; // From the API; null until the outing has arrived there
  let pickSeen = null; // Picks already counted; null until the first answer

  $("#start").onclick = () => {
    const user = currentUser();
    if (!user) {
      $("#idle-note").textContent = "Choose who you are first: “You” in the sidebar.";
      $("#idle-note").hidden = false;
      return;
    }
    follow = true;
    points = null;
    pickSeen = 0;
    picksLayer.clearLayers();
    recorder.start(user);
  };

  $("#rec-pause").onclick = () => {
    const rec = recorder.current();
    if (rec?.paused) recorder.resume();
    else recorder.pause();
  };

  // The first tap arms Finish, the second within 3 s finishes: a stray tap should not end the outing.
  $("#rec-finish").onclick = async () => {
    if (finishing) return;
    if (!finishArmed) {
      finishArmed = setTimeout(() => {
        finishArmed = null;
        update();
      }, 3000);
      return update();
    }
    clearTimeout(finishArmed);
    finishArmed = null;
    finishing = true;
    update();
    const id = await recorder.finish();
    finishing = false;
    if (id) location.hash = `#/done/${encodeURIComponent(id)}`;
  };

  function stateText(rec) {
    if (finishing) return ["Saving…", "saving"];
    if (rec.paused) return ["Paused", "paused"];
    if (rec.gpsError) return [rec.gpsError, "warn"];
    if (!rec.fix) return ["Looking for GPS…", "warn"];
    if (rec.offline) return ["Recording · offline, saved here", "recording"];
    return ["Recording", "recording"];
  }

  function update() {
    const rec = recorder.current();
    $("#idle").hidden = !!rec || finishing;
    $("#rec").hidden = !rec && !finishing;
    el.classList.toggle("recording", !!rec || finishing);
    if (!rec) return;

    const [text, kind] = stateText(rec);
    $("#rec-state").textContent = text;
    $("#rec-state").className = `rec-state ${kind}`;
    $("#rec-time").textContent = clock(rec.elapsed_s);
    $("#rec-dist").textContent = (rec.distance_m / 1000).toFixed(2);
    $("#rec-points").textContent = points ?? 0;
    $("#rec-pause").innerHTML = rec.paused ? `${icon("play", 20)}Resume` : `${icon("pause", 20)}Pause`;
    $("#rec-pause").classList.toggle("dark", !rec.paused);
    $("#rec-finish").innerHTML = finishArmed ? "Tap again to finish" : `${icon("flag", 20)}Finish`;
    $("#rec-finish").classList.toggle("danger", !!finishArmed);

    route.setLatLngs(rec.track);
    if (rec.fix && rec.fix !== shownFix) {
      const first = !shownFix;
      shownFix = rec.fix;
      showMe(rec.fix.lat, rec.fix.lon, rec.fix.accuracy);
      if (first) map.setView([rec.fix.lat, rec.fix.lon], 17);
      else if (follow) map.panTo([rec.fix.lat, rec.fix.lon], { animate: true });
    }
  }

  function toast(text) {
    const t = $("#toast");
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => (t.hidden = true), 3000);
  }

  const stopRecorder = recorder.subscribe(update);
  const ticker = setInterval(update, 1000); // The clock

  // Points and picks: the grabber's picks reach the API, not this page, so ask the API for the outing.
  const stopPicks = poll(async () => {
    const rec = recorder.current();
    if (!rec) return true;
    let o;
    try {
      o = await api.outing(rec.session_id);
    } catch {
      return true; // Not in the API yet: session.start may still be on its way
    }
    if (recorder.current()?.session_id !== rec.session_id) return true;
    points = o.stats.picks * POINTS_PER_ITEM + o.stats.spots_pinned * POINTS_PER_PIN;
    picksLayer.clearLayers();
    for (const p of o.picks) {
      if (p.lat !== null) L.circleMarker([p.lat, p.lon], { radius: 7, color: "#fff", weight: 2, fillColor: "#141414", fillOpacity: 1 }).addTo(picksLayer);
    }
    // Toast new picks only: reopening the map in the middle of an outing just takes the count.
    if (pickSeen !== null && o.picks.length > pickSeen) {
      const p = o.picks[o.picks.length - 1];
      toast(`+${POINTS_PER_ITEM} pts · ${LITTER[p.litter_type] || "Litter"} picked`);
    }
    pickSeen = o.picks.length;
    update();
    return true;
  }, 2000);

  return () => {
    stopRecorder();
    clearInterval(ticker);
    clearTimeout(finishArmed);
    stopPicks();
    stopSpots();
    if (watchId !== null) navigator.geolocation.clearWatch(watchId);
    map.remove();
  };
}
