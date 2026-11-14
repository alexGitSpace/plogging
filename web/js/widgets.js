// Pieces shared by Feed, Quests, Leaderboard and Profile: icons the sidebar does not need, avatars,
// progress bars, the goal ring, hexagon badges and the mini map of a route. All return HTML strings.

import { esc, initials } from "./ui.js";

const PATHS = {
  bell: '<path d="M12 3a6 6 0 0 0-6 6v4l-2 3h16l-2-3V9a6 6 0 0 0-6-6zM10 19a2 2 0 0 0 4 0z"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2" stroke="#fff" stroke-width="2" fill="none" stroke-linecap="round"/>',
  up: '<circle cx="12" cy="12" r="9"/><path d="m8 13.5 4-4 4 4" stroke="#fff" stroke-width="2.2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>',
  chevUp: '<path d="m6 15 6-6 6 6" stroke="currentColor" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/>',
  chevDown: '<path d="m6 9 6 6 6-6" stroke="currentColor" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round"/>',
  crown: '<path d="m3 8 4.5 4L12 5l4.5 7L21 8l-2 11H5z"/>',
  flame: '<path d="M12 2s6 5.5 6 11a6 6 0 0 1-12 0c0-3 2-5 2-5s.5 3 3 3c0 0-2-4.5 1-9z"/>',
  grabber: '<path d="M6 20 17 5M17 5l2.5 1M17 5l-.5 2.6" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>',
  route: '<circle cx="6" cy="18" r="2.2"/><circle cx="18" cy="6" r="2.2"/><path d="M7.5 16.5 11 13l2 1 3.5-6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-dasharray="2 3"/>',
  butt: '<rect x="3" y="11" width="13" height="4" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/><rect x="16" y="11" width="5" height="4" rx="1"/><path d="M8 8c0-2 2-2 2-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>',
  glasses: '<circle cx="7" cy="13" r="3.5" fill="none" stroke="currentColor" stroke-width="2.2"/><circle cx="17" cy="13" r="3.5" fill="none" stroke="currentColor" stroke-width="2.2"/><path d="M10.5 13h3" stroke="currentColor" stroke-width="2.2"/>',
  pin: '<path d="M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/>',
  heart: '<path d="M12 20s-7.5-4.6-7.5-10A4.5 4.5 0 0 1 12 7.4 4.5 4.5 0 0 1 19.5 10C19.5 15.4 12 20 12 20z"/>',
  addUser: '<circle cx="9" cy="8" r="4"/><path d="M2 20c0-4 3-6.5 7-6.5s7 2.5 7 6.5z"/><path d="M19 8v6M16 11h6" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>',
  settings: '<path d="M12 2 20.5 7v10L12 22 3.5 17V7z"/><circle cx="12" cy="12" r="3.2" fill="#fff"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>',
};

export function ico(name, size = 20, cls = "") {
  return `<svg class="icon ${cls}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">${PATHS[name] || ""}</svg>`;
}

export function avatar(name, size = 44, cls = "") {
  return `<span class="ava ${cls}" style="--s:${size}px">${esc(initials(name))}</span>`;
}

export function bar(progress, cls = "") {
  return `<div class="bar ${cls}"><i style="width:${Math.round(Math.max(0, Math.min(1, progress)) * 100)}%"></i></div>`;
}

// The goal ring of the profile: an SVG circle drawn as far as `progress` goes.
export function ring(progress, size = 120, label = "") {
  const r = size / 2 - 8;
  const c = 2 * Math.PI * r;
  const p = Math.max(0, Math.min(1, progress));
  return `<div class="ring" style="width:${size}px;height:${size}px">
    <svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
      <circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="var(--line)" stroke-width="9"/>
      <circle cx="${size / 2}" cy="${size / 2}" r="${r}" fill="none" stroke="var(--green)" stroke-width="9" stroke-linecap="round"
        stroke-dasharray="${(c * p).toFixed(1)} ${c.toFixed(1)}" transform="rotate(-90 ${size / 2} ${size / 2})"/>
    </svg>
    <div class="ring-label">${label}</div>
  </div>`;
}

// Hexagon badges in the colours of the mockup. Locked ones are grey.
const BADGE_ART = {
  first: { bg: "#1f2328", art: '<path d="M14 44 26 30l8 8 6-6 10 12z" fill="#16B364"/><path d="M14 50h36" stroke="#3DDC84" stroke-width="5"/><circle cx="42" cy="22" r="4" fill="#9BE8B8"/>' },
  trail: { bg: "#F5B82E", art: '<path d="M28 14h8l6 40H22z" fill="#16B364"/><path d="M32 18v32" stroke="#fff" stroke-width="2" stroke-dasharray="4 4"/>' },
  eye: { bg: "#1E6B4A", art: '<path d="M22 54 32 28l10 26z" fill="#16B364"/><circle cx="32" cy="26" r="9" fill="#fff"/><circle cx="32" cy="26" r="4.5" fill="#1f2328"/>' },
  hundred: { bg: "#16B364", art: '<text x="32" y="39" text-anchor="middle" font-size="17" font-weight="800" fill="#fff" font-family="Montserrat, sans-serif">100</text>' },
  fire: { bg: "#FF7A45", art: '<path d="M32 14s12 10 12 22a12 12 0 0 1-24 0c0-6 4-10 4-10s1 6 6 6c0 0-4-9 2-18z" fill="#FFE08A"/>' },
};

