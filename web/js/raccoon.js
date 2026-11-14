// The raccoon mascot in Three.js: idle (breathing, blinks, glances, tail) and the victory jump with confetti.
// Ported from the model's test page (_3d/web_test/index.html, model 12.glb = assets/raccoon.glb).
// All motion is procedural on the rig's bones; the glb has no animation clips.
//
//   const stop = await mountRaccoon(el, { win: true });   // el: a square box; returns a cleanup function
//
// With win, the victory plays shortly after loading and then every few seconds; a tap replays it.

import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";

const MODEL_URL = "assets/raccoon.glb";
const WIN_EVERY_S = 4; // victory loop length when win is on
const LOOP = 8; // idle loop length (s); idle motions are harmonics of it
const W0 = (2 * Math.PI) / LOOP;
const X = new THREE.Vector3(1, 0, 0);
const Y = new THREE.Vector3(0, 1, 0);
const Z = new THREE.Vector3(0, 0, 1);
const norm = (s) => s.replace(/[^a-z0-9]/gi, "").toLowerCase(); // three strips "." from bone names

export async function mountRaccoon(el, { win = false } = {}) {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.shadowMap.enabled = true;
  renderer.domElement.style.cssText = "width:100%;height:100%;display:block;cursor:pointer";
  el.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  const sun = new THREE.DirectionalLight(0xffffff, 1.2);
  sun.position.set(-1.5, 3, 2);
  sun.castShadow = true;
  sun.shadow.mapSize.set(1024, 1024);
  scene.add(sun);
  const ground = new THREE.Mesh(new THREE.CircleGeometry(1.2, 48), new THREE.ShadowMaterial({ opacity: 0.18 }));
  ground.rotation.x = -Math.PI / 2;
  ground.receiveShadow = true;
  scene.add(ground);

  const camera = new THREE.PerspectiveCamera(30, 1, 0.05, 50);
  camera.position.set(0, 0.85, 4.3);
  camera.lookAt(0, 0.72, 0);

  function resize() {
    const w = el.clientWidth || 220;
    const h = el.clientHeight || w;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }
  resize();
  const ro = new ResizeObserver(resize);
  ro.observe(el);

  // ---- Confetti in the brand greens ----
  const CONF_N = 140;
  const GREENS = [0x16b364, 0x3ccb7f, 0x73e2a3, 0x0e9384, 0xfdb022, 0xffffff];
  const confetti = new THREE.InstancedMesh(
    new THREE.PlaneGeometry(0.035, 0.018),
    new THREE.MeshBasicMaterial({ side: THREE.DoubleSide, transparent: true }),
    CONF_N,
  );
  confetti.frustumCulled = false;
  confetti.visible = false;
  scene.add(confetti);
  const cP = [], cV = [], cR = [], cW = [];
  for (let i = 0; i < CONF_N; i++) {
    cP.push(new THREE.Vector3());
    cV.push(new THREE.Vector3());
    cR.push(new THREE.Euler());
    cW.push(new THREE.Vector3());
    confetti.setColorAt(i, new THREE.Color(GREENS[i % GREENS.length]));
  }
  let burstT = 0;
  function burst(t) {
    for (let i = 0; i < CONF_N; i++) {
      const a = Math.random() * Math.PI * 2;
      const sp = 0.6 + Math.random() * 1.1;
      cP[i].set((Math.random() - 0.5) * 0.2, 0.85 + Math.random() * 0.1, (Math.random() - 0.5) * 0.2);
      cV[i].set(Math.cos(a) * sp * 0.6, 0.9 + Math.random() * 1.1, Math.sin(a) * sp * 0.45);
      cR[i].set(Math.random() * 6, Math.random() * 6, Math.random() * 6);
      cW[i].set((Math.random() - 0.5) * 14, (Math.random() - 0.5) * 14, (Math.random() - 0.5) * 14);
    }
    confetti.visible = true;
    confetti.material.opacity = 1;
    burstT = t;
  }
  const _m = new THREE.Matrix4(), _qq = new THREE.Quaternion(), _one = new THREE.Vector3(1, 1, 1);
  function stepConfetti(dt, age) {
    for (let i = 0; i < CONF_N; i++) {
      cV[i].y -= 2.2 * dt;
      cV[i].multiplyScalar(1 - 1.6 * dt); // gravity and air drag: a fluttery fall
      cP[i].addScaledVector(cV[i], dt);
      cR[i].x += cW[i].x * dt;
      cR[i].y += cW[i].y * dt;
      cR[i].z += cW[i].z * dt;
      _m.compose(cP[i], _qq.setFromEuler(cR[i]), _one);
      confetti.setMatrixAt(i, _m);
    }
    confetti.instanceMatrix.needsUpdate = true;
    confetti.material.opacity = Math.min(1, Math.max(0, (3.2 - age) / 0.8));
    if (age > 3.2) confetti.visible = false;
  }

  // Soft green glow under the raccoon during the win
  const glow = new THREE.Mesh(
    new THREE.CircleGeometry(0.75, 64),
    new THREE.ShaderMaterial({
      transparent: true,
      depthWrite: false,
      uniforms: { k: { value: 0 } },
      vertexShader: "varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix*modelViewMatrix*vec4(position,1.); }",
      fragmentShader:
        "uniform float k; varying vec2 vUv; void main(){ float d = length(vUv-.5)*2.; gl_FragColor = vec4(0.086,0.702,0.392, k*0.45*smoothstep(1.,0.,d)); }",
    }),
  );
  glow.rotation.x = -Math.PI / 2;
  glow.position.y = 0.002;
  scene.add(glow);

  // ---- Rig: rotate a bone around a WORLD axis on top of its rest pose ----
  const bones = {}, rest = {};
  const _pq = new THREE.Quaternion(), _q = new THREE.Quaternion();
  function worldRot(bone, axis, angle) {
    bone.parent.getWorldQuaternion(_pq);
    _q.setFromAxisAngle(axis, angle);
    const l = _pq.clone().invert().multiply(_q).multiply(_pq); // local = parent⁻¹ · qWorld · parent · current
    bone.quaternion.premultiply(l);
    bone.updateMatrixWorld(true);
  }
  function axisIndex(bone, worldDir) {
    // Which local axis of the bone points along worldDir
    const q = bone.getWorldQuaternion(new THREE.Quaternion());
    let best = "x", bd = 0;
    ["x", "y", "z"].forEach((a, i) => {
      const d = Math.abs(new THREE.Vector3(i === 0, i === 1, i === 2).applyQuaternion(q).dot(worldDir));
      if (d > bd) {
        bd = d;
        best = a;
      }
    });
    return best;
  }
  const toLocal = (bone, worldDir) => worldDir.clone().applyQuaternion(bone.getWorldQuaternion(new THREE.Quaternion()).invert());

  const gltf = await new GLTFLoader().loadAsync(MODEL_URL);
  const model = gltf.scene;
  scene.add(model);
  model.traverse((o) => {
    if (o.isBone) {
      bones[norm(o.name)] = o;
      rest[norm(o.name)] = { q: o.quaternion.clone(), p: o.position.clone(), s: o.scale.clone() };
    }
    if (o.isMesh) o.castShadow = true;
    if (o.isMesh && o.material && o.material.alphaTest > 0) o.material.alphaToCoverage = true; // smooth decal edges
  });
  model.updateMatrixWorld(true);
  const b = bones;
  const FACE = {};
  FACE.eyeUp = axisIndex(b.eyel, Y);
  FACE.mouthUp = axisIndex(b.mouth, Y);
  FACE.mouthSide = axisIndex(b.mouth, X);
  // The pupil moves in the eye's own plane, never along its normal, or it sinks under the white
  const inPlane = (bone, dir) => {
    const v = toLocal(bone, dir);
    v.y = 0;
    return v.normalize();
  };
  FACE.pupil = { l: [inPlane(b.eyel, X), inPlane(b.eyel, Y)], r: [inPlane(b.eyer, X), inPlane(b.eyer, Y)] };

  // ---- Blink: close 0.06 s, open 0.1 s, sometimes twice ----
  let blinks = [], nextBlink = 1.5;
  const blink = (t) => {
    blinks.push(t);
    if (Math.random() < 0.2) blinks.push(t + 0.24);
  };
  function blinkOpen(t, starts) {
    let open = 1;
    for (const s of starts) {
      const x = t - s;
      let v = 1;
      if (x >= 0 && x < 0.06) v = 1 - 0.9 * (x / 0.06) ** 2;
      else if (x >= 0.06 && x < 0.16) {
        const y = (x - 0.06) / 0.1;
        v = 0.1 + 0.9 * (1 - (1 - y) ** 2);
      }
      open = Math.min(open, v);
    }
    return open;
  }
  // ---- Glances every 1–3 s ----
  let look = { x: 0, y: 0 }, lookFrom = { x: 0, y: 0 }, lookTo = { x: 0, y: 0 }, lookT = -10, nextLook = 1;
  const LOOKS = [[0, 0], [1, 0], [-1, 0], [0.7, 0.6], [-0.7, 0.6], [0.6, -0.5], [-0.6, -0.5], [0, 0], [0, 0]];
  const PUPIL_X = 0.017, PUPIL_Y = 0.012; // max pupil travel (m), so the iris stays inside the white
  let smileT = -10, nextSmile = 4, flickT = -10, nextFlick = 3;
  let winT = win ? 0.6 : -100;
  let tapT = -10;
  let lastT = 0;
  const clock = new THREE.Clock();

  renderer.domElement.addEventListener("pointerup", () => {
    const t = clock.getElapsedTime();
    if (win && t - winT > 2.4) winT = t; // tap: victory again
    else tapT = t;
  });

  const ease = (x) => (x <= 0 ? 0 : x >= 1 ? 1 : x * x * (3 - 2 * x));

  function frame(t) {
    const dt = Math.max(0, Math.min(0.05, t - lastT));
    lastT = t;
    if (win && t - winT > WIN_EVERY_S) winT = t;
    for (const k in bones) {
      bones[k].quaternion.copy(rest[k].q);
      bones[k].position.copy(rest[k].p);
      bones[k].scale.copy(rest[k].s);
    }
    const vt = t - winT;
    const WIN = vt >= 0 && vt < 2.4;
    const k = WIN ? 0 : 1;
    // Idle: breathing, head sway, ear twitch
    const breathe = Math.sin(t * W0 * 3) * 0.012 * k;
    b.body.scale.set(rest.body.s.x * (1 + breathe * 0.6), rest.body.s.y * (1 - breathe), rest.body.s.z * (1 + breathe * 0.6));
    b.root.updateMatrixWorld(true);
    worldRot(b.head, Z, Math.sin(t * W0) * 0.08 * k);
    worldRot(b.head, X, Math.sin(t * W0 + 1) * 0.04 * k);
    const tw = (Math.sin(t * W0 * 9) > 0.97 ? 0.25 : 0) * k;
    worldRot(b.earl, X, -tw);
    worldRot(b.earr, X, -0.6 * tw);
    // Tail: yaw = side to side, lift = tip up; each bone lags the one before, so it waves
    const tYaw = [0, 0, 0], tLift = [0, 0, 0], TW = [0.55, 0.8, 1.0];
    for (let i = 0; i < 3; i++) {
      tYaw[i] += k * 0.2 * TW[i] * Math.sin(t * W0 * 2 - i * 0.7);
      tLift[i] += k * (0.03 + 0.03 * Math.sin(t * W0 - i * 0.5)) * TW[i];
    }
    if (k && t > nextFlick) {
      flickT = t;
      nextFlick = t + 4 + Math.random() * 3;
    }
    const ft = t - flickT;
    if (k && ft >= 0 && ft < 0.9) {
      const env = Math.sin(Math.min(1, ft / 0.9) * Math.PI);
      for (let i = 0; i < 3; i++) {
        tLift[i] += env * [0.12, 0.25, 0.4][i];
        tYaw[i] += env * 0.35 * TW[i] * Math.sin((ft - i * 0.06) * 16);
      }
    }
    model.position.y = 0;
    model.rotation.y = 0;
    // ---- Victory: crouch, spin jump, land, happy hops ----
    glow.material.uniforms.k.value = WIN ? Math.min(1, vt / 0.3) * Math.min(1, (2.4 - vt) / 0.5) : 0;
    const bump = (a, c) => (vt > a && vt < c ? Math.sin(((vt - a) / (c - a)) * Math.PI) : 0);
    if (WIN) {
      let sq = 0, h = 0;
      sq += 0.16 * bump(0, 0.3);
      h += 0.38 * bump(0.28, 0.9);
      sq -= 0.1 * bump(0.28, 0.5);
      model.rotation.y = Math.PI * 2 * ease((vt - 0.32) / 0.52);
      sq += 0.14 * bump(0.9, 1.08);
      h += 0.09 * bump(1.12, 1.42) + 0.07 * bump(1.5, 1.78);
      sq += 0.07 * bump(1.42, 1.52) + 0.06 * bump(1.78, 1.9);
      model.position.y = h;
      b.body.scale.y *= 1 - sq;
      b.body.scale.x *= 1 + sq * 0.5;
      b.body.scale.z *= 1 + sq * 0.5;
      worldRot(b.head, X, 0.12 * bump(0, 0.3) - 0.18 * bump(0.3, 0.9));
      worldRot(b.head, Z, Math.sin((vt - 1.0) * 9) * 0.12 * (vt > 1.0 ? Math.min(1, (2.4 - vt) / 0.6) : 0));
      const ear = -0.45 * bump(0.28, 0.95) + Math.sin(vt * 16) * 0.18 * (vt > 1.0 ? 1 : 0) * Math.min(1, (2.4 - vt) / 0.5);
      worldRot(b.earl, X, ear);
      worldRot(b.earr, X, ear * 0.9);
      const spinV = vt > 0.32 && vt < 0.84 ? Math.sin(((vt - 0.32) / 0.52) * Math.PI) : 0;
      const land = vt > 0.9 ? Math.exp(-(vt - 0.9) * 4.5) : 0;
      const happy = vt > 1.0 ? Math.min(1, (vt - 1.0) / 0.25) * Math.min(1, (2.4 - vt) / 0.5) : 0;
      for (let i = 0; i < 3; i++) {
        tLift[i] += -0.06 * bump(0, 0.3) * TW[i];
        tLift[i] += [0.35, 0.55, 0.7][i] * bump(0.28, 0.95);
        tYaw[i] += -0.5 * spinV * TW[i];
        tLift[i] += land * 0.4 * TW[i] * Math.cos((vt - 0.9) * 20 - i * 0.9);
        tYaw[i] += happy * 0.5 * TW[i] * Math.sin(vt * 17 - i * 0.65);
      }
      if (vt >= 0.55 && vt < 0.7 && !confetti.visible) burst(t);
    }
    for (let i = 0; i < 3; i++) {
      const bn = b["tail" + (i + 1)];
      worldRot(bn, Y, tYaw[i]);
      worldRot(bn, X, tLift[i]);
    }
    if (confetti.visible) stepConfetti(dt, t - burstT);

    // ---- Face ----
    if (t > nextBlink && !WIN) {
      blink(t);
      nextBlink = t + 2.5 + Math.random() * 3;
    }
    let eyeOpen = blinkOpen(t, blinks);
    blinks = blinks.filter((s) => t - s < 1);
    if (t > nextLook) {
      const pick = LOOKS[(Math.random() * LOOKS.length) | 0];
      lookFrom = { ...look };
      lookTo = { x: pick[0], y: pick[1] };
      lookT = t;
      nextLook = t + 1 + Math.random() * 2;
    }
    const le = ease((t - lookT) / 0.09);
    look = { x: lookFrom.x + (lookTo.x - lookFrom.x) * le, y: lookFrom.y + (lookTo.y - lookFrom.y) * le };
    let lx = look.x * k, ly = look.y * k;
    if (k && t > nextSmile) {
      smileT = t;
      nextSmile = t + 5 + Math.random() * 4;
    }
    const smile = ease((t - smileT) / 0.3) * (1 - ease((t - smileT - 1.6) / 0.3)) * k * 0.6;
    let mouthW = 1 + 0.25 * smile, mouthH = 1 + 0.9 * smile, eyeScale = 1, mopen = 0;
    const tt = t - tapT;
    if (tt >= 0 && tt < 1.0 && !WIN) {
      // Tap: surprised wide eyes, a small "o", a head jerk, then a blink
      const pop = Math.sin(Math.min(1, tt / 0.6) * Math.PI);
      eyeScale *= 1 + 0.18 * pop;
      mouthW *= 1 - 0.12 * pop;
      mouthH *= 1 - 0.1 * pop;
      mopen = 0.9 * pop;
      lx *= 1 - pop;
      ly = ly * (1 - pop) + 0.3 * pop;
      worldRot(b.head, X, -0.12 * Math.sin(Math.min(1, tt / 0.35) * Math.PI));
      const ear = -0.35 * Math.sin(Math.min(1, tt / 0.5) * Math.PI);
      worldRot(b.earl, X, ear);
      worldRot(b.earr, X, ear);
      model.position.y += 0.035 * Math.sin(Math.min(1, tt / 0.3) * Math.PI);
      if (tt > 0.6) eyeOpen = Math.min(eyeOpen, blinkOpen(tt, [0.62]));
    }
    if (WIN) {
      // Squeeze on the crouch, wide eyes up in the air, happy squint and a big open smile after landing
      eyeOpen = Math.min(eyeOpen, 1 - 0.45 * bump(0, 0.32));
      eyeScale *= 1 + 0.15 * bump(0.3, 0.9);
      ly = 0.8 * bump(0.3, 0.95);
      const happy = ease((vt - 0.95) / 0.12) * (1 - ease((vt - 2.15) / 0.2));
      eyeOpen = Math.min(eyeOpen, 1 - 0.68 * happy);
      ly += 0.6 * happy;
      const sm = ease((vt - 0.25) / 0.2) * (1 - ease((vt - 2.2) / 0.2));
      mouthW = 1 + 0.35 * sm;
      mouthH = 1 + 1.3 * sm;
      const air = bump(0.3, 0.95);
      mopen = Math.max(0.75 * air, happy);
      mouthW *= 1 - 0.25 * air;
      if (vt > 2.05 && vt < 2.07) blink(t);
    }
    for (const [eb, pb, side] of [[b.eyel, b.pupill, "l"], [b.eyer, b.pupilr, "r"]]) {
      eb.scale.set(eyeScale, eyeScale, eyeScale);
      eb.scale[FACE.eyeUp] *= eyeOpen;
      const [ax, ay] = FACE.pupil[side];
      pb.position.copy(rest[norm(pb.name)].p).addScaledVector(ax, lx * PUPIL_X).addScaledVector(ay, ly * PUPIL_Y);
    }
    b.mouth.scale.set(1, 1, 1);
    b.mouth.scale[FACE.mouthSide] = mouthW;
    b.mouth.scale[FACE.mouthUp] = mouthH;
    if (b.mouthopen) {
      // Always set from code: collapsed to a point when closed, grows down from the smile line
      const mo = b.mouthopen;
      mo.scale.set(1, 1, 1);
      mo.scale[FACE.mouthSide] = mopen < 0.01 ? 0.001 : 0.65 + 0.35 * mopen;
      mo.scale[FACE.mouthUp] = Math.max(0.001, mopen);
    }
    worldRot(b.head, Y, lx * 0.06); // the head follows the gaze a little
    renderer.render(scene, camera);
  }

  let raf = 0;
  const loop = () => {
    frame(clock.getElapsedTime());
    raf = requestAnimationFrame(loop);
  };
  loop();

  return () => {
    cancelAnimationFrame(raf);
    ro.disconnect();
    pmrem.dispose();
    renderer.dispose();
    renderer.domElement.remove();
  };
}
