/**
 * Ethereal Juice Box
 *
 * A 40x40 closed-box fluid carries short-lived fluorescent particles.
 *
 * Two solid agents live in the box and stamp white dye. Each transfers the
 * rigid-body velocity of its translated and rotated form into every cell it
 * sweeps, and each has an alternate spinning form:
 *
 *   Disc  K1/K2   T1 blows outward.  T2 becomes five orbiting dots.
 *   Sink  K3/K4   T3 sucks inward.   T4 becomes three orbiting arcs.
 *
 * The sink is drawn as a spinning X; its alternate form is the three arcs.
 *
 * Two more controls fire between opposing nodes on the box edge, each pair
 * placed by its own angle knob:
 *
 *   Sweep K5      T5 drives a pair of converging or diverging pressure fronts.
 *   Bolt  K6      T6 lays a jagged lightning bolt clear across the box.
 *
 * The K/T pairing is the layout of the physical clicky console: pot N is knob
 * KN and pulling that same pot out is TN. The Clicky Console modulator drives
 * all of it over the network -- see ClickyBinding in the Blinky Dome package --
 * but nothing here depends on the hardware being present.
 */

var FloatArray = Java.type("float[]");

var GRID_SIZE = 40;
var SIM_HZ = 60;
var MAX_SUBSTEPS = 4;
var DIFFUSION_ITERATIONS = 16;
var PRESSURE_ITERATIONS = 24;
var MAX_VORTICITY_CONFINEMENT = 36;
var MAX_PARTICLES = 2048;
var TAU = Math.PI * 2;

// The held row. B1, B2 and B3 apply for as long as they are on, and B1 also
// throws one burst on the press itself. All three are impulses into the
// velocity field.
var B1_EDGE_BAND = 2;
var B1_EDGE_IMPULSE = 220;
var B1_PARTICLE_BURST = 24;
var B1_PARTICLE_RATE = 90;
var B1_PARTICLE_SPEED = 30;
var B2_SPIRAL_FORCE = 80;
var B2_SPIRAL_INFLOW = 0.35;
var B2_SPIRAL_EDGE = 0.5;
var B3_JOLT_FORCE = 1260;

// B6 loosens the fluid while it is held. The rest targets are the Viscosity
// and Decay Rate knobs, which default to the values it returns to.
var B6_TWEEN_SECONDS = 0.5;
var B6_HELD_VISCOSITY = 0.4;
var B6_HELD_DECAY = 0.4;

// Agent forms. All extents are multiples of the shared agent radius, so the
// Agent Radius knob scales every form coherently.
var AGENT_DISC = 0;
var AGENT_SINK = 1;
var SATELLITE_COUNT = 5;
var SATELLITE_ORBIT = 1.35;
var SATELLITE_RADIUS = 0.32;
var X_ARM = 1;
var X_HALF_WIDTH = 0.22;
var ARC_COUNT = 3;
var ARC_RADIUS = 1.25;
var ARC_HALF_WIDTH = 0.25;
var ARC_FILL = 0.5;

// T1 and T3 radial fields. A radial field is pure divergence, so both are
// applied after the pressure projection; see the note in simulate().
var RADIAL_REACH = 3;
var RADIAL_STRENGTH = 40;
var T1_PUSH_MULTIPLIER = 0.5;
var T3_PULL_MULTIPLIER = 2;
var T1_TRANSITION_PARTICLES = 20;
var T1_TRANSITION_IMPULSE = 1;

// The two edge node pairs.
var NODE_RADIUS = 0.03;
var NODE_LEVEL = 1;

// T5 sweep.
var SWEEP_SECONDS = 0.2;
var SWEEP_FRONT_HALF_WIDTH = 1.15;
var SWEEP_FORCE = 200;

// T6 bolt.
var BOLT_SEGMENTS = 5;
var BOLT_JAGGEDNESS = 0.22;
var BOLT_DYE_RADIUS = 1.1;
var BOLT_INTENSITY = 4;
var BOLT_PARTICLES = 15;
var BOLT_PARTICLE_SPEED = 26;
var BOLT_IMPULSE = 260;
var BOLT_IMPULSE_RADIUS = 2.2;

