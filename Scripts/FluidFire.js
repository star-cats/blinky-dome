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
 *
 * ---------------------------------------------------------------------------
 *
 * Fuel comes from three source balls, indexed 0, 1 and 2, moved by a
 * choreographer. Every state places them as a formation keyed on that index, so
 * the three always read as one figure rather than three independent lights.
 *
 * Nothing about a ball is drawn — a ball is only where fuel and momentum enter
 * the fluid, and everything visible is the fire's response to that. Two rules
 * make the motion legible:
 *
 *   A ball never jumps. Choreography sets a target and a shared PID-ish
 *   controller flies the ball there, so a state change is a move rather than a
 *   cut, and the fire keeps burning across it.
 *
 *   A ball smears. Every point along the segment it travelled in a step gets the
 *   full source strength rather than a share of it, so a moving ball lays a
 *   solid trail that burns as hard as the ball itself and drags the fluid along
 *   its whole heading, scaled by Advect.
 *
 * Balls pass through each other freely; only PLACER cares where the others are.
 * Choreo picks a random state other than the current one, and Cue means
 * something different in each — see the choreography section below.
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

// ------------------------------------------------------------------ choreography
//
// Held internally for now rather than exposed. Every state derives its timing
// from this one clock, so they stay in step with each other and with anything
// else running at the same tempo.
var CHOREO_BPM = 120;
var CHOREO_PHASE = 0;

var BALL_COUNT = 3;

// A ball's speed limit, in frame-widths per second. This is not a look control —
// it bounds the length of the segment one step can smear over, which bounds the
// work the swept stamp does.
var MAX_BALL_SPEED = 4;

// Longest swept stamp, in sub-discs. A ball at the speed limit crosses about
// four hundredths of the frame in a substep, so this is slack, not a budget.
var MAX_SWEEP_STAMPS = 24;

// How fast a ball's radius multiplier chases the value choreography asks for.
// Slow enough that FIRELINE's shrink to nothing reads as a fade rather than as
// the ball being switched off.
var RADIUS_CHASE = 2.5;

var STATE_ORBIT = 0;
var STATE_COLUMNS = 1;
var STATE_PINGPONG = 2;
var STATE_PLACER = 3;
var STATE_FIRELINE = 4;
var STATE_COUNT = 5;
var STATE_NAMES = ["ORBIT", "COLUMNS", "PINGPONG", "PLACER", "FIRELINE"];

// ORBIT: beats per revolution, the two slow LFOs that deform the circle, and how
// many cues it takes to turn the orbit around. Reversing on every cue makes the
// orbit twitch; making the cue count to eight turns the reversal into something
// that arrives on the bar you were building toward.
var ORBIT_BEATS = 5;
var ORBIT_CUES_PER_FLIP = 8;
var ORBIT_SQUASH_BEATS = 23;
var ORBIT_SQUASH_DEPTH = 0.55;
var ORBIT_AXIS_BEATS = 37;

// COLUMNS: how far from the middle the two ends sit, how many beats a setpoint
// takes to creep the whole way between them when nothing is cueing it, and how
// close a ball has to get to count as having arrived. There is no middle
// setpoint and no clock — a ball is always going to the top or going to the
// bottom, and it only turns around once it has actually got there.
var COLUMN_TRAVEL = 0.42;
var COLUMN_CREEP_BEATS = 16;
var COLUMN_ARRIVE = 0.04;

// PINGPONG: travel speed range in frame-widths per second, how far off a clean
// bounce a reflection is allowed to be, and the inset of the walls it bounces
// off. The imperfection is the point — three balls reflecting perfectly stay
// on the same three paths forever.
var PING_SPEED_MIN = 0.6;
var PING_SPEED_MAX = 1.92;
var PING_ANGLE_JITTER = 0.3;
var PING_SPEED_JITTER = 0.14;
var PING_MARGIN = 0.06;

// PLACER: minimum center-to-center distance between two targets, as a multiple
// of the Radius knob, and how hard to try before settling for the best of a bad
// set. A wide Radius makes the constraint unsatisfiable for three balls in a
// unit box, and a pattern that hangs is worse than one that crowds.
var PLACER_SEPARATION = 1.3;
var PLACER_ATTEMPTS = 64;

// PLACER sway: a parked ball breathes around its spot on two detuned sinusoids,
// one per axis. The frequencies are deliberately not a ratio of small integers,
// so x and y never close the same figure twice and the wander reads as alive
// rather than as an orbit. Each ball is detuned again off its index so the
// three do not sway as one.
var PLACER_SWAY = 0.09;
var PLACER_SWAY_HZ_X = 0.85;
var PLACER_SWAY_HZ_Y = 1.15;
var PLACER_SWAY_DETUNE = 0.037;

