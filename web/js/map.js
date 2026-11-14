// A Leaflet map in the light style of the mockup. Leaflet is loaded as a global script in index.html.

const L = window.L;

// Otaniemi, Espoo: where the demo and the simulated outings happen.
export const HOME = [60.1841, 24.8301];

export function createMap(el, { center = HOME, zoom = 14 } = {}) {
  const map = L.map(el, { zoomControl: false, attributionControl: true }).setView(center, zoom);
  // OpenStreetMap tiles, washed out in CSS (.tiles-light) to match the light map of the mockup. No key needed.
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    className: "tiles-light",
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
  }).addTo(map);
  L.control.zoom({ position: "bottomright" }).addTo(map);
  return map;
}

export function spotMarker(spot) {
  return L.circleMarker([spot.lat, spot.lon], {
    radius: 6,
    weight: 2,
    color: "#ffffff",
    fillColor: spot.cleaned_at ? "#8a9096" : "#1db35b",
    fillOpacity: 1,
  });
}
