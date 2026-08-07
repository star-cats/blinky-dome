/**
 * 2D fluid fire — a real Eulerian fluid simulation, not a noise field dressed
 * up as one.
 *
 * Follows Andrew Chan's writeup (andrewkchan.dev/posts/fire.html): a collocated
 * grid carrying velocity, temperature, fuel and soot, stepped with
 *
 *   advect velocity -> inject source -> combust -> vorticity confinement ->
 *   buoyancy and other forces -> pressure projection -> advect scalars
 *
 * Advection is semi-Lagrangian, so it is unconditionally stable: a cell asks
 * where its contents came from one step ago and samples there. Incompressibility
 * comes from a Jacobi solve of the pressure Poisson equation, which is what
 * makes the plume roll and curl instead of just drifting upward — a fire without
 * projection looks like a smoke machine.
 *
 * Vorticity confinement puts back the small eddies that the grid throws away
 * every time it advects. It is the single knob that decides whether this reads
 * as fire or as hot fog.
 *
 * The box is closed: velocity is clamped at the walls, every field lookup is
 * clamped to the grid, and nothing wraps. The top is open by default so the
 * plume can leave — close it with Lid and the ceiling starts rolling smoke back
 * down.
 *
 * Color is blackbody, not a gradient: temperature maps to Kelvin between the
 * Cool and Hot knobs and then to RGB, so a low Hot burns deep orange and a high
 * one runs through white into blue.
 */

var FloatArray = Java.type("float[]");

// ------------------------------------------------------------------ grid size
//
// Simulation resolution, in cells. This is the one thing here that is not a
// knob: changing it reallocates every field, so it is set in source and takes
// effect when the script reloads.
//
// Cost is linear in cell count and independent of LED count — the grid is the
// picture and the model just samples it, so a denser rig costs nothing extra.
// Measured on this laptop, 40x46 runs about 3.9 ms a frame at the default
// Solver and 96x96 about 23 ms, so the grid is what to turn down first if the
// engine starts missing frames. The grid always covers the model's full
// normalized extent: a non-square rig makes non-square cells, which the
// simulation does not mind.
var GRID_W = 40;
var GRID_H = 46;

// Simulation rate, held independent of the engine's frame rate so the fire
// looks the same on a rig running at 30 fps as on one running at 120. Substeps
// are capped rather than letting a stalled frame try to catch up all at once:
// four is enough headroom for a 30 fps engine at full Speed, and past that the
// fire quietly runs in slow motion, which is the cheap failure.
var SIM_HZ = 60;
var MAX_SUBSTEPS = 4;

// Temperature that fully saturated fuel burns at. Above 1 so a fuel-rich cell
// pins the top of the color ramp and the core of the flame goes white.
var BURN_TEMP = 1.4;

// Spatial frequency of the turbulence stream function, in cells.
var TURB_SCALE = 0.09;

// How much soot dims the gas behind it, where that gas is not itself luminous.
var SOOT_OCCLUSION = 0.85;

// Velocity retained per second of simulated time. Real fluid has no such term —
// this is here as a terminal velocity, so that a maxed Jet and Buoyancy give a
// fast fire rather than one that accelerates until every step advects the whole
// grid off the top edge.
var DRAG = 0.4;

var LUT_SIZE = 256;

// ---------------------------------------------------------------------- source

knob("srcLevel", "Source", "How hard fuel is injected at the source", 0.65);
knob("srcRadius", "Radius", "Source radius, as a fraction of the frame", 0.35);
knob("srcX", "Source X", "Source center, horizontal", 0.5);
knob("srcY", "Source Y", "Source center, vertical", 0.08);
knob("jet", "Jet", "Upward velocity injected at the source", 0.15);
knob("flicker", "Flicker", "How much the source strength wavers over time", 0.4);

// ----------------------------------------------------------------------- fluid

