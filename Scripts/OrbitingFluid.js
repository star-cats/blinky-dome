/**
 * A single luminous dye source orbiting inside a closed 2D fluid box.
 *
 * This is the small, deliberately plain relative of FluidFire.js. There is no
 * combustion, buoyancy, wind, noise or choreography. A soft disc moves in a
 * circle, creates white dye, and drags the fluid along its swept path. The dye
 * is then carried by the velocity field and continuously decays toward black.
 *
 * The fluid step follows the usual stable-fluids order:
 *
 *   self-advect velocity -> diffuse velocity -> inject source momentum
 *   -> vorticity confinement -> pressure projection -> advect/decay dye
 *
 * Semi-Lagrangian advection keeps the step stable. Viscosity is an implicit
 * Jacobi diffusion solve, so the full knob range remains stable too. Vorticity
 * confinement measures curl, follows the gradient of its magnitude, and adds
 * the perpendicular force N x omega; its fixed amount restores some of the
 * small eddies erased by interpolation without adding another control.
 *
 * Every side is a solid no-slip wall. Field samples clamp to the box, pressure
 * uses a closed Neumann boundary, and both velocity components are zeroed on
 * every boundary cell. Nothing wraps and nothing can leave.
 */

var FloatArray = Java.type("float[]");

// Edit this constant and reload the script to change simulation resolution.
var GRID_SIZE = 40;

var SIM_HZ = 60;
var MAX_SUBSTEPS = 4;
var DIFFUSION_ITERATIONS = 16;
var PRESSURE_ITERATIONS = 24;

// Fixed rather than exposed: the requested controls are limited to the six
// below. This sits in the useful low-confinement range for a 40x40 grid.
var VORTICITY_CONFINEMENT = 8;

knob("sourceStrength", "Source Strength", "Dye creation and how firmly the moving source drags the fluid", 0.65);
knob("sourceSize", "Source Size", "Radius of the soft dye source", 0.3);
knob("sourceRadius", "Source Radius", "Radius of the source's circular orbit", 0.65);
knob("sourceAngularSpeed", "Source Angular Speed", "Orbit rate; 0 is still, 1 is 1.2 revolutions per second", 0.22);
knob("decayRate", "Decay Rate", "How quickly all dye continuously decays toward zero", 0.35);
knob("viscosity", "Viscosity", "Velocity diffusion; 0 is fluid, 1 is thick and smooth", 0.2);

var W, H, W1, H1, cells;
var velU, velV, velU2, velV2, diffusionU, diffusionV;
var dye, dye2;
var pressure, pressure2, divergence, curl;

var simAccumulator = 0;
var sourceAngle = 0;
var sourceX = 0.5;
var sourceY = 0.5;
var sourcePrevX = 0.5;
var sourcePrevY = 0.5;
var sourceVelocityU = 0;
var sourceVelocityV = 0;
var previousOrbitRadius = 0;

function init() {
  W = Math.max(4, GRID_SIZE | 0);
  H = W;
  W1 = W - 1;
  H1 = H - 1;
  cells = W * H;

  velU = new FloatArray(cells);
  velV = new FloatArray(cells);
  velU2 = new FloatArray(cells);
  velV2 = new FloatArray(cells);
  diffusionU = new FloatArray(cells);
  diffusionV = new FloatArray(cells);
  dye = new FloatArray(cells);
  dye2 = new FloatArray(cells);
  pressure = new FloatArray(cells);
  pressure2 = new FloatArray(cells);
  divergence = new FloatArray(cells);
  curl = new FloatArray(cells);

  // Chromatik invokes init() before it exposes knob values, so initialization
  // must not read sourceRadius (or any other parameter). The first fixed step
  // resolves the actual orbit and treats it as placement, not a fluid shove.
  sourceX = 0.5;
  sourceY = 0.5;
  sourcePrevX = sourceX;
  sourcePrevY = sourceY;
  sourceVelocityU = 0;
  sourceVelocityV = 0;
  previousOrbitRadius = -1;
  sourceAngle = 0;
  simAccumulator = 0;
}