// How long a ball may sit on its spot before it goes looking for another one,
// and how close to that spot counts as sitting on it. The tolerance has to clear
// the sway, or a ball that has plainly arrived would never be found stationary —
// it is always moving a little, and that is the point of the sway. It follows
// the sway rather than being set independently, so widening one cannot silently
// break the other.
var PLACER_SETTLE_TIME = 2.0;
var PLACER_SETTLED = PLACER_SWAY * 1.6;

// FIRELINE: the launch, the fall, and the strip left behind. The strip burns
// harder than a ball does and throws its fuel upward — it is the wall of flame
// the balls died to light, not a row of pilot lights.
var FIRE_LAUNCH_VX = 0.45;
var FIRE_LAUNCH_VY_MIN = 0.35;
var FIRE_LAUNCH_VY_MAX = 0.8;
var FIRE_GRAVITY = 1.5;
var FIRE_GROUND = 0.02;
var FIRELINE_FADE = 1.0;
var FIRELINE_ROWS = 3;
var FIRELINE_DENSITY = 3;
var FIRELINE_LIFT = 40;
var FIRELINE_SWIRL = 30;

// How hard the strip's own noise swings its output. This rides on top of
// Flicker rather than under it: Flicker is a knob the whole pattern shares and
// can be turned off, and the strip has to churn on its own regardless — the
// gusting is what the fire line is, not a decoration on it.
var FIRELINE_GUST = 0.8;

// ---------------------------------------------------------------------- source

knob("srcLevel", "Source", "How hard fuel is injected at each source ball", 0.65);
knob("srcRadius", "Radius", "Source radius shared by all three balls", 0.18);
knob("srcX", "Center X", "Formation center, horizontal", 0.5);
knob("srcY", "Center Y", "Formation center, vertical", 0.5);
knob("spread", "Spread", "Formation size — orbit radius, column separation", 0.3);
knob("jet", "Jet", "Upward velocity injected at each ball", 0.15);
knob("flicker", "Flicker", "How much the source strength wavers over time", 0.4);
knob("advect", "Advect", "How hard a moving ball drags the fluid along its heading", 0.5);

// ----------------------------------------------------------------- ball motion
//
// One controller, shared by all three balls. Chase is the spring pulling a ball
// to its target, Damping is what stops it overshooting, and Trim is the integral
// term — it kills the steady-state droop of a ball being pushed by its own
// fire, and it is the one that will wind up and wobble if leaned on.

knob("pidP", "Chase", "How hard a ball is pulled toward its target", 0.4);
knob("pidD", "Damping", "How hard that pull is resisted; low overshoots", 0.5);
knob("pidI", "Trim", "Integral correction for persistent error", 0);

trigger("choreo", "Choreo", "Transition to a random other choreography state", onChoreoTrigger);
trigger("cue", "Cue", "Accent the current state; means something different in each", onCueTrigger);

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

  initBalls();
}

// ----------------------------------------------------------- the source balls
//
// Three of them, and the index is part of the choreography rather than just a
// loop counter: ball 0 leads the column, ball 0 is the first third of the orbit,
// and so on. Each carries its own position, velocity, target and integral term,
// plus the previous position that the swept stamp needs.

var balls = [];
var choreoState = STATE_ORBIT;

// Triggers fire from whatever thread rang them, so they are counted here and
// spent in preRender, where the choreography actually lives. Counting rather
// than flagging means two cues inside one frame both land.
var pendingChoreo = 0;
var pendingCue = 0;

// ORBIT's angle is accumulated rather than derived from the beat clock, because
// the cue reverses it: a derived angle would snap to the mirrored position the
// instant the sign flipped.
var orbitPhase = 0;
var orbitDir = 1;
var orbitCueCount = 0;


// FIRELINE's strip. Always here, only ever nonzero in that state or during the
// second it takes to fade out of one.
var fireLineLevel = 0;
var fireLineTarget = 0;

