/**
 * Ethereal Juice Box
 *
 * A 40x40 closed-box fluid carries short-lived fluorescent particles.
 *
 * Two solid agents live in the box and stamp white dye. Each transfers the
 * rigid-body velocity of its translated and rotated form into every cell it
 * sweeps, and each has an alternate five-fold form that spins:
 *
 *   Disc  K1/K2   T1 blows outward and emits.  T2 becomes five orbiting dots.
 *   Ring  K3/K4   T3 sucks inward.             T4 becomes five outward rays.
 *
 * Two more controls fire between opposing nodes on the box edge, each pair
 * placed by its own angle knob:
 *
 *   Sweep K5      T5 drives a pair of converging or diverging pressure fronts.
 *   Bolt  K6      T6 lays a jagged lightning bolt clear across the box.
 */

var FloatArray = Java.type("float[]");

var GRID_SIZE = 40;
var SIM_HZ = 60;
var MAX_SUBSTEPS = 4;
var DIFFUSION_ITERATIONS = 16;
var PRESSURE_ITERATIONS = 24;
var MAX_VORTICITY_CONFINEMENT = 36;
var MAX_PARTICLES = 2048;
var B1_PARTICLE_BURST = 40;
var B4_SWEEP_SECONDS = 0.3;
var B4_FORCE_REFERENCE_SECONDS = 0.4;
var B4_FORCE_MULTIPLIER = 3;
var TAU = Math.PI * 2;

// Agent forms. All extents are multiples of the shared agent radius, so the
// Agent Radius knob scales every form coherently.
var AGENT_DISC = 0;
var AGENT_RING = 1;
var ARM_COUNT = 5;
var SATELLITE_ORBIT = 1.35;
var SATELLITE_RADIUS = 0.32;
var RING_HALF_WIDTH = 0.25;
var RAY_INNER = 0.45;
var RAY_OUTER = 1.5;
var RAY_HALF_WIDTH = 0.2;

// T1 and T3 radial fields. A radial field is pure divergence, so both are
// applied after the pressure projection; see the note in simulate().
var RADIAL_REACH = 3;
var RADIAL_STRENGTH = 40;
var T1_EMISSION_RATE = 45;

// The two edge node pairs. Level is how brightly each pair marks itself.
var NODE_RADIUS = 0.03;
var SWEEP_NODE_LEVEL = 0.7;
var BOLT_NODE_LEVEL = 1;

// T5 sweep.
var SWEEP_SECONDS = 0.5;
var SWEEP_FRONT_HALF_WIDTH = 1.15;
var SWEEP_FORCE = 200;

// T6 bolt.
var BOLT_SEGMENTS = 5;
var BOLT_JAGGEDNESS = 0.11;
var BOLT_DYE_RADIUS = 1.1;
var BOLT_PARTICLES = 25;
var BOLT_PARTICLE_SPEED = 26;
var BOLT_IMPULSE = 260;
var BOLT_IMPULSE_RADIUS = 2.2;

// Settings row
knob("viscosity", "Viscosity", "Velocity diffusion; 0 is fluid, 1 is thick and smooth", 0.62);
knob("turbulence", "Turbulence", "Restore fluid curls lost to interpolation; 0 is smooth", 0.5);
knob("particleRate", "Particle Rate", "Ambient particles emitted per second", 1);
knob("particleLifespan", "Particle Lifespan", "Particle lifetime from 1 to 10 seconds; takes effect on newly emitted particles only", 0);
knob("decayRate", "Decay Rate", "How quickly emitted source material dims to zero", 1);
knob("gammaCorrection", "Gamma Correction", "Shape the source-value to output-brightness curve; 50% is neutral", 0.25);
knob("particleAmplitude", "Particle Amplitude", "Particle source emission multiplier from 0.5x to 10x", 0.3);
knob("agentRadius", "Agent Radius", "Fixed radius shared by the disc and the ring", 0.5);
knob("spinRate", "Spin Rate", "How fast the orbiting dots and the sun rays rotate", 0.4);

// Momentary buttons: true while held and false when released. B2 is the one
// exception; it latches, so the pull stays on until it is switched off.
trigger("b1", "B1", "Emit particles from the disc surface and pulse outward", onB1);
toggle("b2", "B2", "Pull the fluid and its particles toward the disc", false);
trigger("b3", "B3", "Apply random impulses throughout the fluid", onB3);
trigger("b4", "B4", "Sweep inward from both edges to the disc X position", onB4);
trigger("b5", "B5", "Reserved momentary button", onB5);
trigger("b6", "B6", "Reserved momentary button", onB6);