knob("buoyancy", "Buoyancy", "How hard heat lifts the fluid", 0.62);
knob("cooling", "Cooling", "Radiative cooling rate; sets the flame's height", 0.3);
knob("burn", "Burn", "How fast fuel is consumed once it is lit", 0.5);
knob("vorticity", "Vorticity", "Curl put back into the flame; 0 is a smooth plume", 0.5);
knob("wind", "Wind", "Sideways push; 0.5 is still", 0.5);
knob("turbulence", "Turbulence", "Divergence-free noise stirred into the velocity", 0.4);
knob("smoke", "Smoke", "Soot given off by burning fuel", 0.35);
knob("speed", "Speed", "Simulation time scale", 0.5);

// ---------------------------------------------------------------------- render

knob("coolK", "Cool", "Color temperature of the coolest visible gas", 0.3);
knob("hotK", "Hot", "Color temperature of the flame core; high burns white to blue", 0.45);
knob("falloff", "Falloff", "Contrast of the temperature-to-brightness curve", 0.3);
knob("smokeGlow", "Smoke Glow", "How brightly soot renders on its own", 0.22);
knob("level", "Level", "Overall brightness", 0.9);

knobi("solver", "Solver", "Pressure solver iterations; more is rounder and slower", 14, 41);

toggle("lid", "Lid", "Close the top of the box instead of letting the plume out", false);

// ------------------------------------------------------------------ the fields
//
// Java float arrays rather than JS arrays: one flat allocation each, and no
// boxing on the several hundred thousand reads a frame costs.

var W, H, W1, H1, cells;
var velU, velV, velU2, velV2;
var heat, heat2, fuel, fuel2, soot, soot2;
var pressure, pressure2, divergence, curl, psi;

var simAccumulator = 0;
var simClock = 0;

var lutR, lutG, lutB;

function init() {
  W = Math.max(4, GRID_W | 0);
  H = Math.max(4, GRID_H | 0);
  W1 = W - 1;
  H1 = H - 1;
  cells = W * H;

  velU = new FloatArray(cells);
  velV = new FloatArray(cells);
  velU2 = new FloatArray(cells);
  velV2 = new FloatArray(cells);
  heat = new FloatArray(cells);
  heat2 = new FloatArray(cells);
  fuel = new FloatArray(cells);
  fuel2 = new FloatArray(cells);
  soot = new FloatArray(cells);
  soot2 = new FloatArray(cells);
  pressure = new FloatArray(cells);
  pressure2 = new FloatArray(cells);
  divergence = new FloatArray(cells);
  curl = new FloatArray(cells);
  psi = new FloatArray(cells);

  lutR = new FloatArray(LUT_SIZE);
  lutG = new FloatArray(LUT_SIZE);
  lutB = new FloatArray(LUT_SIZE);

  simAccumulator = 0;
  simClock = 0;
}

// ------------------------------------------------------------------- sampling
//
// Every lookup clamps to the grid. Nothing wraps: a plume that reaches an edge
// smears along it rather than reappearing on the far side.

/** Bilinear sample of a field at fractional cell coordinates. */
function sampleField(field, x, y) {
  if (x < 0) { x = 0; } else if (x > W1) { x = W1; }
  if (y < 0) { y = 0; } else if (y > H1) { y = H1; }
  var x0 = x | 0;
  var y0 = y | 0;
  var x1 = x0 < W1 ? x0 + 1 : x0;
  var y1 = y0 < H1 ? y0 + 1 : y0;
  var tx = x - x0;
  var ty = y - y0;
  var row0 = y0 * W;
  var row1 = y1 * W;
  var a = field[row0 + x0];
  var b = field[row0 + x1];
  var c = field[row1 + x0];
  var d = field[row1 + x1];
  var lower = a + (b - a) * tx;
  return lower + ((c + (d - c) * tx) - lower) * ty;
}

// ------------------------------------------------------------------- the step

