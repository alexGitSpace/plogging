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

  const meCircle = L.circle([0, 0], { radius: 1, color: "#2f80ed", weight: 1, fillOpacity: 0.1, interactive: false });
  const meDot = L.circleMarker([0, 0], { radius: 8, color: "#fff", weight: 3, fillColor: "#2f80ed", fillOpacity: 1, interactive: false });
  function showMe(lat, lon, accuracy) {
    meCircle.setLatLng([lat, lon]).setRadius(accuracy).addTo(map);
    meDot.setLatLng([lat, lon]).addTo(map);
  }

  // While recording the map follows you, until you drag it; "Find me" brings that back.
  let follow = true;
  map.on("dragstart", () => (follow = false));

  const locateBtn = $("#locate");
  locateBtn.onclick = () => {
    follow = true;
    const fix = recorder.current()?.fix;
    if (fix) return map.flyTo([fix.lat, fix.lon], Math.max(map.getZoom(), 17));
    // Needs HTTPS or localhost.
    if (!navigator.geolocation) return (locateBtn.title = "Location is not available in this browser");
    locateBtn.classList.add("busy");
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
        locateBtn.classList.remove("busy");
        showMe(coords.latitude, coords.longitude, coords.accuracy);
        map.flyTo([coords.latitude, coords.longitude], Math.max(map.getZoom(), 16));
      },
      (err) => {
        locateBtn.classList.remove("busy");
        locateBtn.title = `Could not get your location: ${err.message}`;
      },
      { enableHighAccuracy: true, timeout: 10000 },
    );
  };

  // ---- Open spots ----

  (async () => {
    try {
      const open = (await api.spots()).filter((s) => !s.cleaned_at);
      $("#open-count").textContent = open.length;
      for (const s of open) {
        spotMarker(s)
          .bindPopup(`<b>${esc(LITTER[s.litter_type] || s.litter_type)}</b><br>Spotted ${esc(ago(s.spotted_at))}`)
          .addTo(map);
      }
    } catch (err) {
      $("#open-count").textContent = "–";
      $("#idle-note").textContent = err.message;
      $("#idle-note").hidden = false;
    }
  })();

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
    map.remove();
  };
}