/** Bilinear field sample with closed, clamped boundaries. */
function sampleField(field, x, y) {
  x = clampValue(x, 0, W1);
  y = clampValue(y, 0, H1);
  var x0 = Math.floor(x);
  var y0 = Math.floor(y);
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

/** Trace each cell backward through the old velocity field. */
function advectVelocity(dt) {
  for (var y = 0; y < H; ++y) {
    for (var x = 0; x < W; ++x) {
      var i = y * W + x;
      var px = x - velU[i] * dt;
      var py = y - velV[i] * dt;
      velU2[i] = sampleField(velU, px, py);
      velV2[i] = sampleField(velV, px, py);
    }
  }
  var swapU = velU; velU = velU2; velU2 = swapU;
  var swapV = velV; velV = velV2; velV2 = swapV;
  enforceNoSlipWalls();
}

/**
 * Implicit velocity diffusion: (I - nu*dt*Laplacian) uNew = uAdvected.
 *
 * Viscosity is in grid-cells squared per second. Squaring the knob gives the
 * watery end useful travel while still reaching a very smooth, oily maximum.
 */
function diffuseVelocity(dt) {
  var nu = viscosity * viscosity * 18;
  if (nu <= 0) {
    return;
  }

  var a = nu * dt;
  var denom = 1 + 4 * a;
  for (var i = 0; i < cells; ++i) {
    diffusionU[i] = velU[i];
    diffusionV[i] = velV[i];
  }

  for (var iteration = 0; iteration < DIFFUSION_ITERATIONS; ++iteration) {
    for (var y = 0; y < H; ++y) {
      for (var x = 0; x < W; ++x) {
        var index = y * W + x;
        if (x === 0 || x === W1 || y === 0 || y === H1) {
          velU2[index] = 0;
          velV2[index] = 0;
          continue;
        }
        velU2[index] = (diffusionU[index] + a * (
          velU[index - 1] + velU[index + 1] +
          velU[index - W] + velU[index + W]
        )) / denom;
        velV2[index] = (diffusionV[index] + a * (
          velV[index - 1] + velV[index + 1] +
          velV[index - W] + velV[index + W]
        )) / denom;
      }
    }
    var swapU = velU; velU = velU2; velU2 = swapU;
    var swapV = velV; velV = velV2; velV2 = swapV;
  }
}

/** Move the source one fixed simulation step around its circle. */
function updateSource(dt) {
  sourcePrevX = sourceX;
  sourcePrevY = sourceY;

  var revolutionsPerSecond = sourceAngularSpeed * 1.2;
  var angularVelocity = revolutionsPerSecond * Math.PI * 2;
  sourceAngle += angularVelocity * dt;
  if (sourceAngle >= Math.PI * 2 * 10000) {
    sourceAngle -= Math.floor(sourceAngle / (Math.PI * 2 * 10000)) * Math.PI * 2 * 10000;
  }

  var orbitRadius = lerpValue(0, 0.42, sourceRadius);
  sourceX = 0.5 + Math.cos(sourceAngle) * orbitRadius;
  sourceY = 0.5 + Math.sin(sourceAngle) * orbitRadius;

  // A Radius knob change is a layout edit, not a fluid impulse. Stamp only the
  // new disc on that step instead of drawing a capsule across the tank.
  if (Math.abs(orbitRadius - previousOrbitRadius) > 1e-6) {
    sourcePrevX = sourceX;
    sourcePrevY = sourceY;
  }
  previousOrbitRadius = orbitRadius;

  // Analytic tangential velocity keeps momentum strictly circular and avoids
  // deriving a false radial kick from a live Radius change.
  sourceVelocityU = -Math.sin(sourceAngle) * orbitRadius * angularVelocity * W1;
  sourceVelocityV = Math.cos(sourceAngle) * orbitRadius * angularVelocity * H1;
}

/**
 * Deposit a soft swept disc and drag covered fluid toward the source velocity.
 * The capsule prevents gaps when a small source moves more than its diameter in
 * one step. Blending toward the source velocity gives the momentum transfer a
 * stable ceiling instead of accelerating the fluid without limit.
 */
function injectSource(dt) {
  var strength = sourceStrength * sourceStrength;
  if (strength <= 0) {
    return;
  }

  var radius = lerpValue(0.012, 0.16, sourceSize);
  var xMin = Math.max(0, Math.floor((Math.min(sourcePrevX, sourceX) - radius) * W1));
  var xMax = Math.min(W1, Math.ceil((Math.max(sourcePrevX, sourceX) + radius) * W1));
  var yMin = Math.max(0, Math.floor((Math.min(sourcePrevY, sourceY) - radius) * H1));
  var yMax = Math.min(H1, Math.ceil((Math.max(sourcePrevY, sourceY) + radius) * H1));

  var segmentX = sourceX - sourcePrevX;
  var segmentY = sourceY - sourcePrevY;
  var segmentLength2 = segmentX * segmentX + segmentY * segmentY;
  var dyeAmount = strength * 8 * dt;
  var coupling = 1 - Math.exp(-strength * 12 * dt);

  for (var y = yMin; y <= yMax; ++y) {
    var py = y / H1;
    for (var x = xMin; x <= xMax; ++x) {
      var px = x / W1;
      var along = segmentLength2 > 0
        ? clampValue(((px - sourcePrevX) * segmentX + (py - sourcePrevY) * segmentY) / segmentLength2, 0, 1)
        : 0;
      var dx = px - (sourcePrevX + segmentX * along);
      var dy = py - (sourcePrevY + segmentY * along);
      var normalizedDistance = Math.sqrt(dx * dx + dy * dy) / radius;
      if (normalizedDistance >= 1) {
        continue;
      }

      // A soft eighth-power profile leaves no visible source-disc outline.
      var falloff = 1 - normalizedDistance * normalizedDistance;
      falloff *= falloff;
      falloff *= falloff;

      var i = y * W + x;
      dye[i] = Math.min(1, dye[i] + dyeAmount * falloff);
      var drag = coupling * falloff;
      velU[i] += (sourceVelocityU - velU[i]) * drag;
      velV[i] += (sourceVelocityV - velV[i]) * drag;
    }
  }
}

/**
 * Restore small vortices lost to semi-Lagrangian interpolation.
 * N points up the gradient of |curl|; N x omega is perpendicular to it.
 */
function applyVorticityConfinement(dt) {
  for (var y = 0; y < H; ++y) {
    for (var x = 0; x < W; ++x) {
      var i = y * W + x;
      var left = x > 0 ? velV[i - 1] : velV[i];
      var right = x < W1 ? velV[i + 1] : velV[i];
      var bottom = y > 0 ? velU[i - W] : velU[i];
      var top = y < H1 ? velU[i + W] : velU[i];
      curl[i] = 0.5 * ((right - left) - (top - bottom));
    }
  }

  for (var yy = 1; yy < H1; ++yy) {
    for (var xx = 1; xx < W1; ++xx) {
      var j = yy * W + xx;
      var gx = 0.5 * (Math.abs(curl[j + 1]) - Math.abs(curl[j - 1]));
      var gy = 0.5 * (Math.abs(curl[j + W]) - Math.abs(curl[j - W]));
      var magnitude = Math.sqrt(gx * gx + gy * gy);
      if (magnitude < 1e-5) {
        continue;
      }
      var force = curl[j] * VORTICITY_CONFINEMENT * dt / magnitude;
      velU[j] += gy * force;
      velV[j] -= gx * force;
    }
  }
}

/** Project the velocity field to zero divergence inside a fully closed box. */
function projectVelocity() {
  for (var y = 0; y < H; ++y) {
    for (var x = 0; x < W; ++x) {
      var i = y * W + x;
      var left = x > 0 ? velU[i - 1] : -velU[i];
      var right = x < W1 ? velU[i + 1] : -velU[i];
      var bottom = y > 0 ? velV[i - W] : -velV[i];
      var top = y < H1 ? velV[i + W] : -velV[i];
      divergence[i] = 0.5 * ((right - left) + (top - bottom));
      pressure[i] *= 0.8;
    }
  }

  for (var iteration = 0; iteration < PRESSURE_ITERATIONS; ++iteration) {
    for (var yy = 0; yy < H; ++yy) {
      for (var xx = 0; xx < W; ++xx) {
        var j = yy * W + xx;
        var center = pressure[j];
        var leftP = xx > 0 ? pressure[j - 1] : center;
        var rightP = xx < W1 ? pressure[j + 1] : center;
        var bottomP = yy > 0 ? pressure[j - W] : center;
        var topP = yy < H1 ? pressure[j + W] : center;
        pressure2[j] = (leftP + rightP + bottomP + topP - divergence[j]) * 0.25;
      }
    }
    var swap = pressure; pressure = pressure2; pressure2 = swap;
  }

  for (var y2 = 0; y2 < H; ++y2) {
    for (var x2 = 0; x2 < W; ++x2) {
      var index = y2 * W + x2;
      var centerP = pressure[index];
      var left = x2 > 0 ? pressure[index - 1] : centerP;
      var right = x2 < W1 ? pressure[index + 1] : centerP;
      var bottom = y2 > 0 ? pressure[index - W] : centerP;
      var top = y2 < H1 ? pressure[index + W] : centerP;
      velU[index] -= 0.5 * (right - left);
      velV[index] -= 0.5 * (top - bottom);
    }
  }

  enforceNoSlipWalls();
}

/** Both velocity components vanish at all four solid walls. */
function enforceNoSlipWalls() {
  for (var x = 0; x < W; ++x) {
    velU[x] = 0;
    velV[x] = 0;
    var top = H1 * W + x;
    velU[top] = 0;
    velV[top] = 0;
  }
  for (var y = 1; y < H1; ++y) {
    var left = y * W;
    var right = left + W1;
    velU[left] = 0;
    velV[left] = 0;
    velU[right] = 0;
    velV[right] = 0;
  }
}

/** Carry dye through projected velocity and apply unavoidable exponential loss. */
function advectAndDecayDye(dt) {
  // Even at zero the half-life is finite (about 14 seconds), satisfying the
  // always-decaying contract. The upper end clears in roughly a tenth second.
  var decayPerSecond = lerpValue(0.05, 7, decayRate * decayRate);
  var retention = Math.exp(-decayPerSecond * dt);

  for (var y = 0; y < H; ++y) {
    for (var x = 0; x < W; ++x) {
      var i = y * W + x;
      dye2[i] = sampleField(dye, x - velU[i] * dt, y - velV[i] * dt) * retention;
    }
  }
  var swap = dye; dye = dye2; dye2 = swap;
}

function simulate(dt) {
  updateSource(dt);
  advectVelocity(dt);
  diffuseVelocity(dt);
  injectSource(dt);
  applyVorticityConfinement(dt);
  projectVelocity();
  advectAndDecayDye(dt);
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (velU == null) {
    init();
  }

  var elapsed = isFinite(deltaMs) ? clampValue(deltaMs / 1000, 0, 0.25) : 0;
  var step = 1 / SIM_HZ;
  simAccumulator += elapsed;
  var budget = MAX_SUBSTEPS * step;
  if (simAccumulator > budget) {
    simAccumulator = budget;
  }
  while (simAccumulator >= step) {
    simAccumulator -= step;
    simulate(step);
  }
}

function renderPoint(point, deltaMs) {
  var value = clampValue(sampleField(dye, point.xn * W1, point.yn * H1), 0, 1);
  var channel = Math.round(value * 255);
  return rgb(channel, channel, channel);
}

function clampValue(value, low, high) {
  return Math.max(low, Math.min(high, value));
}

function lerpValue(a, b, amount) {
  return a + (b - a) * amount;
}