function initBalls() {
  balls.length = 0;
  for (var i = 0; i < BALL_COUNT; ++i) {
    balls.push({
      index: i,
      x: 0.5, y: 0.5,
      px: 0.5, py: 0.5,
      vx: 0, vy: 0,
      tx: 0.5, ty: 0.5,
      ix: 0, iy: 0,
      // PLACER's parked spot. The target is this plus the sway, so the sway
      // never walks the ball away from where it was placed. stillTime is how
      // long it has been sitting on that spot.
      hx: 0.5, hy: 0.5,
      stillTime: 0,
      // COLUMNS' destination end: +1 is the top, -1 is the bottom.
      colDest: 1,
      radiusMul: 1,
      radiusTarget: 1,
      // FIRELINE flies the ball directly instead of through the controller.
      ballistic: false,
      landed: false,
      // PINGPONG's bouncing point, which the ball chases.
      bx: 0.5, by: 0.5,
      dirX: 1, dirY: 0,
      speed: 0
    });
  }
  fireLineLevel = 0;
  fireLineTarget = 0;
  orbitPhase = 0;
  orbitDir = 1;
  orbitCueCount = 0;
  enterState(STATE_ORBIT);
}

function onChoreoTrigger() {
  ++pendingChoreo;
}

function onCueTrigger() {
  ++pendingCue;
}

/** Beats since the script loaded, on the internal tempo. */
function beats() {
  return simClock * (CHOREO_BPM / 60) + CHOREO_PHASE;
}

function randomRange(lo, hi) {
  return lo + Math.random() * (hi - lo);
}

/** The shared source radius in normalized units, before a ball's multiplier. */
function baseRadius() {
  return Math.max(lerp(0.02, 0.6, srcRadius), 1 / Math.min(W1, H1));
}

// ------------------------------------------------------------ state machine

function randomOtherState(current) {
  // Uniform over the four states that are not the current one: pick from that
  // many, then step over the hole.
  var pick = Math.floor(Math.random() * (STATE_COUNT - 1));
  if (pick >= current) {
    ++pick;
  }
  return pick;
}

function enterState(next) {
  exitState(choreoState);
  choreoState = next;

  var i, ball;
  if (next === STATE_ORBIT) {
    // A fresh count per visit, so the first cue after arriving is always the
    // first of eight rather than however many were left over last time.
    orbitCueCount = 0;
  } else if (next === STATE_COLUMNS) {
    // Ball 0 heads for the top and the other two for the bottom, each setpoint
    // starting from the far end so all three have the same full traverse ahead
    // of them. Equal distances at equal speed is what keeps the two groups in
    // antiphase for as long as the state runs.
    for (i = 0; i < balls.length; ++i) {
      ball = balls[i];
      ball.colDest = (i === 0) ? 1 : -1;
      ball.ty = 0.5 - COLUMN_TRAVEL * ball.colDest;
    }
  } else if (next === STATE_PINGPONG) {
    // Each ball leaves on its own heading, from where it already is — the
    // formation reads as one that scatters rather than one that restarts.
    for (i = 0; i < balls.length; ++i) {
      ball = balls[i];
      var angle = Math.random() * Math.PI * 2;
      ball.dirX = Math.cos(angle);
      ball.dirY = Math.sin(angle);
      ball.speed = randomRange(PING_SPEED_MIN, PING_SPEED_MAX);
      ball.bx = clamp(ball.x, PING_MARGIN, 1 - PING_MARGIN);
      ball.by = clamp(ball.y, PING_MARGIN, 1 - PING_MARGIN);
    }
  } else if (next === STATE_PLACER) {
    placeBalls();
  } else if (next === STATE_FIRELINE) {
    for (i = 0; i < balls.length; ++i) {
      ball = balls[i];
      ball.ballistic = true;
      ball.landed = false;
      ball.vx = randomRange(-FIRE_LAUNCH_VX, FIRE_LAUNCH_VX);
      ball.vy = randomRange(FIRE_LAUNCH_VY_MIN, FIRE_LAUNCH_VY_MAX);
    }
  }
}

function exitState(previous) {
  if (previous === STATE_FIRELINE) {
    // Leaving takes the balls off ballistic control and hands them back to the
    // controller at full size; the strip fades on its own from here.
    fireLineTarget = 0;
    for (var i = 0; i < balls.length; ++i) {
      balls[i].ballistic = false;
      balls[i].landed = false;
      balls[i].radiusTarget = 1;
      balls[i].ix = 0;
      balls[i].iy = 0;
    }
  }
}

// --------------------------------------------------------------- the states

/**
 * ORBIT — the three spaced a third of a turn apart on a circle around the
 * formation center, under two slow LFOs: one squashing the vertical axis, one
 * rotating the axis the squash happens on.
 *
 * Cues are counted rather than acted on: every eighth one reverses the orbit,
 * and the seven in between do nothing visible. The count is of cues actually
 * fired, not of frames that saw one, so two cues inside a single frame both
 * land — at eight to a reversal, a swallowed cue would put every later reversal
 * on the wrong beat.
 */