// Latched switches. T5 and T6 act on the flip rather than on the position, so
// both directions do something and neither has a resting state that is "off".
toggle("t1", "T1", "Disc blows the fluid outward and emits particles from its surface", false);
toggle("t2", "T2", "Disc becomes five dots orbiting just outside its radius", false);
toggle("t3", "T3", "Ring sucks the fluid inward like a vacuum", false);
toggle("t4", "T4", "Ring becomes five outward rays, like a sun", false);
toggle("t5", "T5", "Flip in to sweep the box inward, flip out to sweep it back outward", false);
toggle("t6", "T6", "Either flip fires a lightning bolt between the two edge nodes", false);

// K1/K2 and K3/K4 place the two agents. K5 and K6 rotate the two edge node
// pairs; each pair is opposed, so half a turn covers every orientation.
knob("k1", "K1", "Disc X position", 0.5);
knob("k2", "K2", "Disc Y position", 0.5);
knob("k3", "K3", "Ring X position", 0.5);
knob("k4", "K4", "Ring Y position", 0.5);
knob("k5", "K5", "Sweep node angle, 0 to 180 degrees", 0.5);
// Defaulted a quarter turn apart from K5 so the two node pairs do not sit on
// top of each other on load.
knob("k6", "K6", "Bolt node angle, 0 to 180 degrees", 0);

var W, H, W1, H1, cells;
var velU, velV, velU2, velV2, diffusionU, diffusionV;
var dye, dye2, pressure, pressure2, divergence, curl;

var particles = [];
var ambientEmissionAccumulator = 0;
var surfaceEmissionAccumulator = 0;
var thrustEmissionAccumulator = 0;
var simAccumulator = 0;
var renderGamma = 1;

var agents = [];
var agentsInitialized = false;

// T5 and T6 read positions rather than edges, so the previous position is kept
// and the flip is recovered from the difference. They are baselined on the
// first simulated step rather than in init(), which the host calls before the
// parameter variables exist.
var togglesBaselined = false;
var sweepPrevious = false;
var boltPrevious = false;
var sweep = null;
var pendingBolt = null;

// A press is also queued so a very quick tap cannot fall between fixed steps.
var queuedB1 = 0;
var queuedB3 = 0;
var queuedB4 = 0;
var advectionWaves = [];

function onB1() { ++queuedB1; }
function onB3() { ++queuedB3; }
function onB4() { ++queuedB4; }
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
  thrustEmissionAccumulator = 0;
  simAccumulator = 0;

  agents = [
    newAgent(AGENT_DISC),
    newAgent(AGENT_RING)
  ];
  agentsInitialized = false;

  togglesBaselined = false;
  sweep = null;
  pendingBolt = null;

  queuedB1 = queuedB3 = queuedB4 = 0;
  advectionWaves = [];
}

function newAgent(kind) {
  return {
    kind: kind,
    x: 0.5,
    y: 0.5,
    prevX: 0.5,
    prevY: 0.5,
    spin: 0,
    prevSpin: 0,
    alt: false,
    prevAlt: false
  };
}

// ---------------------------------------------------------------- the agents

/** The one radius every agent form is built from. */
function agentRadiusValue() {
  return 0.04 + 0.11 * clampValue(agentRadius, 0, 1);
}

function spinRateRadians() {
  return 3 * clampValue(spinRate, 0, 1);
}

/** How far an agent's form can reach from its own center. */
function agentExtent(agent, radius) {
  if (agent.alt) {
    return agent.kind === AGENT_DISC
      ? radius * (SATELLITE_ORBIT + SATELLITE_RADIUS)
      : radius * RAY_OUTER;
  }
  return agent.kind === AGENT_DISC ? radius : radius * (1 + RING_HALF_WIDTH);
}

/**
 * Whether a point lies inside an agent's solid form.
 *
 * The center and spin are passed in rather than read off the agent so the
 * swept-path test can walk the form back along the motion it made this step.
 * Both alternate forms are five-fold symmetric, so the point is folded onto
 * its nearest arm and one arm is tested instead of five.
 */
function agentContainsAt(agent, px, py, cx, cy, spin, radius) {
  var dx = px - cx;
  var dy = py - cy;
  var distanceSquared = dx * dx + dy * dy;

  if (!agent.alt) {
    if (agent.kind === AGENT_DISC) {
      return distanceSquared <= radius * radius;
    }
    var half = RING_HALF_WIDTH * radius;
    var inner = radius - half;
    var outer = radius + half;
    return distanceSquared >= inner * inner && distanceSquared <= outer * outer;
  }

  var distance = Math.sqrt(distanceSquared);
  var sector = TAU / ARM_COUNT;
  var offset = Math.atan2(dy, dx) - spin;
  offset -= Math.round(offset / sector) * sector;

  if (agent.kind === AGENT_DISC) {
    var orbit = SATELLITE_ORBIT * radius;
    var dotRadius = SATELLITE_RADIUS * radius;
    // Law of cosines from the folded point to that arm's dot center.
    var gap = distanceSquared + orbit * orbit
      - 2 * distance * orbit * Math.cos(offset);
    return gap <= dotRadius * dotRadius;
  }

  if (distance < RAY_INNER * radius || distance > RAY_OUTER * radius) {
    return false;
  }
  return Math.abs(distance * Math.sin(offset)) <= RAY_HALF_WIDTH * radius;
}