/**
 * Advect the velocity field through itself.
 *
 * Semi-Lagrangian: trace backwards from each cell along the current velocity
 * and take what is there. Unconditionally stable, so a violent Jet or a long
 * frame cannot blow the simulation up — it only smears it.
 */
function advectVelocity(dt) {
  var retention = Math.pow(DRAG, dt);
  for (var y = 0; y < H; ++y) {
    for (var x = 0; x < W; ++x) {
      var i = y * W + x;
      var px = x - velU[i] * dt;
      var py = y - velV[i] * dt;
      velU2[i] = sampleField(velU, px, py) * retention;
      velV2[i] = sampleField(velV, px, py) * retention;
    }
  }
  var swapU = velU; velU = velU2; velU2 = swapU;
  var swapV = velV; velV = velV2; velV2 = swapV;
}

function advectScalar(src, dst, dt, retention) {
  for (var y = 0; y < H; ++y) {
    for (var x = 0; x < W; ++x) {
      var i = y * W + x;
      dst[i] = sampleField(src, x - velU[i] * dt, y - velV[i] * dt) * retention;
    }
  }
}

function advectScalars(dt) {
  advectScalar(heat, heat2, dt, 1);
  advectScalar(fuel, fuel2, dt, 1);
  advectScalar(soot, soot2, dt, Math.pow(0.72, dt));
  var swapH = heat; heat = heat2; heat2 = swapH;
  var swapF = fuel; fuel = fuel2; fuel2 = swapF;
  var swapS = soot; soot = soot2; soot2 = swapS;
}

/**
 * Feed the source.
 *
 * Fuel and a little heat are laid into a soft-edged disc, and the same disc gets
 * an upward push so the plume leaves with some momentum rather than waiting for
 * buoyancy to build it. Only the disc's bounding box is visited — the source is
 * usually a small part of the grid and this runs every substep.
 */
function injectSource(dt) {
  // Floored at one cell: a disc smaller than the grid spacing can fall between
  // every cell center and inject nothing at all, which reads as the pattern
  // being broken rather than as the source being small.
  var radius = Math.max(lerp(0.02, 0.6, srcRadius), 1 / Math.min(W1, H1));
  var strength = srcLevel * srcLevel * 9;
  if (strength <= 0) {
    return;
  }

  // A slow perlin waver, so a lit source breathes instead of sitting still.
  var waver = 1 + flicker * Noise.stb_perlin_noise3(simClock * 1.7, 0, 0, 0, 0, 0) * 1.6;
  if (waver < 0) {
    waver = 0;
  }
  var rate = strength * waver * dt;
  var push = jet * jet * 45 * waver * dt;

  var xMin = Math.max(0, Math.floor((srcX - radius) * W1));
  var xMax = Math.min(W1, Math.ceil((srcX + radius) * W1));
  var yMin = Math.max(0, Math.floor((srcY - radius) * H1));
  var yMax = Math.min(H1, Math.ceil((srcY + radius) * H1));

  for (var y = yMin; y <= yMax; ++y) {
    var ny = y / H1 - srcY;
    for (var x = xMin; x <= xMax; ++x) {
      var nx = x / W1 - srcX;
      var d = Math.sqrt(nx * nx + ny * ny) / radius;
      if (d >= 1) {
        continue;
      }
      // Squared cosine-ish falloff: soft enough that the source disc never
      // prints its own outline into the flame.
      var falloff = 1 - d * d;
      falloff *= falloff;
      var i = y * W + x;
      var f = fuel[i] + rate * falloff;
      fuel[i] = f > 1 ? 1 : f;
      var h = heat[i] + rate * falloff * 0.5;
      heat[i] = h > BURN_TEMP ? BURN_TEMP : h;
      velV[i] += push * falloff;
    }
  }
}

/**
 * Burn fuel, heat what it burns, cool what has burned.
 *
 * Cooling is Stefan-Boltzmann — proportional to the fourth power of temperature
 * — which is why the flame has a hard top edge rather than fading out linearly:
 * hot gas sheds heat violently until it is merely warm, then lingers.
 */