// Settings row
knob("viscosity", "Viscosity", "Velocity diffusion; 0 is fluid, 1 is thick and smooth. B6 tweens away from this and back to it", 0.9);
knob("turbulence", "Turbulence", "Restore fluid curls lost to interpolation; 0 is smooth", 0.5);
knob("particleRate", "Particle Rate", "Ambient particles emitted per second", 1);
knob("particleLifespan", "Particle Lifespan", "Particle lifetime from 1 to 10 seconds; takes effect on newly emitted particles only", 0);
knob("decayRate", "Decay Rate", "How quickly emitted source material dims to zero. B6 tweens away from this and back to it", 0.85);
knob("gammaCorrection", "Gamma Correction", "Shape the source-value to output-brightness curve; 50% is neutral", 0.25);
knob("particleAmplitude", "Particle Amplitude", "Particle source emission multiplier from 0.5x to 10x", 0.3);
knob("agentRadius", "Agent Radius", "Fixed radius shared by the disc and the sink", 0.5);
knob("spinRate", "Spin Rate", "How fast the orbiting dots and arcs rotate", 0.4);

// The button row, all held states rather than one-shots: on means the console
// button is down, off means it is up. B1, B2 and B3 do work every step they are
// on, and B1 additionally fires a burst on the press edge.
//
// These are toggle() and not trigger() because a trigger control cannot be
// held -- it fires its listeners and puts itself straight back to false, so a
// script reading it always reads false and "while held" can never work. Nothing
// is lost on a short tap: the console holds a press for a minimum duration
// before it will report the release, so a tap between two frames still lands.
toggle("b1", "B1", "While on, lay source and particles around all four walls and drive them at the center", false);
toggle("b2", "B2", "While on, drive a spiral through the whole box", false);
toggle("b3", "B3", "While on, jolt every cell in a random direction", false);
toggle("b4", "B4", "Reserved button", false);
toggle("b5", "B5", "Reserved button", false);
toggle("b6", "B6", "While on, smear viscosity and decay toward loose; half a second out and half a second back", false);

// Latched switches. T5 and T6 act on the flip rather than on the position, so
// both directions do something and neither has a resting state that is "off".
toggle("t1", "T1", "Disc blows the fluid outward; either flip also throws a burst off its surface", false);
toggle("t2", "T2", "Disc becomes five dots orbiting just outside its radius", false);
toggle("t3", "T3", "Sink sucks the fluid inward like a vacuum", false);
toggle("t4", "T4", "Sink becomes three arc segments, whose interior stays clear under suction", false);
toggle("t5", "T5", "Flip out to sweep the box inward, flip in to sweep it back outward", false);
toggle("t6", "T6", "Either flip fires a lightning bolt between the two edge nodes", false);

// K1/K2 and K3/K4 place the two agents. K5 and K6 rotate the two edge node
// pairs over a full turn. Each pair is opposed, so the second half of a knob
// repeats the first with the two nodes swapped.
knob("k1", "K1", "Disc X position", 0.5);
knob("k2", "K2", "Disc Y position", 0.5);
knob("k3", "K3", "Sink X position", 0.5);
knob("k4", "K4", "Sink Y position", 0.5);
knob("k5", "K5", "Sweep node angle, full turn", 0.25);
// Defaulted a quarter turn apart from K5 so the two node pairs do not sit on
// top of each other on load.
knob("k6", "K6", "Bolt node angle, full turn", 0);

var W, H, W1, H1, cells;
var velU, velV, velU2, velV2, diffusionU, diffusionV;
var dye, dye2, pressure, pressure2, divergence, curl;

var particles = [];
var ambientEmissionAccumulator = 0;
var simAccumulator = 0;
var renderGamma = 1;

var agents = [];
var agentsInitialized = false;

// Everything that acts on a flip rather than on a position keeps its previous
// value here and recovers the edge from the difference. They are baselined on
// the first simulated step rather than in init(), which the host calls before
// the parameter variables exist.
var togglesBaselined = false;
var thrustPrevious = false;
var sweepPrevious = false;
var boltPrevious = false;
var edgePressPrevious = false;
var sweep = null;
var pendingBolt = null;