function updateOrbit(dt, cues) {
  if (cues > 0) {
    orbitCueCount += cues;
    if (orbitCueCount >= ORBIT_CUES_PER_FLIP) {
      orbitCueCount -= ORBIT_CUES_PER_FLIP;
      orbitDir = -orbitDir;
    }
  }
  orbitPhase += dt * (CHOREO_BPM / 60) / ORBIT_BEATS * Math.PI * 2 * orbitDir;

  var b = beats();
  var squash = 1 - ORBIT_SQUASH_DEPTH * 0.5 * (1 - Math.cos(b * Math.PI * 2 / ORBIT_SQUASH_BEATS));
  var axis = b * Math.PI * 2 / ORBIT_AXIS_BEATS;
  var cosAxis = Math.cos(axis);
  var sinAxis = Math.sin(axis);
  var radius = lerp(0.08, 0.45, spread);

  for (var i = 0; i < balls.length; ++i) {
    var ball = balls[i];
    var angle = orbitPhase + i * Math.PI * 2 / BALL_COUNT;
    var ex = Math.cos(angle) * radius;
    var ey = Math.sin(angle) * radius * squash;
    ball.tx = srcX + ex * cosAxis - ey * sinAxis;
    ball.ty = srcY + ex * sinAxis + ey * cosAxis;
    ball.radiusTarget = 1;
  }
}

/**
 * COLUMNS — ball 0 rides the center column, balls 1 and 2 ride columns either
 * side of it, and the two groups head for opposite ends.
 *
 * A ball is only ever going to the top or going to the bottom; there is no
 * middle setpoint and nothing on a timer. Left alone, the setpoint creeps toward
 * that end over COLUMN_CREEP_BEATS and the ball follows it up the column. A cue
 * commits: the setpoint goes the whole way at once and the controller flies the
 * ball there, which is the difference between the column drifting and the column
 * being thrown.
 *
 * The destination only flips once the ball itself has arrived — not once the
 * setpoint has. After a cue the setpoint is there instantly while the ball still
 * has the length of the frame to cross, and flipping then would turn it around
 * before it ever made the trip.
 */
function updateColumns(dt, cues) {
  var offset = lerp(0.08, 0.42, spread);
  var creep = (2 * COLUMN_TRAVEL) / COLUMN_CREEP_BEATS * (CHOREO_BPM / 60) * dt;

  for (var i = 0; i < balls.length; ++i) {
    var ball = balls[i];
    var destY = 0.5 + COLUMN_TRAVEL * ball.colDest;

    ball.tx = srcX + (i === 0 ? 0 : (i === 1 ? -offset : offset));

    if (cues > 0) {
      ball.ty = destY;
    } else if (ball.ty < destY) {
      ball.ty = Math.min(destY, ball.ty + creep);
    } else if (ball.ty > destY) {
      ball.ty = Math.max(destY, ball.ty - creep);
    }

    // Both conditions matter. The setpoint test keeps a ball that entered the
    // state already sitting at its destination from turning around on the first
    // frame — its setpoint starts at the far end, so it has not arrived at
    // anything yet, however close it happens to be standing.
    if (Math.abs(ball.ty - destY) < 1e-9 && Math.abs(ball.y - destY) <= COLUMN_ARRIVE) {
      ball.colDest = -ball.colDest;
    }

    ball.radiusTarget = 1;
  }
}

/**
 * PINGPONG — each ball chases a point that flies straight and bounces off the
 * walls, with the bounce deliberately imperfect so the three drift apart
 * instead of running the same loop forever. Cue does nothing here.
 */
function updatePingpong(dt) {
  var lo = PING_MARGIN;
  var hi = 1 - PING_MARGIN;

  for (var i = 0; i < balls.length; ++i) {
    var ball = balls[i];
    ball.bx += ball.dirX * ball.speed * dt;
    ball.by += ball.dirY * ball.speed * dt;

    var bounced = false;
    if (ball.bx < lo) { ball.bx = lo; ball.dirX = -ball.dirX; bounced = true; }
    else if (ball.bx > hi) { ball.bx = hi; ball.dirX = -ball.dirX; bounced = true; }
    if (ball.by < lo) { ball.by = lo; ball.dirY = -ball.dirY; bounced = true; }
    else if (ball.by > hi) { ball.by = hi; ball.dirY = -ball.dirY; bounced = true; }

    if (bounced) {
      var angle = Math.atan2(ball.dirY, ball.dirX) + randomRange(-PING_ANGLE_JITTER, PING_ANGLE_JITTER);
      ball.dirX = Math.cos(angle);
      ball.dirY = Math.sin(angle);
      ball.speed = clamp(
        ball.speed * randomRange(1 - PING_SPEED_JITTER, 1 + PING_SPEED_JITTER),
        PING_SPEED_MIN,
        PING_SPEED_MAX
      );
    }

    ball.tx = ball.bx;
    ball.ty = ball.by;
    ball.radiusTarget = 1;
  }
}