function combust(dt) {
  var burnFactor = Math.pow(lerp(0.9, 0.001, burn), dt);
  var coolRate = lerp(0.1, 6.5, cooling) * dt;
  var sootRate = smoke * smoke * 4;

  for (var i = 0; i < cells; ++i) {
    var f = fuel[i];
    var h = heat[i];

    if (f > 0.001) {
      var burned = f * (1 - burnFactor);
      fuel[i] = f - burned;
      var lit = f * BURN_TEMP;
      if (lit > h) {
        h = lit;
      }
      var s = soot[i] + burned * sootRate;
      soot[i] = s > 1.5 ? 1.5 : s;
    } else if (f !== 0) {
      fuel[i] = 0;
    }

    if (h > 0.0005) {
      var h2 = h * h;
      h -= coolRate * h2 * h2;
      heat[i] = h > 0 ? h : 0;
    } else if (h !== 0) {
      heat[i] = 0;
    }
  }
}

/**
 * Vorticity confinement.
 *
 * Advection is diffusive: every step rounds off the small eddies, and a fire
 * without them is a plume of hot fog. This measures where curl is concentrated,
 * points a unit vector up that gradient, and pushes along it — feeding the
 * eddies the grid is eating. Constant is deliberately generous at the top of the
 * knob; past about half it stops looking like fluid and starts looking angry.
 */
function applyVorticity(dt) {
  var epsilon = vorticity * vorticity * 22;
  if (epsilon <= 0) {
    return;
  }

  for (var y = 0; y < H; ++y) {
    for (var x = 0; x < W; ++x) {
      var i = y * W + x;
      var l = x > 0 ? velV[i - 1] : velV[i];
      var r = x < W1 ? velV[i + 1] : velV[i];
      var b = y > 0 ? velU[i - W] : velU[i];
      var t = y < H1 ? velU[i + W] : velU[i];
      curl[i] = 0.5 * ((r - l) - (t - b));
    }
  }

  // Interior only: the gradient of |curl| needs a neighbor on both sides, and a
  // confinement force pointed into a wall does nothing useful anyway.
  for (var yy = 1; yy < H1; ++yy) {
    for (var xx = 1; xx < W1; ++xx) {
      var j = yy * W + xx;
      var gx = 0.5 * (Math.abs(curl[j + 1]) - Math.abs(curl[j - 1]));
      var gy = 0.5 * (Math.abs(curl[j + W]) - Math.abs(curl[j - W]));
      var mag = Math.sqrt(gx * gx + gy * gy);
      if (mag < 1e-5) {
        continue;
      }
      var w = curl[j] * epsilon * dt / mag;
      velU[j] += gy * w;
      velV[j] -= gx * w;
    }
  }
}

/** Buoyancy, wind and a divergence-free noise stir. */
function applyForces(dt) {
  var lift = buoyancy * buoyancy * 70 * dt;
  var windForce = (wind - 0.5) * 2 * 18 * dt;
  var stir = turbulence * turbulence * 30 * dt;

  if (stir > 0) {
    // Stir with the curl of a scalar noise field rather than with noise
    // directly: the curl of anything is divergence-free, so this adds swirl
    // that the pressure solve does not immediately undo. Sampled once per cell
    // into psi, then differenced, instead of four noise calls per cell.
    var z = simClock * 0.55;
    for (var y = 0; y < H; ++y) {
      for (var x = 0; x < W; ++x) {
        psi[y * W + x] = Noise.stb_perlin_noise3(x * TURB_SCALE, y * TURB_SCALE, z, 0, 0, 0);
      }
    }
  }

  for (var yy = 0; yy < H; ++yy) {
    for (var xx = 0; xx < W; ++xx) {
      var i = yy * W + xx;
      velV[i] += lift * heat[i];
      velU[i] += windForce;

      if (stir > 0) {
        var l = xx > 0 ? psi[i - 1] : psi[i];
        var r = xx < W1 ? psi[i + 1] : psi[i];
        var b = yy > 0 ? psi[i - W] : psi[i];
        var t = yy < H1 ? psi[i + W] : psi[i];
        velU[i] += stir * 0.5 * (t - b);
        velV[i] -= stir * 0.5 * (r - l);
      }
    }
  }
}