var edgeEmissionAccumulator = 0;

// 0 at the knob values, 1 at B6's held values.
var slipEnvelope = 0;

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
  simAccumulator = 0;

  agents = [
    newAgent(AGENT_DISC),
    newAgent(AGENT_SINK)
  ];
  agentsInitialized = false;

  togglesBaselined = false;
  sweep = null;
  pendingBolt = null;

  edgeEmissionAccumulator = 0;
  slipEnvelope = 0;
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
      : radius * (ARC_RADIUS + ARC_HALF_WIDTH);
  }
  return agent.kind === AGENT_DISC ? radius : radius * (X_ARM + X_HALF_WIDTH);
}

/**
 * Whether a point lies inside an agent's solid form.
 *
 * The center and spin are passed in rather than read off the agent so the
 * swept-path test can walk the form back along the motion it made this step.
 * Both alternate forms are rotationally symmetric, so the point is folded onto
 * its nearest arm and one arm is tested instead of all of them.
 */
function agentContainsAt(agent, px, py, cx, cy, spin, radius) {
  var dx = px - cx;
  var dy = py - cy;
  var distanceSquared = dx * dx + dy * dy;

  if (!agent.alt) {
    if (agent.kind === AGENT_DISC) {
      return distanceSquared <= radius * radius;
    }
    // An X, which is a plus sign in a frame turned an eighth of a turn. Two
    // trig calls beat folding here, and the crossing point stays solid.
    var turn = spin + Math.PI / 4;
    var turnCos = Math.cos(turn);
    var turnSin = Math.sin(turn);
    var barX = Math.abs(turnCos * dx + turnSin * dy);
    var barY = Math.abs(turnCos * dy - turnSin * dx);
    var barHalf = X_HALF_WIDTH * radius;
    var barArm = X_ARM * radius;
    return (barY <= barHalf && barX <= barArm) || (barX <= barHalf && barY <= barArm);
  }

  var distance = Math.sqrt(distanceSquared);
  var sector = TAU / (agent.kind === AGENT_DISC ? SATELLITE_COUNT : ARC_COUNT);
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

  // A band at the arc radius, cut into ARC_COUNT pieces. ARC_FILL is how much
  // of each sector is arc; the remainder is the gap between one arc and the
  // next, which is what keeps the three segments reading as separate.
  if (Math.abs(distance - ARC_RADIUS * radius) > ARC_HALF_WIDTH * radius) {
    return false;
  }
  return Math.abs(offset) <= ARC_FILL * sector * 0.5;
}

/** The heading of the X's first arm. */
function spinOffsetX(agent) {
  return agent.spin + Math.PI / 4;
}