/** A point on an agent's outer surface with its outward normal, p in [0,1). */
function agentSurfaceSample(agent, p, radius) {
  p = wrap01(p);

  if (!agent.alt) {
    var theta = TAU * p;
    var c = Math.cos(theta);
    var s = Math.sin(theta);
    var edge = agent.kind === AGENT_DISC ? radius : radius * (1 + RING_HALF_WIDTH);
    return { x: agent.x + edge * c, y: agent.y + edge * s, nx: c, ny: s };
  }

  var armAngle = agent.spin + Math.floor(p * ARM_COUNT) * (TAU / ARM_COUNT);
  var armCos = Math.cos(armAngle);
  var armSin = Math.sin(armAngle);

  if (agent.kind === AGENT_RING) {
    var tip = RAY_OUTER * radius;
    return { x: agent.x + tip * armCos, y: agent.y + tip * armSin, nx: armCos, ny: armSin };
  }

  // The fractional part within the chosen arm doubles as the angle around
  // that arm's dot, so one random number places the sample completely.
  var local = TAU * wrap01(p * ARM_COUNT);
  var localCos = Math.cos(local);
  var localSin = Math.sin(local);
  var orbit = SATELLITE_ORBIT * radius;
  var dotRadius = SATELLITE_RADIUS * radius;
  return {
    x: agent.x + orbit * armCos + dotRadius * localCos,
    y: agent.y + orbit * armSin + dotRadius * localSin,
    nx: localCos,
    ny: localSin
  };
}

function updateAgents(dt) {
  var spinDelta = spinRateRadians() * dt;
  for (var i = 0; i < agents.length; ++i) {
    var agent = agents[i];
    agent.prevX = agent.x;
    agent.prevY = agent.y;
    agent.prevSpin = agent.spin;
    agent.prevAlt = agent.alt;
    agent.spin = wrapAngle(agent.spin + spinDelta);
  }

  agents[0].x = clampValue(k1, 0, 1);
  agents[0].y = clampValue(k2, 0, 1);
  agents[0].alt = t2;
  agents[1].x = clampValue(k3, 0, 1);
  agents[1].y = clampValue(k4, 0, 1);
  agents[1].alt = t4;

  // Initial placement and a discrete form change do not create a spurious
  // full-box sweep. Translation and spin begin on the following step.
  for (var j = 0; j < agents.length; ++j) {
    var subject = agents[j];
    if (!agentsInitialized || subject.alt !== subject.prevAlt) {
      subject.prevX = subject.x;
      subject.prevY = subject.y;
      subject.prevSpin = subject.spin;
      subject.prevAlt = subject.alt;
    }
  }
  agentsInitialized = true;
}