/**
 * Pressure projection: make the velocity field divergence-free.
 *
 * Solid walls take a Neumann condition — the ghost cell copies its neighbor, so
 * pressure has no gradient across the wall and no flow crosses it. An open top
 * takes a Dirichlet condition instead: the ghost is the negation of its
 * neighbor, which puts zero pressure on the boundary face and lets the plume
 * leave. Pressure is warm-started from the previous step, which is worth
 * several Jacobi iterations for free.
 */
function project() {
  var openTop = !lid;

  for (var y = 0; y < H; ++y) {
    for (var x = 0; x < W; ++x) {
      var i = y * W + x;
      var l = x > 0 ? velU[i - 1] : -velU[i];
      var r = x < W1 ? velU[i + 1] : -velU[i];
      var b = y > 0 ? velV[i - W] : -velV[i];
      var t = y < H1 ? velV[i + W] : (openTop ? velV[i] : -velV[i]);
      divergence[i] = 0.5 * ((r - l) + (t - b));
      pressure[i] *= 0.9;
    }
  }

  var iterations = solver > 0 ? solver : 1;
  for (var k = 0; k < iterations; ++k) {
    for (var yy = 0; yy < H; ++yy) {
      for (var xx = 0; xx < W; ++xx) {
        var j = yy * W + xx;
        var pc = pressure[j];
        var pl = xx > 0 ? pressure[j - 1] : pc;
        var pr = xx < W1 ? pressure[j + 1] : pc;
        var pb = yy > 0 ? pressure[j - W] : pc;
        var pt = yy < H1 ? pressure[j + W] : (openTop ? -pc : pc);
        pressure2[j] = (pl + pr + pb + pt - divergence[j]) * 0.25;
      }
    }
    var swap = pressure;
    pressure = pressure2;
    pressure2 = swap;
  }

  for (var y3 = 0; y3 < H; ++y3) {
    for (var x3 = 0; x3 < W; ++x3) {
      var m = y3 * W + x3;
      var pm = pressure[m];
      var gl = x3 > 0 ? pressure[m - 1] : pm;
      var gr = x3 < W1 ? pressure[m + 1] : pm;
      var gb = y3 > 0 ? pressure[m - W] : pm;
      var gt = y3 < H1 ? pressure[m + W] : (openTop ? -pm : pm);
      velU[m] -= 0.5 * (gr - gl);
      velV[m] -= 0.5 * (gt - gb);
    }
  }

  // The walls themselves: no flow through them, whatever the solve left behind.
  for (var y4 = 0; y4 < H; ++y4) {
    velU[y4 * W] = 0;
    velU[y4 * W + W1] = 0;
  }
  for (var x4 = 0; x4 < W; ++x4) {
    velV[x4] = 0;
    if (!openTop) {
      velV[H1 * W + x4] = 0;
    }
  }
}

function simulate(dt) {
  advectVelocity(dt);
  injectSource(dt);
  combust(dt);
  applyVorticity(dt);
  applyForces(dt);
  project();
  advectScalars(dt);
}

// -------------------------------------------------------------- blackbody LUT
//
// Temperature to color, by way of Kelvin. The chromaticity is the usual
// piecewise fit to the blackbody locus — cheap, and accurate enough over
// 1000-12000K that nobody has ever won an argument about it — and brightness is
// folded into the same table, so shading an LED is a table lookup and nothing
// else. Rebuilt every frame because it is 256 entries and a knob that lies
// about its own value for one frame is a worse bug than this is a cost.

function kelvinChannel(value) {
  return value < 0 ? 0 : (value > 255 ? 255 : value);
}

