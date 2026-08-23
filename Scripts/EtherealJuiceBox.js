/**
 * Ethereal Juice Box
 *
 * A 40x40 closed-box fluid carries short-lived fluorescent particles. A
 * controllable solid source stamps white dye and transfers the rigid-body
 * velocity of its translated and rotated shape into every cell it sweeps.
 */

var FloatArray = Java.type("float[]");

var GRID_SIZE = 40;
var SIM_HZ = 60;
var MAX_SUBSTEPS = 4;
var DIFFUSION_ITERATIONS = 16;
var PRESSURE_ITERATIONS = 24;
var VORTICITY_CONFINEMENT = 6;
var MAX_PARTICLES = 2048;
var TAU = Math.PI * 2;

// Settings row
knob("viscosity", "Viscosity", "Velocity diffusion; 0 is fluid, 1 is thick and smooth", 0.3);
knob("particleRate", "Particle Rate", "Ambient particles emitted per second", 0.3);
knob("particleLifespan", "Particle Lifespan", "Particle lifetime from 1 to 10 seconds", 0.45);
knob("decayRate", "Decay Rate", "How quickly emitted source material dims to zero", 0.35);
knob("gammaCorrection", "Gamma Correction", "Shape the source-value to output-brightness curve; 50% is neutral", 0.5);
knob("particleAmplitude", "Particle Amplitude", "Particle source emission multiplier from 0.5x to 10x", 0.2313782132);

// Momentary buttons: true while held and false when released.
trigger("b1", "B1", "Emit particles from the source surface and pulse outward", onB1);
trigger("b2", "B2", "Pull the fluid and its particles toward the source", onB2);
trigger("b3", "B3", "Apply random impulses throughout the fluid", onB3);
trigger("b4", "B4", "Reserved momentary button", onB4);
trigger("b5", "B5", "Reserved momentary button", onB5);
trigger("b6", "B6", "Reserved momentary button", onB6);

// Latched switches. T1/T2 form a two-bit shape selector.
toggle("t1", "T1", "Source shape selector high bit", false);
toggle("t2", "T2", "Source shape selector low bit", false);
toggle("t3", "T3", "Reserved toggle", false);
toggle("t4", "T4", "Reserved toggle", false);
toggle("t5", "T5", "Reserved toggle", false);
toggle("t6", "T6", "Reserved toggle", false);

// K1/K2 position the source. K3 is bidirectional rotation with still at 50%.
knob("k1", "K1", "Source X position", 0.5);
knob("k2", "K2", "Source Y position", 0.5);
knob("k3", "K3", "Source rotation rate; 50% is stopped", 0.5);
knob("k4", "K4", "Reserved rotary control", 0.5);
knob("k5", "K5", "Reserved rotary control", 0.5);
knob("k6", "K6", "Reserved rotary control", 0.5);

var W, H, W1, H1, cells;
var velU, velV, velU2, velV2, diffusionU, diffusionV;
var dye, dye2, pressure, pressure2, divergence, curl;

var particles = [];
var ambientEmissionAccumulator = 0;
var surfaceEmissionAccumulator = 0;
var simAccumulator = 0;

var sourceX = 0.5;
var sourceY = 0.5;
var sourcePrevX = 0.5;
var sourcePrevY = 0.5;
var sourceAngle = 0;
var sourcePrevAngle = 0;
var sourceAngularVelocity = 0;
var sourceShape = 0;
var sourcePrevShape = 0;
var sourceInitialized = false;

// A press is also queued so a very quick tap cannot fall between fixed steps.
var queuedB1 = 0;
var queuedB2 = 0;
var queuedB3 = 0;

function onB1() { ++queuedB1; }
function onB2() { ++queuedB2; }
function onB3() { ++queuedB3; }
function onB4() {}
function onB5() {}
function onB6() {}

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

  particles = [];
  ambientEmissionAccumulator = 0;
  surfaceEmissionAccumulator = 0;
  simAccumulator = 0;
  sourceX = sourcePrevX = 0.5;
  sourceY = sourcePrevY = 0.5;
  sourceAngle = sourcePrevAngle = 0;
  sourceShape = sourcePrevShape = 0;
  sourceInitialized = false;
  queuedB1 = queuedB2 = queuedB3 = 0;
}

function selectedShape() {
  return (t1 ? 2 : 0) + (t2 ? 1 : 0);
}