/**
 * PLACER — the balls hold still until a cue scatters them to fresh positions.
 *
 * Targets are rejection-sampled so no two sit closer than PLACER_SEPARATION
 * times the Radius knob. That constraint has no solution once Radius is wide, so
 * the search is capped and keeps the roomiest set it saw rather than looping.
 */
function placeBalls() {
  var minDistance = PLACER_SEPARATION * baseRadius();
  var margin = clamp(baseRadius() * 0.5, 0.02, 0.3);
  var lo = margin;
  var hi = 1 - margin;

  var bestX = [];
  var bestY = [];
  var bestScore = -1;

  for (var attempt = 0; attempt < PLACER_ATTEMPTS; ++attempt) {
    var xs = [];
    var ys = [];
    for (var i = 0; i < balls.length; ++i) {
      xs.push(randomRange(lo, hi));
      ys.push(randomRange(lo, hi));
    }

    // Score a candidate set by its tightest pair, so the fallback is the most
    // spread out set the search happened to see.
    var closest = Infinity;
    for (var a = 0; a < xs.length; ++a) {
      for (var b = a + 1; b < xs.length; ++b) {
        var dx = xs[a] - xs[b];
        var dy = ys[a] - ys[b];
        var d = Math.sqrt(dx * dx + dy * dy);
        if (d < closest) {
          closest = d;
        }
      }
    }
    if (closest > bestScore) {
      bestScore = closest;
      bestX = xs;
      bestY = ys;
    }
    if (closest >= minDistance) {
      break;
    }
  }

  for (var k = 0; k < balls.length; ++k) {
    balls[k].hx = bestX[k];
    balls[k].hy = bestY[k];
    balls[k].stillTime = 0;
  }
}

/**
 * Move one ball to a fresh spot, leaving the others where they are.
 *
 * Two different distances have to hold. Against the other balls it is the usual
 * separation, and against its own current spot it is at least a sway and a half
 * — otherwise a narrow Radius lets the search hand back a spot the ball is
 * already standing in, and since standing there is what triggered the move, it
 * would sit and re-roll every PLACER_SETTLE_TIME without ever going anywhere.
 *
 * Candidates are scored on how well they satisfy both as a fraction, so the
 * fallback after a failed search is the most balanced near-miss rather than one
 * that is generous about the others and useless about itself.
 */
function placeOne(ball) {
  var minOther = PLACER_SEPARATION * baseRadius();
  var minSelf = Math.max(minOther, PLACER_SETTLED * 1.5);
  var margin = clamp(baseRadius() * 0.5, 0.02, 0.3);
  var lo = margin;
  var hi = 1 - margin;

  var bestX = ball.hx;
  var bestY = ball.hy;
  var bestScore = -1;

  for (var attempt = 0; attempt < PLACER_ATTEMPTS; ++attempt) {
    var x = randomRange(lo, hi);
    var y = randomRange(lo, hi);

    var selfDx = x - ball.hx;
    var selfDy = y - ball.hy;
    var selfDistance = Math.sqrt(selfDx * selfDx + selfDy * selfDy);

    var otherDistance = Infinity;
    for (var i = 0; i < balls.length; ++i) {
      if (balls[i] === ball) {
        continue;
      }
      var dx = x - balls[i].hx;
      var dy = y - balls[i].hy;
      var d = Math.sqrt(dx * dx + dy * dy);
      if (d < otherDistance) {
        otherDistance = d;
      }
    }

    var score = Math.min(selfDistance / minSelf, otherDistance / minOther);
    if (score > bestScore) {
      bestScore = score;
      bestX = x;
      bestY = y;
    }
    if (score >= 1) {
      break;
    }
  }

  ball.hx = bestX;
  ball.hy = bestY;
  ball.stillTime = 0;
}

/**
 * PLACER — never quite still, and never settled for long.
 *
 * The sway is two sinusoids per ball, x and y on different frequencies so the
 * ball traces an open Lissajous figure instead of a circle, and each ball
 * detuned off its index so the three drift out of step with each other. It is
 * applied to the target rather than the position, so it arrives through the
 * controller and the balls lag it the way they lag everything else.
 *
 * A ball that has held its spot for PLACER_SETTLE_TIME goes and finds another,
 * on its own clock rather than the formation's — so the three trade places
 * continuously and a cue is a scatter on top of that, not the only thing that
 * ever moves them. "Held its spot" is measured against the ball's home rather
 * than its speed, because the sway means its speed never reaches zero.
 */