/** Transfer rigid translation and rotation into every cell an agent sweeps. */
function applyAgentSweep(agent, dt, radius) {
  var dx = agent.x - agent.prevX;
  var dy = agent.y - agent.prevY;
  var da = shortestAngleDifference(agent.spin, agent.prevSpin);
  if (Math.abs(dx) + Math.abs(dy) + Math.abs(da) < 1e-9) {
    return;
  }

  var extent = agentExtent(agent, radius);
  var travelCells = Math.sqrt(dx * dx + dy * dy) * Math.max(W1, H1);
  var rotationalCells = Math.abs(da) * extent * Math.max(W1, H1);
  var samples = Math.max(1, Math.min(96, Math.ceil(Math.max(travelCells, rotationalCells) * 2)));
  var translationU = dx * W1 / dt;
  var translationV = dy * H1 / dt;
  var angularVelocity = da / dt;
  var coupling = 0.72;

  // Only the band the form can possibly have touched this step is scanned.
  var minX = Math.max(1, Math.floor((Math.min(agent.x, agent.prevX) - extent) * W1));
  var maxX = Math.min(W1 - 1, Math.ceil((Math.max(agent.x, agent.prevX) + extent) * W1));
  var minY = Math.max(1, Math.floor((Math.min(agent.y, agent.prevY) - extent) * H1));
  var maxY = Math.min(H1 - 1, Math.ceil((Math.max(agent.y, agent.prevY) + extent) * H1));

  for (var y = minY; y <= maxY; ++y) {
    var yn = y / H1;
    for (var x = minX; x <= maxX; ++x) {
      var xn = x / W1;
      var hit = false;
      var hitCx = agent.x;
      var hitCy = agent.y;
      for (var sample = 0; sample <= samples; ++sample) {
        var amount = sample / samples;
        var cx = agent.prevX + dx * amount;
        var cy = agent.prevY + dy * amount;
        var spin = agent.prevSpin + da * amount;
        if (agentContainsAt(agent, xn, yn, cx, cy, spin, radius)) {
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

/** An agent is always a solid, maximum-value emitter. */
function stampAgent(agent, radius) {
  var extent = agentExtent(agent, radius);
  var minX = Math.max(0, Math.floor((agent.x - extent) * W1));
  var maxX = Math.min(W1, Math.ceil((agent.x + extent) * W1));
  var minY = Math.max(0, Math.floor((agent.y - extent) * H1));
  var maxY = Math.min(H1, Math.ceil((agent.y + extent) * H1));

  for (var y = minY; y <= maxY; ++y) {
    var yn = y / H1;
    for (var x = minX; x <= maxX; ++x) {
      if (agentContainsAt(agent, x / W1, yn, agent.x, agent.y, agent.spin, radius)) {
        dye[y * W + x] = 1;
      }
    }
  }
}

/**
 * T1 and T3. Sign +1 blows the fluid away from the agent, -1 draws it in.
 * The falloff is linear rather than inverse-square so there is no singularity
 * sitting inside the solid form.
 */
function applyRadialField(agent, dt, radius, sign) {
  var reach = agentExtent(agent, radius) * RADIAL_REACH;
  var minX = Math.max(1, Math.floor((agent.x - reach) * W1));
  var maxX = Math.min(W1 - 1, Math.ceil((agent.x + reach) * W1));
  var minY = Math.max(1, Math.floor((agent.y - reach) * H1));
  var maxY = Math.min(H1 - 1, Math.ceil((agent.y + reach) * H1));
  var scale = sign * RADIAL_STRENGTH * dt;

  for (var y = minY; y <= maxY; ++y) {
    var yn = y / H1;
    for (var x = minX; x <= maxX; ++x) {
      var xn = x / W1;
      var dx = xn - agent.x;
      var dy = yn - agent.y;
      var distance = Math.sqrt(dx * dx + dy * dy);
      if (distance >= reach) continue;
      var inverse = 1 / Math.max(distance, 1e-4);
      var falloff = (1 - distance / reach) * scale;
      var i = y * W + x;
      velU[i] += dx * inverse * falloff * W1;
      velV[i] += dy * inverse * falloff * H1;
    }
  }
}

// ------------------------------------------------------------- the edge nodes

function sweepAngle() {
  return clampValue(k5, 0, 1) * Math.PI;
}

function boltAngle() {
  return clampValue(k6, 0, 1) * Math.PI;
}

/** Axial distance from the box center to the inset edge along this heading. */
function edgeReach(angle, inset) {
  var c = Math.abs(Math.cos(angle));
  var s = Math.abs(Math.sin(angle));
  var limit = 0.5 - inset;
  var byX = c > 1e-6 ? limit / c : Infinity;
  var byY = s > 1e-6 ? limit / s : Infinity;
  return Math.min(byX, byY);
}

function edgeNodePosition(angle, inset) {
  var reach = edgeReach(angle, inset);
  return { x: 0.5 + Math.cos(angle) * reach, y: 0.5 + Math.sin(angle) * reach };
}

/** Mark a node pair so it is clear where the next sweep or bolt comes from. */
function stampNodePair(angle, level) {
  stampNodeDot(edgeNodePosition(angle, NODE_RADIUS), level);
  stampNodeDot(edgeNodePosition(angle + Math.PI, NODE_RADIUS), level);
}

function stampNodeDot(position, level) {
  var gx = position.x * W1;
  var gy = position.y * H1;
  var radiusCells = NODE_RADIUS * Math.max(W1, H1);
  var minX = Math.max(0, Math.floor(gx - radiusCells));
  var maxX = Math.min(W1, Math.ceil(gx + radiusCells));
  var minY = Math.max(0, Math.floor(gy - radiusCells));
  var maxY = Math.min(H1, Math.ceil(gy + radiusCells));

  for (var y = minY; y <= maxY; ++y) {
    for (var x = minX; x <= maxX; ++x) {
      var dx = x - gx;
      var dy = y - gy;
      if (dx * dx + dy * dy > radiusCells * radiusCells) continue;
      var i = y * W + x;
      if (level > dye[i]) dye[i] = level;
    }
  }
}

// ----------------------------------------------------------------- T5, sweep

/**
 * Flipping T5 in converges a pair of fronts on the box center; flipping it out
 * drives them back to the edge. Only one sweep is ever in flight, so reversing
 * mid-travel abandons the old one on the spot rather than queueing behind it.
 */
function updateSweep(dt) {
  if (t5 !== sweepPrevious) {
    sweepPrevious = t5;
    sweep = { angle: sweepAngle(), outward: !t5, age: 0 };
  }
  if (sweep === null) return;

  var previousTime = clampValue(sweep.age / SWEEP_SECONDS, 0, 1);
  sweep.age = sweep.age + dt >= SWEEP_SECONDS - 1e-9
    ? SWEEP_SECONDS
    : sweep.age + dt;
  var time = clampValue(sweep.age / SWEEP_SECONDS, 0, 1);

  // Quadratic, so both fronts leave slowly and arrive hard.
  var previousProgress = previousTime * previousTime;
  var progress = time * time;

  var reach = edgeReach(sweep.angle, NODE_RADIUS);
  var previousOffset = reach * (sweep.outward ? previousProgress : 1 - previousProgress);
  var currentOffset = reach * (sweep.outward ? progress : 1 - progress);
  var push = sweep.outward ? 1 : -1;

  applySweptFront(sweep.angle, previousOffset, currentOffset, push);
  applySweptFront(sweep.angle, -previousOffset, -currentOffset, -push);

  if (sweep.age >= SWEEP_SECONDS) {
    sweep = null;
  }
}

/**
 * Impulse every cell crossed by one planar front travelling along the sweep
 * axis. The swept interval between the previous and current frontier positions
 * is used rather than the current position alone, so a fast front cannot step
 * over a row of cells and leave a gap behind it.
 */
function applySweptFront(angle, previousOffset, currentOffset, push) {
  var axisCos = Math.cos(angle);
  var axisSin = Math.sin(angle);
  var low = Math.min(previousOffset, currentOffset);
  var high = Math.max(previousOffset, currentOffset);
  var halfWidth = SWEEP_FRONT_HALF_WIDTH / Math.max(W1, H1);
  var forceU = push * axisCos * SWEEP_FORCE;
  var forceV = push * axisSin * SWEEP_FORCE;

  for (var y = 1; y < H1; ++y) {
    var yn = y / H1 - 0.5;
    for (var x = 1; x < W1; ++x) {
      var xn = x / W1 - 0.5;
      var axial = xn * axisCos + yn * axisSin;
      var distance = axial < low ? low - axial : (axial > high ? axial - high : 0);
      if (distance >= halfWidth) continue;
      var falloff = 1 - distance / halfWidth;
      var i = y * W + x;
      velU[i] += forceU * falloff;
      velV[i] += forceV * falloff;
    }
  }
}

// ------------------------------------------------------------------ T6, bolt

/**
 * Either flip of T6 fires. The strike is built and its impulses are applied
 * here, after the projection, but its dye is held over to the stamping phase
 * so the bolt is not advected away in the same step that draws it.
 */
function updateBolt() {
  if (t6 === boltPrevious) return;
  boltPrevious = t6;

  var angle = boltAngle();
  var from = edgeNodePosition(angle, NODE_RADIUS);
  var to = edgeNodePosition(angle + Math.PI, NODE_RADIUS);
  var dx = to.x - from.x;
  var dy = to.y - from.y;
  var length = Math.sqrt(dx * dx + dy * dy) || 1;
  var normalX = -dy / length;
  var normalY = dx / length;

  // The ends are pinned to the nodes and the deflection peaks at mid-span, so
  // the bolt is always jagged in the middle and always lands on both nodes.
  var points = [];
  for (var i = 0; i <= BOLT_SEGMENTS; ++i) {
    var amount = i / BOLT_SEGMENTS;
    var offset = (Math.random() * 2 - 1) * BOLT_JAGGEDNESS * Math.sin(Math.PI * amount);
    points.push({
      x: clampValue(from.x + dx * amount + normalX * offset, 0, 1),
      y: clampValue(from.y + dy * amount + normalY * offset, 0, 1)
    });
  }
  pendingBolt = points;

  for (var p = 0; p < BOLT_PARTICLES; ++p) {
    var position = pointAlongBolt(points, (p + Math.random()) / BOLT_PARTICLES);
    var launchAngle = Math.random() * TAU;
    var speed = BOLT_PARTICLE_SPEED * (0.35 + Math.random());
    addParticle(
      position.x,
      position.y,
      Math.cos(launchAngle) * speed,
      Math.sin(launchAngle) * speed
    );
    applyBoltImpulse(position.x, position.y);
  }
}

function pointAlongBolt(points, amount) {
  var span = clampValue(amount, 0, 1) * BOLT_SEGMENTS;
  var index = Math.min(BOLT_SEGMENTS - 1, Math.floor(span));
  var local = span - index;
  var a = points[index];
  var b = points[index + 1];
  return { x: a.x + (b.x - a.x) * local, y: a.y + (b.y - a.y) * local };
}

function applyBoltImpulse(x, y) {
  var gx = x * W1;
  var gy = y * H1;
  var angle = Math.random() * TAU;
  var impulseU = Math.cos(angle) * BOLT_IMPULSE;
  var impulseV = Math.sin(angle) * BOLT_IMPULSE;
  var minX = Math.max(1, Math.floor(gx - BOLT_IMPULSE_RADIUS));
  var maxX = Math.min(W1 - 1, Math.ceil(gx + BOLT_IMPULSE_RADIUS));
  var minY = Math.max(1, Math.floor(gy - BOLT_IMPULSE_RADIUS));
  var maxY = Math.min(H1 - 1, Math.ceil(gy + BOLT_IMPULSE_RADIUS));

  for (var cy = minY; cy <= maxY; ++cy) {
    for (var cx = minX; cx <= maxX; ++cx) {
      var dx = cx - gx;
      var dy = cy - gy;
      var distance = Math.sqrt(dx * dx + dy * dy);
      if (distance >= BOLT_IMPULSE_RADIUS) continue;
      var falloff = 1 - distance / BOLT_IMPULSE_RADIUS;
      var i = cy * W + cx;
      velU[i] += impulseU * falloff;
      velV[i] += impulseV * falloff;
    }
  }
}

function stampPendingBolt() {
  if (pendingBolt === null) return;
  for (var s = 0; s < BOLT_SEGMENTS; ++s) {
    stampBoltSegment(pendingBolt[s], pendingBolt[s + 1]);
  }
  pendingBolt = null;
}

function stampBoltSegment(a, b) {
  var dx = b.x - a.x;
  var dy = b.y - a.y;
  var cellLength = Math.sqrt(dx * dx * W1 * W1 + dy * dy * H1 * H1);
  var steps = Math.max(1, Math.ceil(cellLength * 2));
  for (var i = 0; i <= steps; ++i) {
    var amount = i / steps;
    stampBoltPoint(a.x + dx * amount, a.y + dy * amount);
  }
}

function stampBoltPoint(x, y) {
  var gx = x * W1;
  var gy = y * H1;
  var minX = Math.max(0, Math.floor(gx - BOLT_DYE_RADIUS));
  var maxX = Math.min(W1, Math.ceil(gx + BOLT_DYE_RADIUS));
  var minY = Math.max(0, Math.floor(gy - BOLT_DYE_RADIUS));
  var maxY = Math.min(H1, Math.ceil(gy + BOLT_DYE_RADIUS));

  for (var cy = minY; cy <= maxY; ++cy) {
    for (var cx = minX; cx <= maxX; ++cx) {
      var dx = cx - gx;
      var dy = cy - gy;
      var distance = Math.sqrt(dx * dx + dy * dy);
      if (distance >= BOLT_DYE_RADIUS) continue;
      var value = BOLT_NODE_LEVEL * (1 - 0.35 * distance / BOLT_DYE_RADIUS);
      var i = cy * W + cx;
      if (value > dye[i]) dye[i] = value;
    }
  }
}

// -------------------------------------------------------------- the particles

function ambientRatePerSecond() {
  return 36 * particleRate * particleRate;
}

function currentParticleLifespan() {
  return 1 + 9 * particleLifespan;
}

function addParticle(x, y, launchU, launchV) {
  if (particles.length >= MAX_PARTICLES) {
    // Nothing reads the list in order, so the tail fills the vacated slot
    // rather than shifting two thousand entries down by one.
    particles[0] = particles[particles.length - 1];
    particles.pop();
  }
  particles.push({
    x: clampValue(x, 0, 1),
    y: clampValue(y, 0, 1),
    age: 0,
    lifespan: currentParticleLifespan(),
    launchU: launchU || 0,
    launchV: launchV || 0
  });
}

function emitAmbientParticles(dt) {
  ambientEmissionAccumulator += ambientRatePerSecond() * dt;
  while (ambientEmissionAccumulator >= 1) {
    ambientEmissionAccumulator -= 1;
    addParticle(Math.random(), Math.random());
  }
}

function emitSurfaceParticleAndPulse(agent, radius) {
  var sample = agentSurfaceSample(agent, Math.random(), radius);
  var impulseScale = b2 ? 4 : 2;
  // Start just outside the solid form so the new particle is immediately
  // visible, then give it a strong short-lived outward launch velocity.
  var x = sample.x + sample.nx * 0.75 / W1;
  var y = sample.y + sample.ny * 0.75 / H1;
  addParticle(x, y, sample.nx * 21 * impulseScale, sample.ny * 21 * impulseScale);

  var gx = x * W1;
  var gy = y * H1;
  var pulseRadius = 3;
  var minX = Math.max(1, Math.floor(gx - pulseRadius));
  var maxX = Math.min(W1 - 1, Math.ceil(gx + pulseRadius));
  var minY = Math.max(1, Math.floor(gy - pulseRadius));
  var maxY = Math.min(H1 - 1, Math.ceil(gy + pulseRadius));
  for (var cy = minY; cy <= maxY; ++cy) {
    for (var cx = minX; cx <= maxX; ++cx) {
      var dx = cx - gx;
      var dy = cy - gy;
      var distance = Math.sqrt(dx * dx + dy * dy);
      if (distance >= pulseRadius) continue;
      var falloff = 1 - distance / pulseRadius;
      var i = cy * W + cx;
      velU[i] += sample.nx * 18 * impulseScale * falloff;
      velV[i] += sample.ny * 18 * impulseScale * falloff;
    }
  }
}

function handleB1(dt, radius) {
  var agent = agents[0];
  while (queuedB1 > 0) {
    --queuedB1;
    for (var burstParticle = 0; burstParticle < B1_PARTICLE_BURST; ++burstParticle) {
      emitSurfaceParticleAndPulse(agent, radius);
    }
  }
  if (b1) {
    surfaceEmissionAccumulator += Math.max(10, ambientRatePerSecond()) * dt;
    while (surfaceEmissionAccumulator >= 1) {
      surfaceEmissionAccumulator -= 1;
      emitSurfaceParticleAndPulse(agent, radius);
    }
  } else {
    surfaceEmissionAccumulator = 0;
  }
}

/** The steady surface emission T1 adds on top of its outward field. */
function handleT1Emission(dt, radius) {
  if (!t1) {
    thrustEmissionAccumulator = 0;
    return;
  }
  thrustEmissionAccumulator += T1_EMISSION_RATE * dt;
  while (thrustEmissionAccumulator >= 1) {
    thrustEmissionAccumulator -= 1;
    emitSurfaceParticleAndPulse(agents[0], radius);
  }
}

function advectParticles(dt) {
  for (var i = particles.length - 1; i >= 0; --i) {
    var particle = particles[i];
    particle.age += dt;
    if (particle.age >= particle.lifespan) {
      particles[i] = particles[particles.length - 1];
      particles.pop();
      continue;
    }
    var gx = particle.x * W1;
    var gy = particle.y * H1;
    var carriedU = sampleField(velU, gx, gy) + particle.launchU;
    var carriedV = sampleField(velV, gx, gy) + particle.launchV;
    particle.x = clampValue(particle.x + carriedU * dt / W1, 0, 1);
    particle.y = clampValue(particle.y + carriedV * dt / H1, 0, 1);
    var launchRetention = Math.exp(-3.5 * dt);
    particle.launchU *= launchRetention;
    particle.launchV *= launchRetention;
  }
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

// ---------------------------------------------------------- the momentary row

function applyAttraction(dt, active) {
  if (!active) return;
  var agent = agents[0];
  var strength = 3.2 * dt;
  var softening = 0.035;
  for (var y = 1; y < H1; ++y) {
    var yn = y / H1;
    for (var x = 1; x < W1; ++x) {
      var xn = x / W1;
      var dx = agent.x - xn;
      var dy = agent.y - yn;
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
  var strength = 630 * Math.sqrt(dt);
  for (var y = 1; y < H1; ++y) {
    for (var x = 1; x < W1; ++x) {
      var i = y * W + x;
      velU[i] += (Math.random() * 2 - 1) * strength;
      velV[i] += (Math.random() * 2 - 1) * strength;
    }
  }
}

/** Apply an inward impulse to every texel crossed by one vertical wavefront. */
function applySweptVerticalFront(previousX, currentX, direction, frontierSpeed) {
  var previousGridX = previousX * W1;
  var currentGridX = currentX * W1;
  var lowX = Math.min(previousGridX, currentGridX);
  var highX = Math.max(previousGridX, currentGridX);
  var edgeRadius = 1.15;
  var strength = B4_FORCE_MULTIPLIER * Math.max(48, frontierSpeed * 1.15);
  var minX = Math.max(1, Math.floor(lowX - edgeRadius));
  var maxX = Math.min(W1 - 1, Math.ceil(highX + edgeRadius));

  for (var x = minX; x <= maxX; ++x) {
    var distance = x < lowX ? lowX - x : (x > highX ? x - highX : 0);
    if (distance >= edgeRadius) continue;
    var falloff = 1 - distance / edgeRadius;
    for (var y = 1; y < H1; ++y) {
      velU[y * W + x] += direction * strength * falloff;
    }
  }
}

/**
 * Advance all B4 waves. Both fronts use the same normalized progress, so their
 * unequal travel distances still terminate at the snapshotted disc X on the
 * same fixed step. Swept intervals prevent gaps between frontier positions.
 */
function updateAdvectionWaves(dt) {
  while (queuedB4 > 0) {
    --queuedB4;
    advectionWaves.push({ targetX: agents[0].x, age: 0 });
  }

  for (var i = advectionWaves.length - 1; i >= 0; --i) {
    var wave = advectionWaves[i];
    var previousTime = clampValue(wave.age / B4_SWEEP_SECONDS, 0, 1);
    wave.age = wave.age + dt >= B4_SWEEP_SECONDS - 1e-9
      ? B4_SWEEP_SECONDS
      : wave.age + dt;
    var time = clampValue(wave.age / B4_SWEEP_SECONDS, 0, 1);
    var previousProgress = previousTime * previousTime;
    var progress = time * time;

    var previousLeft = wave.targetX * previousProgress;
    var currentLeft = wave.targetX * progress;
    var previousRight = 1 - (1 - wave.targetX) * previousProgress;
    var currentRight = 1 - (1 - wave.targetX) * progress;
    // Force magnitude references the old 400ms linear frontier speeds. This
    // keeps the requested 3x force change independent of the new timing curve.
    var leftSpeed = wave.targetX * W1 / B4_FORCE_REFERENCE_SECONDS;
    var rightSpeed = (1 - wave.targetX) * W1 / B4_FORCE_REFERENCE_SECONDS;

    if (wave.targetX > 1e-6) {
      applySweptVerticalFront(previousLeft, currentLeft, 1, leftSpeed);
    }
    if (wave.targetX < 1 - 1e-6) {
      applySweptVerticalFront(previousRight, currentRight, -1, rightSpeed);
    }
    if (wave.age >= B4_SWEEP_SECONDS) {
      advectionWaves.splice(i, 1);
    }
  }
}

// ----------------------------------------------------------------- the solver

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
      var backX = x - velU[i] * dt;
      var backY = y - velV[i] * dt;
      velU2[i] = sampleField(velU, backX, backY);
      velV2[i] = sampleField(velV, backX, backY);
    }
  }
  var swapU = velU; velU = velU2; velU2 = swapU;
  var swapV = velV; velV = velV2; velV2 = swapV;
  enforceNoSlipWalls();
}

function diffuseVelocity(dt) {
  var viscosityRangeScale = 0.5 + 2.5 * viscosity;
  var nu = viscosity * viscosity * 18 * viscosityRangeScale;
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
      var force = curl[j] * MAX_VORTICITY_CONFINEMENT * turbulence * dt / magnitude;
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

// ------------------------------------------------------------------ the frame

function simulate(dt) {
  // Baselined on first use rather than defaulted, so loading a project with a
  // switch already flipped does not read as a flip and fire on frame one.
  if (!togglesBaselined) {
    sweepPrevious = t5;
    boltPrevious = t6;
    togglesBaselined = true;
  }

  var radius = agentRadiusValue();
  updateAgents(dt);
  emitAmbientParticles(dt);
  advectVelocity(dt);
  diffuseVelocity(dt);
  for (var i = 0; i < agents.length; ++i) {
    applyAgentSweep(agents[i], dt, radius);
  }
  var randomPulseActive = b3 || queuedB3 > 0;
  applyRandomFieldPulse(dt, randomPulseActive);
  if (queuedB3 > 0) --queuedB3;

  applyVorticityConfinement(dt);
  projectVelocity();

  // Everything below is radial, impulsive or both. It has to follow the
  // zero-divergence projection; applying it before would let pressure cancel
  // most of the visible motion.
  handleB1(dt, radius);
  applyAttraction(dt, b2);
  if (t1) applyRadialField(agents[0], dt, radius, 1);
  if (t3) applyRadialField(agents[1], dt, radius, -1);
  handleT1Emission(dt, radius);
  updateSweep(dt);
  updateBolt();
  updateAdvectionWaves(dt);

  advectParticles(dt);
  advectAndDecayDye(dt);
  emitParticleDye();
  stampPendingBolt();
  stampNodePair(sweepAngle(), SWEEP_NODE_LEVEL);
  stampNodePair(boltAngle(), BOLT_NODE_LEVEL);
  for (var j = 0; j < agents.length; ++j) {
    stampAgent(agents[j], radius);
  }
}

function preRender(deltaMs, nowMillis, model, colors, enabledAmount) {
  if (velU == null) init();
  // Hoisted out of renderPoint, where it does not vary by point and would cost
  // one pow per LED per frame.
  renderGamma = Math.pow(4, gammaCorrection * 2 - 1);

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
  var channel = Math.round(Math.pow(value, renderGamma) * 255);
  return rgb(channel, channel, channel);
}

// ------------------------------------------------------------------ utilities

function clampValue(value, low, high) {
  return Math.max(low, Math.min(high, value));
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