/**
 * Parametric source boundary. Parameter p travels once around the outer edge
 * over [0,1). Shape order: circle, oblong squircle, four-cusp star, flower.
 * The returned coordinates are local to the source and normalized to the box.
 */
function sourceSurfacePointLocal(p, shape) {
  var theta = TAU * wrap01(p);
  var c = Math.cos(theta);
  var s = Math.sin(theta);

  if (shape === 1) {
    return {
      x: 0.13 * signValue(c) * Math.sqrt(Math.abs(c)),
      y: 0.075 * signValue(s) * Math.sqrt(Math.abs(s))
    };
  }
  if (shape === 2) {
    return { x: 0.115 * c * c * c, y: 0.115 * s * s * s };
  }
  var radius = shape === 3 ? 0.09 + 0.028 * Math.cos(6 * theta) : 0.095;
  return { x: radius * c, y: radius * s };
}

/** Current source boundary position as a function of p in [0,1). */
function sourceSurfacePoint(p) {
  return localToWorld(sourceSurfacePointLocal(p, sourceShape), sourceX, sourceY, sourceAngle);
}

/** Outward unit normal of the current source boundary at p in [0,1). */
function sourceSurfaceNormal(p) {
  var epsilon = 0.0001;
  var before = sourceSurfacePointLocal(p - epsilon, sourceShape);
  var after = sourceSurfacePointLocal(p + epsilon, sourceShape);
  var tx = after.x - before.x;
  var ty = after.y - before.y;
  var nx = ty;
  var ny = -tx;
  var center = sourceSurfacePointLocal(p, sourceShape);
  if (nx * center.x + ny * center.y < 0) {
    nx = -nx;
    ny = -ny;
  }
  var length = Math.sqrt(nx * nx + ny * ny);
  if (length < 1e-9) {
    length = Math.sqrt(center.x * center.x + center.y * center.y) || 1;
    nx = center.x;
    ny = center.y;
  }
  var localNx = nx / length;
  var localNy = ny / length;
  var c = Math.cos(sourceAngle);
  var s = Math.sin(sourceAngle);
  return { x: c * localNx - s * localNy, y: s * localNx + c * localNy };
}

function localToWorld(local, cx, cy, angle) {
  var c = Math.cos(angle);
  var s = Math.sin(angle);
  return { x: cx + c * local.x - s * local.y, y: cy + s * local.x + c * local.y };
}

function sourceContainsWorld(x, y, cx, cy, angle, shape) {
  var c = Math.cos(angle);
  var s = Math.sin(angle);
  var dx = x - cx;
  var dy = y - cy;
  var lx = c * dx + s * dy;
  var ly = -s * dx + c * dy;
  var ax = Math.abs(lx);
  var ay = Math.abs(ly);

  if (shape === 0) {
    return lx * lx + ly * ly <= 0.095 * 0.095;
  }
  if (shape === 1) {
    var sx = ax / 0.13;
    var sy = ay / 0.075;
    return sx * sx * sx * sx + sy * sy * sy * sy <= 1;
  }
  if (shape === 2) {
    return Math.pow(ax / 0.115, 2 / 3) + Math.pow(ay / 0.115, 2 / 3) <= 1;
  }

  var theta = Math.atan2(ly, lx);
  var boundary = 0.09 + 0.028 * Math.cos(6 * theta);
  return lx * lx + ly * ly <= boundary * boundary;
}

function updateSource(dt) {
  sourcePrevX = sourceX;
  sourcePrevY = sourceY;
  sourcePrevAngle = sourceAngle;
  sourcePrevShape = sourceShape;

  sourceX = clampValue(k1, 0, 1);
  sourceY = clampValue(k2, 0, 1);
  sourceShape = selectedShape();
  sourceAngularVelocity = (k3 - 0.5) * TAU;
  sourceAngle = wrapAngle(sourceAngle + sourceAngularVelocity * dt);

  // Initial placement and a discrete shape mutation do not create a spurious
  // full-box sweep. Translation and rotation begin on the following step.
  if (!sourceInitialized || sourceShape !== sourcePrevShape) {
    sourcePrevX = sourceX;
    sourcePrevY = sourceY;
    sourcePrevAngle = sourceAngle;
    sourcePrevShape = sourceShape;
    sourceInitialized = true;
  }
}