function updatePlacer(dt, cues) {
  if (cues > 0) {
    placeBalls();
  }

  var tau = Math.PI * 2;
  for (var i = 0; i < balls.length; ++i) {
    var ball = balls[i];
    var detune = 1 + i * PLACER_SWAY_DETUNE;
    ball.tx = ball.hx + PLACER_SWAY * Math.sin(tau * PLACER_SWAY_HZ_X * detune * simClock + i * 2.1);
    ball.ty = ball.hy + PLACER_SWAY * Math.sin(tau * PLACER_SWAY_HZ_Y * detune * simClock + i * 3.7);
    ball.radiusTarget = 1;

    var dx = ball.x - ball.hx;
    var dy = ball.y - ball.hy;
    if (dx * dx + dy * dy <= PLACER_SETTLED * PLACER_SETTLED) {
      ball.stillTime += dt;
      if (ball.stillTime >= PLACER_SETTLE_TIME) {
        placeOne(ball);
      }
    } else {
      // Still travelling — the clock only runs once it has arrived.
      ball.stillTime = 0;
    }
  }
}

/**
 * FIRELINE — the balls are thrown, fall on a parabola, and land.
 *
 * This is the one state that flies the balls directly instead of through the
 * controller: the spec is a ballistic arc, and a chased target would round the
 * apex off into a lob. The first ball down lights the strip along the bottom of
 * the frame, which fades up over a second; landed balls shrink away, leaving the
 * strip burning on its own.
 */
function updateFireline(dt) {
  for (var i = 0; i < balls.length; ++i) {
    var ball = balls[i];
    if (ball.landed) {
      ball.radiusTarget = 0;
      continue;
    }

    ball.vy -= FIRE_GRAVITY * dt;
    ball.x += ball.vx * dt;
    ball.y += ball.vy * dt;

    // Reflect off the sides so a hard throw lands inside the frame instead of
    // sailing out of it and dropping fuel where nothing can see it.
    if (ball.x < 0) { ball.x = -ball.x; ball.vx = -ball.vx; }
    else if (ball.x > 1) { ball.x = 2 - ball.x; ball.vx = -ball.vx; }

    if (ball.y <= FIRE_GROUND) {
      ball.y = FIRE_GROUND;
      ball.vx = 0;
      ball.vy = 0;
      ball.landed = true;
      ball.radiusTarget = 0;
      fireLineTarget = 1;
    }

    ball.tx = ball.x;
    ball.ty = ball.y;
  }
}

// ------------------------------------------------------- the controller

/**
 * Fly every ball to its target.
 *
 * A spring to the target, damping against its own velocity, and an integral term
 * for the droop that a ball sitting in its own updraft otherwise keeps. The
 * integral is clamped: a target a ball cannot reach — one parked outside the
 * frame by a wide Spread — would otherwise wind it up without limit and fire the
 * ball across the frame when the state changed.
 */
function driveBalls(dt) {
  var kp = lerp(2, 120, pidP);
  var kd = lerp(0.5, 20, pidD);
  var ki = lerp(0, 40, pidI);

  for (var i = 0; i < balls.length; ++i) {
    var ball = balls[i];

    if (!ball.ballistic) {
      var ex = ball.tx - ball.x;
      var ey = ball.ty - ball.y;

      ball.ix = clamp(ball.ix + ex * dt, -0.5, 0.5);
      ball.iy = clamp(ball.iy + ey * dt, -0.5, 0.5);

      ball.vx += (kp * ex + ki * ball.ix - kd * ball.vx) * dt;
      ball.vy += (kp * ey + ki * ball.iy - kd * ball.vy) * dt;

      var speed = Math.sqrt(ball.vx * ball.vx + ball.vy * ball.vy);
      if (speed > MAX_BALL_SPEED) {
        var scale = MAX_BALL_SPEED / speed;
        ball.vx *= scale;
        ball.vy *= scale;
      }

      ball.x += ball.vx * dt;
      ball.y += ball.vy * dt;
    }

    ball.radiusMul += (ball.radiusTarget - ball.radiusMul) * clamp(dt * RADIUS_CHASE, 0, 1);

    // A ball whose state went bad would keep emitting at a poisoned position
    // forever, so it gets parked at the formation center rather than lost.
    if (!isFinite(ball.x) || !isFinite(ball.y) || !isFinite(ball.vx) || !isFinite(ball.vy)) {
      ball.x = clamp(srcX, 0, 1);
      ball.y = clamp(srcY, 0, 1);
      ball.vx = 0;
      ball.vy = 0;
      ball.ix = 0;
      ball.iy = 0;
      ball.px = ball.x;
      ball.py = ball.y;
    }
  }
}

