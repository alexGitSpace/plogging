// Small helpers shared by the views. Everything from the API goes through esc() before it reaches innerHTML.

export function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

export function initials(name) {
  return String(name || "?")
    .split(/[\s._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0].toUpperCase())
    .join("");
}

export function km(m) {
  return (m / 1000).toFixed(m < 10000 ? 1 : 0);
}

export function duration(s) {
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h ? `${h}h ${m}m` : `${m}m`;
}

export function clock(s) {
  const m = Math.floor(s / 60);
  return `${String(m).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

export function ago(t) {
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return `${Math.floor(s / 86400)} d ago`;
}

export const LITTER = {
  bottle: "Plastic bottle",
  can: "Can",
  cigarette_butt: "Cigarette butt",
  wrapper: "Wrapper",
  paper: "Paper",
  other: "Litter",
};

export function loading(el, title) {
  el.innerHTML = `<header class="page-head"><h1>${esc(title)}</h1></header><p class="muted">Loading…</p>`;
}

export function failed(el, title, err) {
  el.innerHTML = `<header class="page-head"><h1>${esc(title)}</h1></header>
    <div class="card error">Could not load from the API: ${esc(err.message)}</div>`;
}

const ICON_PATHS = {
  feed: '<path d="M4 11 12 4l8 7v9h-5v-6H9v6H4z"/>',
  quests: '<circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="12" cy="12" r="4" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="12" cy="12" r="1.5"/>',
  map: '<path d="M20 4 3 11l7 2 2 7z"/>',
  leaders: '<rect x="4" y="12" width="4" height="8" rx="1"/><rect x="10" y="6" width="4" height="14" rx="1"/><rect x="16" y="9" width="4" height="11" rx="1"/>',
  profile: '<circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7z"/>',
  grabber: '<path d="M6 20 17 5M17 5l2.5 1M17 5l-.5 2.6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
  glasses: '<circle cx="7" cy="13" r="3.5" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="17" cy="13" r="3.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M10.5 13h3" stroke="currentColor" stroke-width="2"/>',
  play: '<path d="M8 5v14l11-7z"/>',
  locate: '<path d="M20 4 3 11l7 2 2 7z"/>',
  pin: '<path d="M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/>',
};

export function icon(name, size = 22) {
  return `<svg class="icon" width="${size}" height="${size}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">${ICON_PATHS[name] || ""}</svg>`;
}