function ambientRatePerSecond() {
  return 36 * particleRate * particleRate;
}

function currentParticleLifespan() {
  return 1 + 9 * particleLifespan;
}

function addParticle(x, y) {
  if (particles.length >= MAX_PARTICLES) {
    particles.shift();
  }
  particles.push({
    x: clampValue(x, 0, 1),
    y: clampValue(y, 0, 1),
    age: 0,
    lifespan: currentParticleLifespan()
  });
}

function emitAmbientParticles(dt) {
  ambientEmissionAccumulator += ambientRatePerSecond() * dt;
  while (ambientEmissionAccumulator >= 1) {
    ambientEmissionAccumulator -= 1;
    addParticle(Math.random(), Math.random());
  }
}

function emitSurfaceParticleAndPulse() {
  var p = Math.random();
  var position = sourceSurfacePoint(p);
  var normal = sourceSurfaceNormal(p);
  addParticle(position.x, position.y);

  var gx = position.x * W1;
  var gy = position.y * H1;
  var pulseRadius = 3;
  var minX = Math.max(1, Math.floor(gx - pulseRadius));
  var maxX = Math.min(W1 - 1, Math.ceil(gx + pulseRadius));
  var minY = Math.max(1, Math.floor(gy - pulseRadius));
  var maxY = Math.min(H1 - 1, Math.ceil(gy + pulseRadius));
  for (var y = minY; y <= maxY; ++y) {
    for (var x = minX; x <= maxX; ++x) {
      var dx = x - gx;
      var dy = y - gy;
      var distance = Math.sqrt(dx * dx + dy * dy);
      if (distance >= pulseRadius) continue;
      var falloff = 1 - distance / pulseRadius;
      var i = y * W + x;
      velU[i] += normal.x * 18 * falloff;
      velV[i] += normal.y * 18 * falloff;
    }
  }
}

function handleB1(dt) {
  var held = b1;
  if (queuedB1 > 0) {
    --queuedB1;
    emitSurfaceParticleAndPulse();
  }
  if (held) {
    surfaceEmissionAccumulator += Math.max(10, ambientRatePerSecond()) * dt;
    while (surfaceEmissionAccumulator >= 1) {
      surfaceEmissionAccumulator -= 1;
      emitSurfaceParticleAndPulse();
    }
  } else {
    surfaceEmissionAccumulator = 0;
  }
}

/** Transfer rigid translation and rotation into every cell the source sweeps. */
function applySourceSweep(dt) {
  var dx = sourceX - sourcePrevX;
  var dy = sourceY - sourcePrevY;
  var da = shortestAngleDifference(sourceAngle, sourcePrevAngle);
  if (Math.abs(dx) + Math.abs(dy) + Math.abs(da) < 1e-9) {
    return;
  }
  var travelCells = Math.sqrt(dx * dx + dy * dy) * Math.max(W1, H1);
  var rotationalCells = Math.abs(da) * 0.13 * Math.max(W1, H1);
  var samples = Math.max(1, Math.min(96, Math.ceil(Math.max(travelCells, rotationalCells) * 2)));
  var translationU = dx * W1 / dt;
  var translationV = dy * H1 / dt;
  var angularVelocity = da / dt;
  var coupling = 0.72;

  for (var y = 1; y < H1; ++y) {
    var yn = y / H1;
    for (var x = 1; x < W1; ++x) {
      var xn = x / W1;
      var hit = false;
      var hitCx = sourceX;
      var hitCy = sourceY;
      for (var sample = 0; sample <= samples; ++sample) {
        var amount = sample / samples;
        var cx = sourcePrevX + dx * amount;
        var cy = sourcePrevY + dy * amount;
        var angle = sourcePrevAngle + da * amount;
        if (sourceContainsWorld(xn, yn, cx, cy, angle, sourceShape)) {
          hit = true;
          hitCx = cx;
          hitCy = cy;
          break;
        }
      }
      if (!hit) continue;

      var rigidU = translationU - angularVelocity * (yn - hitCy) * W1;
      var rigidV = translationV + angularVelocity * (xn - hitCx) * H1;
      var i = y * W + x;
      velU[i] += (rigidU - velU[i]) * coupling;
      velV[i] += (rigidV - velV[i]) * coupling;
    }
  }
}