/** One choreography step: spend the cue, place the targets, fly the balls. */
function updateChoreography(dt) {
  // The count, not a flag: ORBIT counts cues to eight before it acts on them,
  // and collapsing two that arrived in one frame into a single "yes" would drop
  // one. States that only care whether a cue happened read it as truthy.
  var cues = pendingCue;
  pendingCue = 0;

  if (choreoState === STATE_ORBIT) {
    updateOrbit(dt, cues);
  } else if (choreoState === STATE_COLUMNS) {
    updateColumns(dt, cues);
  } else if (choreoState === STATE_PINGPONG) {
    updatePingpong(dt);
  } else if (choreoState === STATE_PLACER) {
    updatePlacer(dt, cues);
  } else {
    updateFireline(dt);
  }

  driveBalls(dt);

  // Linear so the second the spec asks for is actually a second.
  var fadeStep = dt / FIRELINE_FADE;
  if (fireLineLevel < fireLineTarget) {
    fireLineLevel = Math.min(fireLineTarget, fireLineLevel + fadeStep);
  } else if (fireLineLevel > fireLineTarget) {
    fireLineLevel = Math.max(fireLineTarget, fireLineLevel - fadeStep);
  }
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
 * Lay one soft-edged disc of fuel into the grid.
 *
 * Only the disc's bounding box is visited, since a source is a small part of the
 * grid and this runs several times a substep. Each stamp of a swept ball is a
 * full-strength one: overlapping stamps saturate against the fuel and heat
 * ceilings rather than accumulating without limit.
 *
 * @param {number} cx - Disc center, normalized
 * @param {number} cy - Disc center, normalized
 * @param {number} radius - Disc radius, normalized
 * @param {number} rate - Fuel deposited at the center of the disc
 * @param {number} push - Upward velocity added at the center, in cells/sec
 * @param {number} dragU - Fluid velocity to drag toward, in cells/sec
 * @param {number} dragV - Fluid velocity to drag toward, in cells/sec
 * @param {number} coupling - How completely to drag; 0 leaves the fluid alone
 */
function stampDisc(cx, cy, radius, rate, push, dragU, dragV, coupling) {
  var xMin = Math.max(0, Math.floor((cx - radius) * W1));
  var xMax = Math.min(W1, Math.ceil((cx + radius) * W1));
  var yMin = Math.max(0, Math.floor((cy - radius) * H1));
  var yMax = Math.min(H1, Math.ceil((cy + radius) * H1));

  for (var y = yMin; y <= yMax; ++y) {
    var ny = y / H1 - cy;
    for (var x = xMin; x <= xMax; ++x) {
      var nx = x / W1 - cx;
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

      if (coupling > 0) {
        // Drag rather than shove: the fluid is pulled a fraction of the way to
        // the ball's own velocity. Being a blend and not an impulse, it cannot
        // add energy without bound however fast the ball is going or however
        // many stamps land on the same cell.
        var k = coupling * falloff;
        if (k > 1) {
          k = 1;
        }
        velU[i] += (dragU - velU[i]) * k;
        velV[i] += (dragV - velV[i]) * k;
      }
    }
  }
}

/**
 * Feed the three source balls, smearing each along the path it just travelled.
 *
 * Every stamp along the segment gets the full source strength and the full
 * advection, not a share of it — the trail a moving ball leaves burns as hard as
 * the ball does, so speed buys length rather than costing brightness. Fuel and
 * heat clamp per cell, so a slow ball laying many overlapping stamps saturates
 * instead of running away.
 */
function injectSources(dt) {
  var strength = srcLevel * srcLevel * 9;
  var base = baseRadius();

  for (var b = 0; b < balls.length; ++b) {
    var ball = balls[b];
    var radius = base * ball.radiusMul;

    // Below a cell across there is nothing left to stamp; a shrinking FIRELINE
    // ball reaches this and stops emitting rather than dithering single cells.
    if (strength <= 0 || radius < 0.5 / Math.min(W1, H1)) {
      ball.px = ball.x;
      ball.py = ball.y;
      continue;
    }

    // Each ball wavers on its own phase — one shared waver would pulse all
    // three together and read as the whole pattern flickering.
    var waver = 1 + flicker * Noise.stb_perlin_noise3(simClock * 1.7, ball.index * 13.7, 0, 0, 0, 0) * 1.6;
    if (waver < 0) {
      waver = 0;
    }

    var dx = ball.x - ball.px;
    var dy = ball.y - ball.py;
    var distance = Math.sqrt(dx * dx + dy * dy);

    // Half a radius between stamps keeps the swept trail solid; a lone stamp
    // for a ball that barely moved keeps a still ball cheap.
    var stamps = 1;
    if (distance > 0) {
      stamps = Math.ceil(distance / Math.max(radius * 0.5, 1e-4));
      if (stamps < 1) {
        stamps = 1;
      } else if (stamps > MAX_SWEEP_STAMPS) {
        stamps = MAX_SWEEP_STAMPS;
      }
    }

    var rate = strength * waver * dt;
    var push = jet * jet * 45 * waver * dt;

    // The fluid is dragged toward the ball's own velocity, converted from
    // frame-widths per second into the grid's cells per second.
    var dragU = ball.vx * W1;
    var dragV = ball.vy * H1;
    var coupling = advect * advect * 12 * dt;

    for (var s = 0; s < stamps; ++s) {
      // Stamp centers sit on the segment, offset half a step so the trail is
      // symmetric about the path rather than piling up on one end of it.
      var t = (s + 0.5) / stamps;
      stampDisc(
        ball.px + dx * t,
        ball.py + dy * t,
        radius,
        rate,
        push,
        dragU,
        dragV,
        coupling
      );
    }

    ball.px = ball.x;
    ball.py = ball.y;
  }

  injectFireLine(dt);
}

/**
 * FIRELINE's strip: the whole bottom edge as one source.
 *
 * Present in every state and silent in all but one — its level is what fades,
 * so the strip lights over a second when the balls land and dies over a second
 * when the choreography moves on, rather than switching with the state.
 */
function injectFireLine(dt) {
  if (fireLineLevel <= 0) {
    return;
  }

  var strength = srcLevel * srcLevel * 9 * FIRELINE_DENSITY * fireLineLevel;
  if (strength <= 0) {
    return;
  }
  var rows = Math.min(FIRELINE_ROWS, H);
  var lift = FIRELINE_LIFT * fireLineLevel * dt;
  var swirl = FIRELINE_SWIRL * fireLineLevel * dt;

  for (var x = 0; x < W; ++x) {
    // Flicker varies along the strip as well as over time, so the line breaks
    // into tongues instead of pulsing as one bar.
    var waver = 1 + flicker * Noise.stb_perlin_noise3(x * 0.35, simClock * 2.2, 0, 0, 0, 0) * 1.3;
    if (waver < 0) {
      waver = 0;
    }
    // The strip's own gusting, on noise slices of its own. One gust drives both
    // the fuel and the lift under it, so a patch that is burning hard is also
    // the patch throwing itself upward — which is what a tongue of flame is,
    // rather than a bright spot and a draught that happen to share an address.
    var gust = 1 + FIRELINE_GUST * Noise.stb_perlin_noise3(x * 0.7, simClock * 2.6, 3.1, 0, 0, 0);
    if (gust < 0) {
      gust = 0;
    }

    // A second, faster noise on a different slice pushes sideways as well as up.
    // Lift alone gives a flat sheet of flame; this is what makes the strip curl
    // into separate tongues that lean and cross as they climb.
    var lateral = Noise.stb_perlin_noise3(x * 0.55, simClock * 3.1, 7.3, 0, 0, 0);

    var rate = strength * waver * gust * dt;

    for (var y = 0; y < rows; ++y) {
      var falloff = 1 - y / rows;
      var i = y * W + x;
      var f = fuel[i] + rate * falloff;
      fuel[i] = f > 1 ? 1 : f;
      var h = heat[i] + rate * falloff * 0.5;
      heat[i] = h > BURN_TEMP ? BURN_TEMP : h;
      velV[i] += lift * waver * gust * falloff;
      velU[i] += swirl * lateral * falloff;
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
  // Choreography moves first so the swept stamp smears over exactly the motion
  // that belongs to this substep.
  updateChoreography(dt);
  advectVelocity(dt);
  injectSources(dt);
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

  // Spent here rather than inside the substep loop: a state change is one
  // event, and firing it in each substep of a frame would re-roll the state
  // several times over.
  while (pendingChoreo > 0) {
    --pendingChoreo;
    enterState(randomOtherState(choreoState));
    print("FluidFire.js: choreography -> " + STATE_NAMES[choreoState]);
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