export function hexBadge(badge, size = 64) {
  const a = BADGE_ART[badge.id] || BADGE_ART.first;
  return `<figure class="hex ${badge.earned ? "" : "locked"}" title="${esc(badge.title)}: ${esc(badge.text)}${badge.earned ? "" : " (locked)"}">
    <svg width="${size}" height="${size * 1.1}" viewBox="0 0 64 70">
      <defs><clipPath id="hx-${badge.id}"><path d="M32 2 61 18v34L32 68 3 52V18z"/></clipPath></defs>
      <g clip-path="url(#hx-${badge.id})"><rect width="64" height="70" fill="${a.bg}"/>${a.art}</g>
    </svg>
    <figcaption>${esc(badge.title)}</figcaption>
  </figure>`;
}

// A route as an SVG line on a light "map": no tiles, so it works offline and costs nothing to draw ten of.
export function miniRoute(route, { w = 320, h = 150 } = {}) {
  const pts = (route || []).filter((p) => Array.isArray(p) && p.length >= 2);
  const streets = `<path d="M-10 ${h * 0.62} L${w + 10} ${h * 0.48}M${w * 0.18} -10 L${w * 0.3} ${h + 10}M${w * 0.62} -10 L${w * 0.55} ${h + 10}" stroke="#fff" stroke-width="10"/>
    <circle cx="${w * 0.95}" cy="${h * 0.05}" r="${h * 0.45}" fill="#dbe8f3"/>
    <rect x="${w * 0.06}" y="${h * 0.55}" width="${w * 0.3}" height="${h * 0.32}" rx="8" fill="#e3eedb"/>`;
  if (pts.length < 2) return `<svg class="mini-route" viewBox="0 0 ${w} ${h}" preserveAspectRatio="xMidYMid slice"><rect width="${w}" height="${h}" fill="#eff0ec"/>${streets}</svg>`;
  const lat0 = pts.reduce((s, p) => s + p[0], 0) / pts.length;
  const k = Math.cos((lat0 * Math.PI) / 180); // longitude degrees are shorter up north
  const xs = pts.map((p) => p[1] * k);
  const ys = pts.map((p) => -p[0]);
  const [minX, maxX, minY, maxY] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const pad = 22;
  const scale = Math.min((w - 2 * pad) / (maxX - minX || 1e-9), (h - 2 * pad) / (maxY - minY || 1e-9));
  const ox = (w - (maxX - minX) * scale) / 2;
  const oy = (h - (maxY - minY) * scale) / 2;
  const xy = pts.map((_, i) => [(xs[i] - minX) * scale + ox, (ys[i] - minY) * scale + oy]);
  const d = xy.map(([x, y], i) => `${i ? "L" : "M"}${x.toFixed(1)} ${y.toFixed(1)}`).join("");
  const [sx, sy] = xy[0];
  const [ex, ey] = xy[xy.length - 1];
  return `<svg class="mini-route" viewBox="0 0 ${w} ${h}" preserveAspectRatio="xMidYMid slice">
    <rect width="${w}" height="${h}" fill="#eff0ec"/>${streets}
    <path d="${d}" fill="none" stroke="#16B364" stroke-opacity=".2" stroke-width="12" stroke-linecap="round" stroke-linejoin="round"/>
    <path d="${d}" fill="none" stroke="#16B364" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>
    <circle cx="${sx}" cy="${sy}" r="5" fill="#fff" stroke="#141414" stroke-width="2.5"/>
    <circle cx="${ex}" cy="${ey}" r="6" fill="#16B364" stroke="#fff" stroke-width="2.5"/>
  </svg>`;
}

// Litter of an outing as a strip of coloured segments, as under the feed cards of the mockup.
export const LITTER_COLOURS = { cigarette_butt: "#16B364", wrapper: "#3DDC84", can: "#9BE8B8", bottle: "#1f2328", paper: "#c8ccd0", other: "#e3e5e4" };

export function litterStrip(litter) {
  const entries = Object.entries(litter || {}).filter(([, n]) => n > 0);
  if (!entries.length) return `<div class="strip"><i style="flex:1;background:var(--line)"></i></div>`;
  return `<div class="strip">${entries
    .map(([type, n]) => `<i style="flex:${n};background:${LITTER_COLOURS[type] || "#ccc"}" title="${esc(type)}: ${n}"></i>`)
    .join("")}</div>`;
}