function applyAttraction(dt, active) {
  if (!active) return;
  var strength = 8 * dt;
  var softening = 0.035;
  for (var y = 1; y < H1; ++y) {
    var yn = y / H1;
    for (var x = 1; x < W1; ++x) {
      var xn = x / W1;
      var dx = sourceX - xn;
      var dy = sourceY - yn;
      var r = Math.sqrt(dx * dx + dy * dy);
      var inverseR = 1 / Math.max(softening, r);
      var i = y * W + x;
      velU[i] += dx * inverseR * inverseR * strength * W1;
      velV[i] += dy * inverseR * inverseR * strength * H1;
    }
  }
}

function applyRandomFieldPulse(dt, active) {
  if (!active) return;
  var strength = 42 * Math.sqrt(dt);
  for (var y = 1; y < H1; ++y) {
    for (var x = 1; x < W1; ++x) {
      var i = y * W + x;
      velU[i] += (Math.random() * 2 - 1) * strength;
      velV[i] += (Math.random() * 2 - 1) * strength;
    }
  }
}

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
  var lower = field[row0 + x0] + (field[row0 + x1] - field[row0 + x0]) * tx;
  var upper = field[row1 + x0] + (field[row1 + x1] - field[row1 + x0]) * tx;
  return lower + (upper - lower) * ty;
}

function advectVelocity(dt) {
  for (var y = 0; y < H; ++y) {
    for (var x = 0; x < W; ++x) {
      var i = y * W + x;
      velU2[i] = sampleField(velU, x - velU[i] * dt, y - velV[i] * dt);
      velV2[i] = sampleField(velV, x - velU[i] * dt, y - velV[i] * dt);
    }
  }
  var swapU = velU; velU = velU2; velU2 = swapU;
  var swapV = velV; velV = velV2; velV2 = swapV;
  enforceNoSlipWalls();
}

function diffuseVelocity(dt) {
  var nu = viscosity * viscosity * 18;
  if (nu <= 0) return;
  var a = nu * dt;
  var denominator = 1 + 4 * a;
  for (var i = 0; i < cells; ++i) {
    diffusionU[i] = velU[i];
    diffusionV[i] = velV[i];
  }
  for (var iteration = 0; iteration < DIFFUSION_ITERATIONS; ++iteration) {
    for (var y = 0; y < H; ++y) {
      for (var x = 0; x < W; ++x) {
        var index = y * W + x;
        if (x === 0 || x === W1 || y === 0 || y === H1) {
          velU2[index] = velV2[index] = 0;
        } else {
          velU2[index] = (diffusionU[index] + a * (velU[index - 1] + velU[index + 1] + velU[index - W] + velU[index + W])) / denominator;
          velV2[index] = (diffusionV[index] + a * (velV[index - 1] + velV[index + 1] + velV[index - W] + velV[index + W])) / denominator;
        }
      }
    }
    var swapU = velU; velU = velU2; velU2 = swapU;
    var swapV = velV; velV = velV2; velV2 = swapV;
  }
}

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
      if (magnitude < 1e-5) continue;
      var force = curl[j] * VORTICITY_CONFINEMENT * dt / magnitude;
      velU[j] += gy * force;
      velV[j] -= gx * force;
    }
  }
}

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
      var left2 = x2 > 0 ? pressure[index - 1] : centerP;
      var right2 = x2 < W1 ? pressure[index + 1] : centerP;
      var bottom2 = y2 > 0 ? pressure[index - W] : centerP;
      var top2 = y2 < H1 ? pressure[index + W] : centerP;
      velU[index] -= 0.5 * (right2 - left2);
      velV[index] -= 0.5 * (top2 - bottom2);
    }
  }
  enforceNoSlipWalls();
}

function enforceNoSlipWalls() {
  for (var x = 0; x < W; ++x) {
    velU[x] = velV[x] = 0;
    var top = H1 * W + x;
    velU[top] = velV[top] = 0;
  }
  for (var y = 1; y < H1; ++y) {
    var left = y * W;
    var right = left + W1;
    velU[left] = velV[left] = 0;
    velU[right] = velV[right] = 0;
  }
}

function advectParticles(dt) {
  for (var i = particles.length - 1; i >= 0; --i) {
    var particle = particles[i];
    particle.age += dt;
    if (particle.age >= particle.lifespan) {
      particles.splice(i, 1);
      continue;
    }
    var gx = particle.x * W1;
    var gy = particle.y * H1;
    particle.x = clampValue(particle.x + sampleField(velU, gx, gy) * dt / W1, 0, 1);
    particle.y = clampValue(particle.y + sampleField(velV, gx, gy) * dt / H1, 0, 1);
  }
}