function buildColorTable() {
  // Geometric rather than linear in Kelvin, because color temperature is: the
  // ember-to-candle end of the range lives in a few hundred degrees while the
  // white-to-blue end takes thousands. Spread linearly, the whole knob above a
  // quarter turn is daylight and every setting looks the same.
  var kMin = 500 * Math.pow(8, coolK);
  var kMax = 1000 * Math.pow(12, hotK);
  if (kMax < kMin) {
    kMax = kMin;
  }
  // Low Falloff pushes brightness into the cool gas and fattens the flame; high
  // Falloff keeps only the core lit. The physical answer is the fourth power of
  // temperature, which on an 8-bit rig leaves the whole plume at black — the
  // default sits near linear instead, and the top of the knob is there for
  // anyone who wants a lean flame on a dark model.
  var gamma = lerp(0.35, 3.2, falloff);
  var scale = level * 255;

  for (var i = 0; i < LUT_SIZE; ++i) {
    var t = i / (LUT_SIZE - 1);
    var kelvin = lerp(kMin, kMax, t) / 100;
    var r, g, b;

    if (kelvin <= 66) {
      r = 255;
      g = 99.4708025861 * Math.log(kelvin) - 161.1195681661;
      b = kelvin <= 19 ? 0 : 138.5177312231 * Math.log(kelvin - 10) - 305.0447927307;
    } else {
      r = 329.698727446 * Math.pow(kelvin - 60, -0.1332047592);
      g = 288.1221695283 * Math.pow(kelvin - 60, -0.0755148492);
      b = 255;
    }

    var brightness = Math.pow(t, gamma) * scale / 255;
    lutR[i] = kelvinChannel(r) * brightness;
    lutG[i] = kelvinChannel(g) * brightness;
    lutB[i] = kelvinChannel(b) * brightness;
  }
}

// ------------------------------------------------------------------ rendering

var glowScale = 0;

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (velU == null) {
    init();
  }

  var dt = isFinite(deltaMs) ? clamp(deltaMs / 1000, 0, 0.25) : 0;
  var step = 1 / SIM_HZ;
  simAccumulator += dt * lerp(0.15, 2.5, speed);

  var budget = MAX_SUBSTEPS * step;
  if (simAccumulator > budget) {
    // Do not try to catch up after a stall. The fire runs briefly in slow
    // motion, which nobody sees; the alternative is a frame that takes ten
    // times as long as the one that caused it.
    simAccumulator = budget;
  }
  while (simAccumulator >= step) {
    simAccumulator -= step;
    simClock += step;
    simulate(step);
  }

  buildColorTable();
  glowScale = smokeGlow * smokeGlow * level * 255;
}

function renderPoint(point, deltaMs) {
  var gx = point.xn * W1;
  var gy = point.yn * H1;

  var t = sampleField(heat, gx, gy) / BURN_TEMP;
  if (t > 1) {
    t = 1;
  } else if (!(t > 0)) {
    t = 0;
  }
  var index = (t * (LUT_SIZE - 1)) | 0;

  var s = sampleField(soot, gx, gy);
  if (s > 1) {
    s = 1;
  } else if (!(s > 0)) {
    s = 0;
  }

  // Soot is thickest exactly where the fire is brightest, so occluding by
  // density alone puts a hole in the flame. Weighting by how cold the gas is
  // instead lets the plume darken and catch its own light above the flame,
  // while the burning core stays the brightest thing on the model.
  var alpha = s * (1 - t);
  var occlusion = 1 - alpha * SOOT_OCCLUSION;
  var glow = alpha * glowScale;

  var r = lutR[index] * occlusion + glow;
  var g = lutG[index] * occlusion + glow;
  var b = lutB[index] * occlusion + glow;

  return rgb(
    r > 255 ? 255 : r | 0,
    g > 255 ? 255 : g | 0,
    b > 255 ? 255 : b | 0
  );
}