/** A point on an agent's outer surface with its outward normal, p in [0,1). */
function agentSurfaceSample(agent, p, radius) {
  p = wrap01(p);

  if (!agent.alt) {
    if (agent.kind === AGENT_DISC) {
      var theta = TAU * p;
      var c = Math.cos(theta);
      var s = Math.sin(theta);
      return { x: agent.x + radius * c, y: agent.y + radius * s, nx: c, ny: s };
    }
    // Off the tip of one of the X's four arms.
    var tipAngle = spinOffsetX(agent) + Math.floor(p * 4) * (Math.PI / 2);
    var tipCos = Math.cos(tipAngle);
    var tipSin = Math.sin(tipAngle);
    var reach = X_ARM * radius;
    return { x: agent.x + reach * tipCos, y: agent.y + reach * tipSin, nx: tipCos, ny: tipSin };
  }

  if (agent.kind === AGENT_SINK) {
    // Somewhere along the outer edge of one of the three arcs.
    var arcSector = TAU / ARC_COUNT;
    var along = (wrap01(p * ARC_COUNT) - 0.5) * ARC_FILL * arcSector;
    var arcAngle = agent.spin + Math.floor(p * ARC_COUNT) * arcSector + along;
    var arcCos = Math.cos(arcAngle);
    var arcSin = Math.sin(arcAngle);
    var edge = (ARC_RADIUS + ARC_HALF_WIDTH) * radius;
    return { x: agent.x + edge * arcCos, y: agent.y + edge * arcSin, nx: arcCos, ny: arcSin };
  }

  var armAngle = agent.spin + Math.floor(p * SATELLITE_COUNT) * (TAU / SATELLITE_COUNT);
  // The fractional part within the chosen arm doubles as the angle around
  // that arm's dot, so one random number places the sample completely.
  var local = TAU * wrap01(p * SATELLITE_COUNT);
  var localCos = Math.cos(local);
  var localSin = Math.sin(local);
  var orbit = SATELLITE_ORBIT * radius;
  var dotRadius = SATELLITE_RADIUS * radius;
  return {
    x: agent.x + orbit * Math.cos(armAngle) + dotRadius * localCos,
    y: agent.y + orbit * Math.sin(armAngle) + dotRadius * localSin,
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

/**
 * An agent is always a solid, maximum-value emitter. The sink's arc form is
 * additionally a container: while suction is running its interior is forced to
 * zero every step, which is the case that would otherwise pack the middle with
 * everything the vacuum has just dragged in. The void is bounded by the inner
 * edge of the arcs, so it reads as the shape containing it rather than as a
 * hole floating on its own.
 */
function stampAgent(agent, radius, suction) {
  var extent = agentExtent(agent, radius);
  var minX = Math.max(0, Math.floor((agent.x - extent) * W1));
  var maxX = Math.min(W1, Math.ceil((agent.x + extent) * W1));
  var minY = Math.max(0, Math.floor((agent.y - extent) * H1));
  var maxY = Math.min(H1, Math.ceil((agent.y + extent) * H1));
  // Only the arc form encloses anything. The X is a solid figure with no
  // interior, so there is nothing to hold open when it is the one drawn.
  var hollow = agent.kind === AGENT_SINK && agent.alt && suction;
  var hollowRadius = radius * (ARC_RADIUS - ARC_HALF_WIDTH);

  for (var y = minY; y <= maxY; ++y) {
    var yn = y / H1;
    for (var x = minX; x <= maxX; ++x) {
      var xn = x / W1;
      var i = y * W + x;
      if (agentContainsAt(agent, xn, yn, agent.x, agent.y, agent.spin, radius)) {
        dye[i] = 1;
      } else if (hollow) {
        var dx = xn - agent.x;
        var dy = yn - agent.y;
        if (dx * dx + dy * dy < hollowRadius * hollowRadius) {
          dye[i] = 0;
        }
      }
    }
  }
}

/**
 * T1 and T3. Sign +1 blows the fluid away from the agent, -1 draws it in.
 * The falloff is linear rather than inverse-square so there is no singularity
 * sitting inside the solid form.
 */
function applyRadialField(agent, dt, radius, sign, multiplier) {
  var reach = agentExtent(agent, radius) * RADIAL_REACH;
  var minX = Math.max(1, Math.floor((agent.x - reach) * W1));
  var maxX = Math.min(W1 - 1, Math.ceil((agent.x + reach) * W1));
  var minY = Math.max(1, Math.floor((agent.y - reach) * H1));
  var maxY = Math.min(H1 - 1, Math.ceil((agent.y + reach) * H1));
  var scale = sign * RADIAL_STRENGTH * multiplier * dt;

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
  return clampValue(k5, 0, 1) * TAU;
}

function boltAngle() {
  return clampValue(k6, 0, 1) * TAU;
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
 * Flipping T5 out converges a pair of fronts on the box center; flipping it in
 * drives them back to the edge. Only one sweep is ever in flight, so reversing
 * mid-travel abandons the old one on the spot rather than queueing behind it.
 */
function updateSweep(dt) {
  if (t5 !== sweepPrevious) {
    sweepPrevious = t5;
    sweep = { angle: sweepAngle(), outward: t5, age: 0 };
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
      var value = BOLT_INTENSITY * (1 - 0.35 * distance / BOLT_DYE_RADIUS);
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

function emitSurfaceParticleAndPulse(agent, radius, boost) {
  var sample = agentSurfaceSample(agent, Math.random(), radius);
  var impulseScale = 2 * (boost || 1);
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

/**
 * B1. A band of source around all four walls with an inward impulse behind it,
 * so the box is squeezed from every edge at once. The dye is laid down in the
 * stamping phase rather than here, so it is not advected away by the very
 * impulse that accompanies it.
 */
function applyEdgeImpulse() {
  var reach = B1_EDGE_BAND + 1;
  for (var y = 1; y < H1; ++y) {
    var yn = y / H1;
    var fromBottom = y;
    var fromTop = H1 - y;
    for (var x = 1; x < W1; ++x) {
      var depth = Math.min(x, W1 - x, fromBottom, fromTop);
      if (depth > B1_EDGE_BAND) continue;
      var dx = 0.5 - x / W1;
      var dy = 0.5 - yn;
      var distance = Math.sqrt(dx * dx + dy * dy);
      if (distance < 1e-6) continue;
      // Straight at the middle of the box rather than square off the nearest
      // wall, so the four walls converge on one point instead of four fronts.
      var scale = B1_EDGE_IMPULSE * (1 - depth / reach) / distance;
      var i = y * W + x;
      velU[i] += dx * scale;
      velV[i] += dy * scale;
    }
  }
}

/** A point on the perimeter just inside the source band, p in [0,1). */
function perimeterPoint(p) {
  var inset = (B1_EDGE_BAND + 0.5) / Math.max(W1, H1);
  var span = 1 - 2 * inset;
  var walk = wrap01(p) * 4;
  var side = Math.floor(walk);
  var along = inset + (walk - side) * span;
  if (side === 0) return { x: along, y: inset };
  if (side === 1) return { x: 1 - inset, y: along };
  if (side === 2) return { x: 1 - inset - (along - inset), y: 1 - inset };
  return { x: inset, y: 1 - inset - (along - inset) };
}

/** One particle off the wall, launched at the middle of the box. */
function emitEdgeParticle() {
  var position = perimeterPoint(Math.random());
  var dx = 0.5 - position.x;
  var dy = 0.5 - position.y;
  var distance = Math.sqrt(dx * dx + dy * dy) || 1;
  addParticle(
    position.x,
    position.y,
    dx / distance * B1_PARTICLE_SPEED,
    dy / distance * B1_PARTICLE_SPEED
  );
}

/**
 * B1. Source around all four walls, particles thrown off them, and an impulse
 * carrying both at the middle of the box. Returns whether the band should be
 * stamped this step; the dye itself is laid down in the stamping phase so it
 * is not advected away by the impulse that accompanies it.
 *
 * The impulse and the steady emission run for as long as the button is on. The
 * burst is on the press edge alone, so holding B1 is a wall of pressure with a
 * single throw of particles at the front of it rather than a throw per step.
 */
function handleB1(dt) {
  var pressed = b1 && !edgePressPrevious;
  edgePressPrevious = b1;

  if (!b1) {
    edgeEmissionAccumulator = 0;
    return false;
  }

  applyEdgeImpulse();
  if (pressed) {
    for (var i = 0; i < B1_PARTICLE_BURST; ++i) {
      emitEdgeParticle();
    }
  }
  edgeEmissionAccumulator += B1_PARTICLE_RATE * dt;
  while (edgeEmissionAccumulator >= 1) {
    edgeEmissionAccumulator -= 1;
    emitEdgeParticle();
  }
  return true;
}

function stampEdgeSource() {
  for (var y = 0; y < H; ++y) {
    var inset = y > B1_EDGE_BAND && y < H1 - B1_EDGE_BAND;
    for (var x = 0; x < W; ++x) {
      if (inset && x > B1_EDGE_BAND && x < W1 - B1_EDGE_BAND) continue;
      dye[y * W + x] = 1;
    }
  }
}

/**
 * B2. A ring vortex about the box center, with enough inflow to make it read
 * as a spiral rather than a turntable. The strength peaks partway out and
 * eases to nothing before the walls, so the no-slip boundary is not fighting
 * a hard tangential edge.
 */
function applySpiralImpulse() {
  for (var y = 1; y < H1; ++y) {
    var yn = y / H1 - 0.5;
    for (var x = 1; x < W1; ++x) {
      var xn = x / W1 - 0.5;
      var r = Math.sqrt(xn * xn + yn * yn);
      if (r < 1e-5 || r >= B2_SPIRAL_EDGE) continue;
      var inverse = 1 / r;
      var falloff = Math.sin(Math.PI * r / B2_SPIRAL_EDGE) * B2_SPIRAL_FORCE;
      var i = y * W + x;
      velU[i] += (-yn - xn * B2_SPIRAL_INFLOW) * inverse * falloff;
      velV[i] += (xn - yn * B2_SPIRAL_INFLOW) * inverse * falloff;
    }
  }
}

/** B3. Every interior cell shoved in its own random direction. */
function applyRandomFieldPulse(dt) {
  var strength = B3_JOLT_FORCE * Math.sqrt(dt);
  for (var y = 1; y < H1; ++y) {
    for (var x = 1; x < W1; ++x) {
      var i = y * W + x;
      velU[i] += (Math.random() * 2 - 1) * strength;
      velV[i] += (Math.random() * 2 - 1) * strength;
    }
  }
}

/**
 * B6. While on, viscosity and decay smear toward loose and fast-decaying over
 * half a second; switched off, they smear back to whatever the two knobs say
 * over the same half second. Latching rather than momentary, so the smeared
 * state can be held indefinitely without keeping the control pressed.
 */
function updateSlipEnvelope(dt) {
  var step = dt / B6_TWEEN_SECONDS;
  slipEnvelope = b6
    ? Math.min(1, slipEnvelope + step)
    : Math.max(0, slipEnvelope - step);
}

function effectiveViscosity() {
  var rest = clampValue(viscosity, 0, 1);
  return rest + (B6_HELD_VISCOSITY - rest) * slipEnvelope;
}

function effectiveDecayRate() {
  var rest = clampValue(decayRate, 0, 1);
  return rest + (B6_HELD_DECAY - rest) * slipEnvelope;
}

/**
 * Either flip of T1 throws one burst off the disc, and that is the whole of
 * its emission -- holding T1 on does not keep feeding particles in. Switching
 * it off is therefore as much of an event as switching it on, and the on state
 * is the sustained outward field alone.
 */
function handleT1Transition(radius) {
  if (t1 === thrustPrevious) return;
  thrustPrevious = t1;
  for (var i = 0; i < T1_TRANSITION_PARTICLES; ++i) {
    emitSurfaceParticleAndPulse(agents[0], radius, T1_TRANSITION_IMPULSE);
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
  var thickness = effectiveViscosity();
  var viscosityRangeScale = 0.5 + 2.5 * thickness;
  var nu = thickness * thickness * 54 * viscosityRangeScale;
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
  var fade = effectiveDecayRate();
  var decayPerSecond = 0.05 + 13.95 * fade * fade;
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
    thrustPrevious = t1;
    sweepPrevious = t5;
    boltPrevious = t6;
    edgePressPrevious = b1;
    togglesBaselined = true;
  }

  var radius = agentRadiusValue();
  updateSlipEnvelope(dt);
  updateAgents(dt);
  emitAmbientParticles(dt);
  advectVelocity(dt);
  diffuseVelocity(dt);
  for (var i = 0; i < agents.length; ++i) {
    applyAgentSweep(agents[i], dt, radius);
  }

  if (b3) applyRandomFieldPulse(dt);

  applyVorticityConfinement(dt);
  projectVelocity();

  // Everything below is radial, tangential or impulsive. It has to follow the
  // zero-divergence projection; applying it before would let pressure cancel
  // most of the visible motion.
  var edgeActive = handleB1(dt);

  if (b2) applySpiralImpulse();

  if (t1) applyRadialField(agents[0], dt, radius, 1, T1_PUSH_MULTIPLIER);
  if (t3) applyRadialField(agents[1], dt, radius, -1, T3_PULL_MULTIPLIER);
  handleT1Transition(radius);
  updateSweep(dt);
  updateBolt();

  advectParticles(dt);
  advectAndDecayDye(dt);
  emitParticleDye();
  stampPendingBolt();
  if (edgeActive) stampEdgeSource();
  stampNodePair(sweepAngle(), NODE_LEVEL);
  stampNodePair(boltAngle(), NODE_LEVEL);
  stampAgent(agents[0], radius, false);
  stampAgent(agents[1], radius, t3);
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