function advectAndDecayDye(dt) {
  var decayPerSecond = 0.05 + 6.95 * decayRate * decayRate;
  var retention = Math.exp(-decayPerSecond * dt);
  for (var y = 0; y < H; ++y) {
    for (var x = 0; x < W; ++x) {
      var i = y * W + x;
      dye2[i] = sampleField(dye, x - velU[i] * dt, y - velV[i] * dt) * retention;
    }
  }
  var swap = dye; dye = dye2; dye2 = swap;
}

/** A particle is a one-texel soft emitter with a sinusoidal 0 -> 1 -> 0 cycle. */
function emitParticleDye() {
  var radius = 1.25;
  var amplitude = 0.5 * Math.pow(20, particleAmplitude);
  for (var p = 0; p < particles.length; ++p) {
    var particle = particles[p];
    var emission = amplitude * Math.sin(Math.PI * particle.age / particle.lifespan);
    var gx = particle.x * W1;
    var gy = particle.y * H1;
    var minX = Math.max(0, Math.floor(gx - radius));
    var maxX = Math.min(W1, Math.ceil(gx + radius));
    var minY = Math.max(0, Math.floor(gy - radius));
    var maxY = Math.min(H1, Math.ceil(gy + radius));
    for (var y = minY; y <= maxY; ++y) {
      for (var x = minX; x <= maxX; ++x) {
        var dx = x - gx;
        var dy = y - gy;
        var distance = Math.sqrt(dx * dx + dy * dy);
        if (distance >= radius) continue;
        var falloff = 1 - distance / radius;
        var value = emission * falloff * falloff;
        var i = y * W + x;
        if (value > dye[i]) dye[i] = value;
      }
    }
  }
}

/** The source itself is always a solid, maximum-value emitter. */
function stampSolidSource() {
  for (var y = 0; y < H; ++y) {
    var yn = y / H1;
    for (var x = 0; x < W; ++x) {
      if (sourceContainsWorld(x / W1, yn, sourceX, sourceY, sourceAngle, sourceShape)) {
        dye[y * W + x] = 1;
      }
    }
  }
}

function simulate(dt) {
  updateSource(dt);
  emitAmbientParticles(dt);
  advectVelocity(dt);
  diffuseVelocity(dt);
  applySourceSweep(dt);
  handleB1(dt);

  var attractionActive = b2 || queuedB2 > 0;
  var randomPulseActive = b3 || queuedB3 > 0;
  applyAttraction(dt, attractionActive);
  applyRandomFieldPulse(dt, randomPulseActive);
  if (queuedB2 > 0) --queuedB2;
  if (queuedB3 > 0) --queuedB3;

  applyVorticityConfinement(dt);
  projectVelocity();
  advectParticles(dt);
  advectAndDecayDye(dt);
  emitParticleDye();
  stampSolidSource();
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (velU == null) init();
  var elapsed = isFinite(deltaMs) ? clampValue(deltaMs / 1000, 0, 0.25) : 0;
  var step = 1 / SIM_HZ;
  simAccumulator = Math.min(simAccumulator + elapsed, MAX_SUBSTEPS * step);
  while (simAccumulator >= step) {
    simAccumulator -= step;
    simulate(step);
  }
}

function renderPoint(point, deltaMs) {
  var value = clampValue(sampleField(dye, point.xn * W1, point.yn * H1), 0, 1);
  var gamma = Math.pow(4, gammaCorrection * 2 - 1);
  value = Math.pow(value, gamma);
  var channel = Math.round(value * 255);
  return rgb(channel, channel, channel);
}

function clampValue(value, low, high) {
  return Math.max(low, Math.min(high, value));
}

function signValue(value) {
  return value < 0 ? -1 : 1;
}

function wrap01(value) {
  value %= 1;
  return value < 0 ? value + 1 : value;
}

function wrapAngle(value) {
  value %= TAU;
  return value < 0 ? value + TAU : value;
}

function shortestAngleDifference(a, b) {
  var difference = a - b;
  while (difference > Math.PI) difference -= TAU;
  while (difference < -Math.PI) difference += TAU;
  return difference;
}
