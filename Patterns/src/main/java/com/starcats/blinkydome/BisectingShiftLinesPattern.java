package com.starcats.blinkydome;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Lines march in on the beat, then the whole picture slides over, cuts itself
 * open and fills the opening with a flat new tone. Repeat forever, the axis
 * turning a quarter turn each time. Ported from Scripts/BisectingShiftLines.js.
 *
 * A phase is four beats by default. On the first three a line enters from one
 * edge of the frame and extends across it, one per beat. On the fourth a cut is
 * struck across the far edge of everything drawn — a line laid down whole rather
 * than extended, marking where the picture is about to come apart — and the
 * entire scene translates back toward the edge it came from, far enough to bring
 * that cut in to the middle of the frame. Everything is carried along with it,
 * and what opens up behind is a solid field of a new tone. The next phase draws
 * into that field, on the perpendicular axis, growing away from a perpendicular
 * edge; then it cuts and shifts again, and so on. Lines enter downward and the
 * picture slides up; then lines enter rightward and the picture slides left;
 * then up-or-down again, chosen by coin flip, forever.
 *
 * There is no canvas and nothing is ever erased. The picture is an ordered list
 * of two kinds of opaque marks in a world that extends past the frame, plus a
 * camera offset, and a shift is nothing but the camera moving:
 *
 *   - a LINE, a rectangle of one flat tone;
 *   - a PAINT, a half-plane of one flat tone, laid down at the moment of a cut
 *     and covering everything beyond it.
 *
 * A paint is what makes the opening a color rather than a hole. It is placed in
 * world coordinates at the cut, so it does not have to be animated or clipped:
 * the camera slides the frame across it and the field is revealed at exactly the
 * rate the picture moves. Marks are only ever appended, so walking the list from
 * newest back is a painter's algorithm in reverse — the first mark covering a
 * point is the one you see, and a paint correctly buries every line older than
 * the cut while leaving every line drawn after it on top.
 *
 * The tones are the four quarters 0, 1/3, 2/3, 1. Lines are black on every field
 * but the black one, where they go light because black on black is nothing, so
 * the piece reads as black ruling over flat ground with the darkest sections
 * inverting. They are flat: a mark is its tone edge to edge, and the only
 * blending anywhere is the pixel of anti-aliasing at a mark's border.
 *
 * The four states — top, bottom, left, right — are one piece of code. A state is
 * an axis and a sign, and everything else is written against a coordinate `t`
 * that runs 0 at the edge the lines come from to 1 at the edge they reach. Lines
 * grow t upward, the camera moves t downward, the field laid down is t from the
 * split to 1, and the next phase's lines are placed randomly inside it. Which
 * literal direction any of that is depends only on which pair the state names.
 *
 * All of that lives on a square laid over the frame, which exists to make the
 * world isotropic — a shift, a rotation and a line's thickness all have to mean
 * the same thing along either axis, and the frame itself need not be square.
 * Splits, placements and widths are specified against the frame and converted
 * through it, so "50%" is half of what you can actually see, and a beat of
 * extending is one traverse of it.
 *
 * The composition is therefore exactly frame-sized, and turning it swings its
 * corners in: at any angle off the square the outer corners of the frame fall
 * outside the picture and sit at the opening tone. That is the honest reading of
 * a design whose whole vocabulary — top, bottom, left, right, half way up — is
 * written against the edges of a frame, and on a fixture that is not a filled
 * rectangle anyway it costs nothing.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Bisecting Shift Lines")
@LXComponent.Description("Ruled lines that cut the picture open and slide it aside")
public class BisectingShiftLinesPattern extends LXPattern {

  private static final double TAU = Math.PI * 2;

  private static final int TOP = 0;
  private static final int BOTTOM = 1;
  private static final int LEFT = 2;
  private static final int RIGHT = 3;

  /**
   * A state is an axis and a direction, and nothing else. `axis` is 0 for the
   * horizontal one and 1 for the vertical; `flip` says the lines grow toward the
   * low end of it rather than the high end. Every difference between the four
   * states is one of these two lookups.
   */
  private static final int[] STATE_AXIS = { 1, 1, 0, 0 };
  private static final boolean[] STATE_FLIP = { true, false, false, true };

  /** The two kinds of mark. See the header. */
  private static final int KIND_LINE = 0;
  private static final int KIND_PAINT = 1;

  /**
   * The four tones a revealed field can take, and the tone the lines drawn on
   * each of them get.
   *
   * Lines are black on every field that carries any light at all, so the picture
   * reads as black rule-work laid over flat ground and the ruling stays one
   * thing throughout rather than changing identity with each cut. The black
   * field is the one exception, because black lines on it would be no lines at
   * all; there they go light instead, and that is the only place in the piece
   * where the ruling is lighter than what it sits on.
   *
   * Every pair here differs, which is what guarantees a line is always visible
   * against its own ground. See beginShift for the other half of that guarantee
   * — a field never opens onto the tone already showing.
   */
  private static final double[] BG_LEVELS = { 0, .33, .66, 1 };
  private static final double[] LINE_LEVELS = { .66, 0, 0, 0 };

  /** The ground before the first cut, and the lines that go on it. */
  private static final int BASE_INDEX = 0;

  /** Most lines a single phase can draw, and so the longest a phase can be. */
  private static final int LINES_MAX = 5;

  /**
   * Marks kept alive.
   *
   * Nothing is ever erased, so the count is set entirely by how fast updateItems
   * can prove marks buried. That turns out to be fast, because a cut buries in
   * bulk: over two hours the retained list peaks at 60 and the drawn subset at
   * about 40, whatever the aspect and whatever the angle.
   *
   * The ceiling has to clear that by a wide margin, because once it binds the
   * overflow in allocItem starts evicting the oldest mark whether or not anyone
   * can see it, and a mark that is still visible blinks out where you are
   * looking. Four times the measured peak buys a lot of room for a run longer
   * than any tested here.
   */
  private static final int MAX_ITEMS = 256;

  /** Line width as a fraction of the frame's height, at either end of the knob. */
  private static final double THICK_MIN = .004;
  private static final double THICK_MAX = .12;

  /**
   * How far past the frame a mark has to be carried before it is forgotten.
   *
   * Not zero, because shifts alternate axes but not directions: two phases
   * apart, the picture can slide back the way it came, and something that had
   * just left the frame ought to return rather than having quietly ceased to
   * exist. One shift is at most 0.92 of a frame span, so this covers any single
   * reversal; nothing that needs two of them in a row is still worth carrying.
   */
  private static final double PRUNE_MARGIN = .7;

  /** Slack on the visibility test, so a mark's soft edge is never clipped. */
  private static final double EDGE_MARGIN = .05;

  /** Full cycles of the tone phase per second at the top of the Speed knob. */
  private static final double PHASE_RATE = .25;

  /**
   * How far past each end of the frame a line is drawn while it is being laid
   * down, as a fraction of the frame's span on its own axis.
   *
   * A line that stops exactly at the frame edge has an end, and turning the
   * scene swings that end into view — the line is then plainly a segment that
   * halts in mid-air rather than something running off the side of the world,
   * and the whole illusion goes with it. Drawing half a frame further at each
   * end puts both ends outside anything a rotation can reach.
   *
   * Whether a half is *enough* is a matter of geometry rather than taste, and it
   * depends on the model: the visible square, turned, reaches a half-diagonal
   * from center, which is 0.5 in scene units whatever the aspect. An end sits
   * span/2 + overdraw from center, so clearing it wants overdraw >= 0.5 -
   * span/2. On a squarish model a half of the span is comfortably more than
   * that; on a wide one the short axis needs a little more, so axisOver takes
   * whichever is larger and the ends stay hidden either way.
   */
  private static final double OVERDRAW = .5;

  public final CompoundParameter bpm =
    new CompoundParameter("BPM", .5, 0, 1)
    .setDescription("Tempo everything is cut to; 0.5 is 120");

  public final CompoundParameter rate =
    new CompoundParameter("Rate", 0, 0, 1)
    .setDescription("How much quicker than a beat a line extends or the scene shifts; 0 is exactly one beat, 1 is three times");

  public final CompoundParameter count =
    new CompoundParameter("Count", .5, 0, 1)
    .setDescription("Lines drawn per phase, before the cut");

  public final CompoundParameter split =
    new CompoundParameter("Split", .5, 0, 1)
    .setDescription("Where the cut lands, as a fraction of the frame");

  public final CompoundParameter spread =
    new CompoundParameter("Spread", .4, 0, 1)
    .setDescription("How far the cut wanders from that, a quarter of the frame either way at full");

  public final CompoundParameter minThick =
    new CompoundParameter("Min", .12, 0, 1)
    .setDescription("Thinnest a line can be");

  public final CompoundParameter maxThick =
    new CompoundParameter("Max", .3, 0, 1)
    .setDescription("Thickest a line can be");

  public final CompoundParameter angle =
    new CompoundParameter("Angle", 0, 0, 1)
    .setDescription("Where the scene is turned to; a full turn across the knob");

  /**
   * Advances `phase`, which every tone is shifted by on the way out.
   *
   * Zero leaves the picture exactly as composed, and that is the point of the
   * strict wrap in shiftTone: at rest this control does nothing at all rather
   * than something very small.
   *
   * A warning about turning it up, because the tones are levels and the shift is
   * cyclic, which are not the same kind of thing. Tone 1 and tone 0 are one step
   * apart on a cycle of length 1, so any nonzero phase drives them to the same
   * value — and the white field is exactly the one whose lines are black. Sweep
   * this and the white sections go flat, their ruling swallowed, until the phase
   * comes back around. Every other field keeps its lines throughout. If you want
   * the sweep without that, the fix is in the tone table: no two entries of
   * BG_LEVELS and LINE_LEVELS that need to stay apart may differ by exactly 1.
   */
  public final CompoundParameter speed =
    new CompoundParameter("Speed", 0, 0, 1)
    .setDescription("How fast the tones cycle; 0 leaves them alone");

  /**
   * Deliberately low. Every mark is flat to its own edge, so this is strictly
   * the anti-aliasing on that edge and not shading: at 0.12 the transition is
   * under 1% of the frame, which is about half a pixel on a fixture 60 points
   * across — enough to keep a near-horizontal edge from stepping, little enough
   * that the tones still read as poster-flat. Turn it up only if the fixture is
   * coarser.
   */
  public final CompoundParameter soft =
    new CompoundParameter("Soft", .12, 0, 1)
    .setDescription("Edge softness — this is the anti-aliasing, not shading");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", .55, 0, 1)
    .setDescription("Hue everything is tinted");

  public final CompoundParameter sat =
    new CompoundParameter("Sat", 0, 0, 1)
    .setDescription("Saturation of that tint; 0 leaves the four tones as greys");

  public final CompoundParameter level =
    new CompoundParameter("Level", 1, 0, 1)
    .setDescription("Overall brightness");

  public final BooleanParameter autoAspect =
    new BooleanParameter("Aspect", true)
    .setDescription("Correct for a non-square model");

  // ---------------------------------------------------------------- the picture
  //
  // Marks, oldest first, in world coordinates on the scene square. Parallel flat
  // arrays rather than objects: these are walked per LED, and the flat form
  // keeps the inner loop free of field indirection. Compaction in updateItems
  // preserves the order, which is the whole basis of the painter's walk in
  // draw().

  private final int[] itemKind = new int[MAX_ITEMS];
  private final int[] itemAxis = new int[MAX_ITEMS];   // line: axis it runs along; paint: axis it cuts
  private final double[] itemPos = new double[MAX_ITEMS];   // line: perpendicular coordinate
  private final double[] itemBase = new double[MAX_ITEMS];  // line: fixed end; paint: boundary
  private final double[] itemLen = new double[MAX_ITEMS];   // line: reach once fully extended
  private final int[] itemDir = new int[MAX_ITEMS];    // line: growth way; paint: side covered
  private final double[] itemHalf = new double[MAX_ITEMS];  // line: half its thickness
  private final double[] itemBorn = new double[MAX_ITEMS];  // line: beat it started extending
  private final double[] itemOd = new double[MAX_ITEMS];    // line: overrun at each end
  private final double[] itemCut = new double[MAX_ITEMS];   // line: beat its phase was cut, or +inf
  private final double[] itemLevel = new double[MAX_ITEMS]; // the flat tone it lays down
  private int itemN = 0;

  // Scratch, written by updateItems' first pass and read by its second: whether
  // each mark survives, whether it is drawn, and where it landed on screen.
  private final boolean[] itemKeep = new boolean[MAX_ITEMS];
  private final boolean[] itemDraw = new boolean[MAX_ITEMS];
  private final double[] itemSLo = new double[MAX_ITEMS];
  private final double[] itemSHi = new double[MAX_ITEMS];
  private final double[] itemSPos = new double[MAX_ITEMS];

  // This frame's visible marks in screen coordinates, rebuilt by updateItems so
  // draw() is a straight walk with no camera arithmetic in it. A paint keeps its
  // boundary in drawLo and the side it covers in drawDir; a line uses drawLo and
  // drawHi for its extent and leaves drawDir alone.
  private final int[] drawKind = new int[MAX_ITEMS];
  private final int[] drawAxis = new int[MAX_ITEMS];
  private final double[] drawPos = new double[MAX_ITEMS];
  private final double[] drawLo = new double[MAX_ITEMS];
  private final double[] drawHi = new double[MAX_ITEMS];
  private final double[] drawHalf = new double[MAX_ITEMS];
  private final int[] drawDir = new int[MAX_ITEMS];
  private final double[] drawLevel = new double[MAX_ITEMS];
  private int drawN = 0;

  // ----------------------------------------------------------------- the clock

  private double beats = 0;
  private long lastBeat = -1;

  // ----------------------------------------------------------------- the phase

  private int phaseState = TOP;
  private long phaseStart = 0;
  private int phaseCount = 3;
  private double phaseSplit = .5;   // as a fraction of the frame, not of the scene square

  // The tone of the field the current phase is drawing into, and so of its lines.
  private int sectionIndex = BASE_INDEX;
  private double sectionLevel = LINE_LEVELS[BASE_INDEX];

  // What endShift worked out for the phase after it. Held here rather than
  // returned so that a phase change, like a frame, allocates nothing.
  private int nextState = TOP;
  private double nextBandLo = 0;
  private double nextBandHi = 1;

  // The lines of the current phase, planned in full when the phase begins so
  // that they can be guaranteed not to overlap, and revealed one per beat.
  private final double[] planPos = new double[LINES_MAX];
  private final double[] planHalf = new double[LINES_MAX];
  private final double[] planW = new double[LINES_MAX];
  private final double[] planGap = new double[LINES_MAX + 1];

  // ---------------------------------------------------------------- the camera

  private double camU = 0;
  private double camV = 0;
  private double camBaseU = 0;
  private double camBaseV = 0;
  private double shiftDU = 0;
  private double shiftDV = 0;
  private double shiftStart = 0;

  // -------------------------------------------------- frame-derived quantities

  private double aspectX = 1;
  private double sceneR = Math.sqrt(2);
  private final double[] axisMargin = { 0, 0 };  // where the frame starts, on the scene square
  private final double[] axisSpan = { 1, 1 };    // and how much of the square it covers
  private final double[] axisOver = { 0, 0 };    // and how far a line overruns each end of it
  private double cosT = 1;
  private double sinT = 0;

  /** Cycles 0..1 under the Speed knob; every tone leaves shifted by it. */
  private double phase = 0;

  /** The starting ground, phase-shifted; refreshed each frame by updateItems. */
  private double groundLevel = BG_LEVELS[BASE_INDEX];
  private double rateMul = 1;
  private double softW = .02;
  private double thickLo = .01;
  private double thickHi = .03;
  private boolean started = false;
  private LXModel startedModel = null;

  public BisectingShiftLinesPattern(LX lx) {
    super(lx);
    addParameter("bpm", this.bpm);
    addParameter("rate", this.rate);
    addParameter("count", this.count);
    addParameter("split", this.split);
    addParameter("spread", this.spread);
    addParameter("minThick", this.minThick);
    addParameter("maxThick", this.maxThick);
    addParameter("angle", this.angle);
    addParameter("speed", this.speed);
    addParameter("soft", this.soft);
    addParameter("hue", this.hue);
    addParameter("sat", this.sat);
    addParameter("level", this.level);
    addParameter("autoAspect", this.autoAspect);
  }

  private void resetScene() {
    this.itemN = 0;
    this.drawN = 0;
    this.beats = 0;
    this.lastBeat = -1;
    this.camU = this.camV = this.camBaseU = this.camBaseV = 0;
    this.shiftDU = this.shiftDV = 0;
    this.phase = 0;
    this.sectionIndex = BASE_INDEX;
    this.sectionLevel = LINE_LEVELS[BASE_INDEX];
    // The whole frame is fair game for the opening phase, since nothing has been
    // cut yet and there is no field to draw into.
    beginPhase(0, TOP, 0, 1);
    this.started = true;
    this.startedModel = this.model;
  }

  /** Smoothstep. Both the extending and the shifting run on it. */
  private static double ease(double p) {
    return p * p * (3 - 2 * p);
  }

  /**
   * A tone, moved around the cycle by the current phase.
   *
   * Applied to each mark's own tone rather than to the finished pixel, which for
   * everything but the hairline of anti-aliasing at a border is the same
   * arithmetic — inside a mark the pixel *is* that mark's tone. At a border it
   * is the difference between right and wrong. Shifting afterwards would blend
   * the two original tones and then wrap the blend, and wherever the wrap fell
   * inside that blend the seam would sweep the whole range and draw a
   * bright-then-dark outline around every shape in the picture. Shifting first
   * means the seam only ever interpolates between the two tones actually being
   * shown.
   *
   * It is also a good deal cheaper here: a few dozen marks a frame rather than
   * once for every LED.
   *
   * The wrap is strict rather than a floor, so a phase of zero is an exact
   * identity and tone 1 stays white instead of dropping to black at rest.
   */
  private double shiftTone(double v) {
    v += this.phase;
    return (v > 1) ? v - 1 : v;
  }

  /** A half-thickness somewhere in the range the two knobs bracket. */
  private double randomHalf() {
    return (this.thickLo + Math.random() * (this.thickHi - this.thickLo)) * .5;
  }

  // ------------------------------------------------------------- phase changes

  /**
   * Start a phase in `state`, placing its lines somewhere in the field the last
   * cut opened up.
   *
   * The band arrives as a fraction of the frame along the axis of the *previous*
   * phase, which is the axis a new line's position varies along — the two
   * alternate, so the previous phase's axis is this one's perpendicular.
   */
  private void beginPhase(long beat, int state, double bandLo, double bandHi) {
    this.phaseState = state;
    this.phaseStart = beat;
    this.phaseCount = 1 + (int) Math.round(clamp(this.count.getValue(), 0, 1) * (LINES_MAX - 1));
    this.phaseSplit = clamp(
      this.split.getValue() + (Math.random() * 2 - 1) * .25 * this.spread.getValue(),
      .08, .92);

    int perp = 1 - STATE_AXIS[state];
    planLines(
      this.axisMargin[perp] + bandLo * this.axisSpan[perp],
      this.axisMargin[perp] + bandHi * this.axisSpan[perp]
    );
  }

  /**
   * Choose this phase's line positions and widths, in scene coordinates, inside
   * the band from `lo` to `hi`.
   *
   * They must not overlap, so they are packed rather than sampled independently:
   * widths are drawn first, scaled down together if they cannot all fit, and
   * what is left over is split into the gaps around them by a random partition.
   * That gives non-overlap by construction instead of by rejection, which
   * matters because a narrow band and thick lines can make rejection sampling
   * take arbitrarily long or never succeed at all.
   *
   * The order is then shuffled, so that the first line drawn is not always the
   * one nearest the low edge of the band.
   */
  private void planLines(double lo, double hi) {
    int n = this.phaseCount;
    int i;

    double span = hi - lo;
    if (span < .02) {
      // Degenerate band — a split pushed hard against an edge. Open it up around
      // its own center rather than trying to pack into nothing.
      double mid = (lo + hi) * .5;
      lo = mid - .01;
      hi = mid + .01;
      span = hi - lo;
    }

    double total = 0;
    for (i = 0; i < n; ++i) {
      this.planW[i] = randomHalf() * 2;
      total += this.planW[i];
    }

    // Leave a little air even in the worst case, so lines packed into a tight
    // band still read as separate lines rather than as one solid block.
    double cap = span * .85;
    if (total > cap) {
      double k = cap / total;
      for (i = 0; i < n; ++i) {
        this.planW[i] *= k;
      }
      total = cap;
    }

    double gapSum = 0;
    for (i = 0; i <= n; ++i) {
      this.planGap[i] = Math.random();
      gapSum += this.planGap[i];
    }
    if (gapSum <= 0) {
      gapSum = 1;
    }

    double slack = span - total;
    double at = lo;
    for (i = 0; i < n; ++i) {
      at += slack * this.planGap[i] / gapSum;
      this.planPos[i] = at + this.planW[i] * .5;
      this.planHalf[i] = this.planW[i] * .5;
      at += this.planW[i];
    }

    for (i = n - 1; i > 0; --i) {
      int j = (int) Math.floor(Math.random() * (i + 1));
      double p = this.planPos[i]; this.planPos[i] = this.planPos[j]; this.planPos[j] = p;
      double h = this.planHalf[i]; this.planHalf[i] = this.planHalf[j]; this.planHalf[j] = h;
    }
  }

  /** Reserve the next slot, dropping the oldest mark if the list is full. */
  private int allocItem() {
    if (this.itemN >= MAX_ITEMS) {
      for (int i = 1; i < this.itemN; ++i) {
        this.itemKind[i - 1] = this.itemKind[i];
        this.itemAxis[i - 1] = this.itemAxis[i];
        this.itemPos[i - 1] = this.itemPos[i];
        this.itemBase[i - 1] = this.itemBase[i];
        this.itemLen[i - 1] = this.itemLen[i];
        this.itemDir[i - 1] = this.itemDir[i];
        this.itemHalf[i - 1] = this.itemHalf[i];
        this.itemBorn[i - 1] = this.itemBorn[i];
        this.itemOd[i - 1] = this.itemOd[i];
        this.itemCut[i - 1] = this.itemCut[i];
        this.itemLevel[i - 1] = this.itemLevel[i];
      }
      --this.itemN;
    }
    return this.itemN++;
  }

  /**
   * Lay a line along `axis`, at `pos` across it, growing from `base` in `dir`.
   *
   * Everything is in world coordinates, which is the whole trick: from here on
   * the mark never moves, and every shift is the camera moving instead.
   */
  private void addLine(int axis, double pos, double base, double len, int dir, double half,
      double born, double od, double cut, double lvl) {
    int slot = allocItem();
    this.itemKind[slot] = KIND_LINE;
    this.itemAxis[slot] = axis;
    this.itemPos[slot] = pos;
    this.itemBase[slot] = base;
    this.itemLen[slot] = len;
    this.itemDir[slot] = dir;
    this.itemHalf[slot] = half;
    this.itemBorn[slot] = born;
    this.itemOd[slot] = od;
    this.itemCut[slot] = cut;
    this.itemLevel[slot] = lvl;
  }

  /** Flood everything past `bound` on `axis`, on the `dir` side of it. */
  private void addPaint(int axis, double bound, int dir, double lvl, double od, double born) {
    int slot = allocItem();
    this.itemKind[slot] = KIND_PAINT;
    this.itemAxis[slot] = axis;
    this.itemBase[slot] = bound;
    this.itemDir[slot] = dir;
    this.itemOd[slot] = od;
    this.itemBorn[slot] = born;
    this.itemCut[slot] = Double.POSITIVE_INFINITY;
    this.itemLevel[slot] = lvl;
    // Not written by the paint path, but the slot may be recycled from a line.
    this.itemPos[slot] = 0;
    this.itemLen[slot] = 0;
    this.itemHalf[slot] = 0;
  }

  /**
   * Send in the field that the coming cut will open onto — a beat before the
   * cut.
   *
   * A field is a half-plane, so it has no far end to hide, but it does have a
   * boundary, and dropping that boundary onto the frame's far edge at the moment
   * of the cut is a pop: square-on the edge is off screen and nothing shows, but
   * a turned frame reaches past it into the corner, and a whole triangle of new
   * tone would appear out of nowhere. So the field starts an overrun further
   * out, where even a turned frame cannot see it, and slides that overrun off
   * before it is needed. It has to be sent early to do this — hence a beat ahead
   * — and it arrives exactly as the cut lands, boundary already on the edge and
   * still.
   *
   * Square-on, the whole slide happens beyond the frame and is invisible; the
   * field still begins to enter the picture precisely on the cut. Turned, the
   * corner fills gradually instead of blinking. Either way the boundary is where
   * it should be by the time it matters, so nothing downstream — the split, the
   * camera, the cut line — knows this happened.
   *
   * One useful side effect: the field reaches the boundary just as the phase's
   * lines are trimmed back to it, and it already covers everything past that
   * point, so the trim has nothing left to reveal and the snap is seamless.
   */
  private void beginField(long beat) {
    int axis = STATE_AXIS[this.phaseState];
    boolean flip = STATE_FLIP[this.phaseState];
    int dir = flip ? -1 : 1;
    double edge = phaseBase(axis, flip) + dir * this.axisSpan[axis];

    // Never the tone already on screen, or the cut would open onto its own
    // ground and the whole thing it is there to announce would be invisible.
    int pick = (this.sectionIndex + 1 + (int) Math.floor(Math.random() * 3)) % 4;
    addPaint(axis, edge, dir, BG_LEVELS[pick], this.axisOver[axis], beat);
    this.sectionIndex = pick;
    this.sectionLevel = LINE_LEVELS[pick];
  }

  /** Where this phase's lines start from, in world coordinates. */
  private double phaseBase(int axis, boolean flip) {
    return (flip ? 1 - this.axisMargin[axis] : this.axisMargin[axis])
      + ((axis == 0) ? this.camU : this.camV);
  }

  /** Put the k'th planned line into the picture, at the edge, with no length yet. */
  private void spawnLine(int k, long beat) {
    int axis = STATE_AXIS[this.phaseState];
    boolean flip = STATE_FLIP[this.phaseState];
    addLine(
      axis,
      this.planPos[k] + ((axis == 0) ? this.camV : this.camU),
      // Edge of frame to edge of frame, so the cut that follows is exactly the
      // fraction of the frame the split asks for. The overdraw hangs off either
      // end of that and is not part of it.
      phaseBase(axis, flip),
      this.axisSpan[axis],
      flip ? -1 : 1,
      this.planHalf[k],
      // The beat itself rather than the current time, so a long frame cannot let
      // a line start late and drift off the grid.
      beat,
      this.axisOver[axis],
      Double.POSITIVE_INFINITY,
      this.sectionLevel
    );
  }

  /**
   * Strike the cut and set the camera moving.
   *
   * All three things that happen here sit at the same world coordinate — the far
   * edge of everything this phase drew. The picture is about to come apart along
   * it, so it gets, in this order: the field flooding everything beyond it, then
   * the cut line laid across it. Order is what makes the cut read as a cut
   * rather than as an edge: the paint buries the ends of the lines that ran up
   * to it, and the cut line goes on top of the paint, whole and at once, so the
   * opening arrives already marked instead of growing a border.
   *
   * The distance is fixed by where that edge has to end up: content covers the
   * frame edge to edge, and it has to come to rest covering the near edge to the
   * split, so the camera travels the rest of the frame — 1 - split of it.
   */
  private void beginShift(long beat) {
    int axis = STATE_AXIS[this.phaseState];
    boolean flip = STATE_FLIP[this.phaseState];
    int dir = flip ? -1 : 1;
    int perp = 1 - axis;

    double edge = phaseBase(axis, flip) + dir * this.axisSpan[axis];

    // Everything still uncut belongs to the phase now ending — nothing else can,
    // since every earlier phase was cut when it ended. Recording the beat here
    // is what lets a line work out, on its own, that its leading end is now the
    // boundary and its trailing overdraw is on its way out.
    for (int i = this.itemN - 1; i >= 0; --i) {
      if (this.itemKind[i] != KIND_LINE) {
        continue;
      }
      if (!Double.isInfinite(this.itemCut[i])) {
        break;
      }
      this.itemCut[i] = beat;
    }

    // The cut line keeps its overdraw for good rather than being trimmed to the
    // frame, because nothing ever cuts across *it* — so it is carried as a line
    // that is simply longer, already cut, with the overrun folded into its base
    // and length. Otherwise turning the scene would show its two ends stopping
    // inside the picture, which is exactly what the overdraw exists to prevent.
    double od = this.axisOver[perp];
    addLine(
      perp,
      edge,
      phaseBase(perp, false) - od,
      this.axisSpan[perp] + 2 * od,
      1,
      randomHalf(),
      beat,
      0,
      // Already cut, so it arrives at its full length rather than extending into
      // it: the cut is struck, not drawn.
      -1,
      this.sectionLevel
    );

    double dist = this.axisSpan[axis] * (1 - this.phaseSplit) * dir;
    this.shiftDU = (axis == 0) ? dist : 0;
    this.shiftDV = (axis == 1) ? dist : 0;
    this.shiftStart = beat;
  }

  /**
   * Bank the shift and pick what follows it.
   *
   * The next phase runs on the perpendicular axis, its direction a coin flip,
   * and it draws into the band the cut just opened — which is the split to the
   * far edge, or its mirror if the lines were growing the other way.
   */
  private void endShift() {
    this.camBaseU += this.shiftDU;
    this.camBaseV += this.shiftDV;
    this.shiftDU = 0;
    this.shiftDV = 0;

    boolean flip = STATE_FLIP[this.phaseState];
    this.nextBandLo = flip ? 0 : this.phaseSplit;
    this.nextBandHi = flip ? 1 - this.phaseSplit : 1;

    this.nextState = (STATE_AXIS[this.phaseState] == 1)
      ? ((Math.random() < .5) ? LEFT : RIGHT)
      : ((Math.random() < .5) ? TOP : BOTTOM);
  }

  /**
   * Advance the state machine one beat. A phase is `phaseCount` beats of drawing
   * and then one of cutting and shifting, and the beat after that belongs to the
   * next phase.
   */
  private void onBeat(long beat) {
    long k = beat - this.phaseStart;

    if (k > this.phaseCount) {
      endShift();
      beginPhase(beat, this.nextState, this.nextBandLo, this.nextBandHi);
      k = 0;
    }

    if (k < this.phaseCount) {
      spawnLine((int) k, beat);
      // The last line of the phase shares its beat with the field being sent in
      // for the cut that follows. Spawned first, so it still belongs to the tone
      // it was composed against rather than the one arriving.
      if (k == this.phaseCount - 1) {
        beginField(beat);
      }
    } else {
      beginShift(beat);
    }
  }

  // ----------------------------------------------------------------- per frame

  @Override
  protected void run(double deltaMs) {
    double dt = Double.isFinite(deltaMs) ? clamp(deltaMs * .001, 0, .25) : 0;

    updateFrame();

    this.rateMul = 1 + 2 * clamp(this.rate.getValue(), 0, 1);
    this.softW = lerp(.002, .05, this.soft.getValue()) * this.axisSpan[1];
    double a = lerp(THICK_MIN, THICK_MAX, clamp(this.minThick.getValue(), 0, 1)) * this.axisSpan[1];
    double b = lerp(THICK_MIN, THICK_MAX, clamp(this.maxThick.getValue(), 0, 1)) * this.axisSpan[1];
    this.thickLo = (a < b) ? a : b;
    this.thickHi = (a < b) ? b : a;

    // The script's init() hook fires on load and whenever the model changes, and
    // all it does is ask for a restart, because the knobs are not yet bound at
    // that point. Watching the model reference here is the same thing, without
    // needing a hook that runs before the pattern is ready to read itself.
    if (!this.started || this.startedModel != this.model) {
      resetScene();
    }

    // Set, not accumulated: the knob is the angle itself, so moving it puts the
    // scene where it says rather than changing how fast it drifts from here.
    double theta = clamp(this.angle.getValue(), 0, 1) * TAU;
    this.cosT = Math.cos(theta);
    this.sinT = Math.sin(theta);

    // Kept inside 0..1 so the wrap in shiftTone only ever has to fire once, and
    // so it cannot drift into the range where a float stops resolving small
    // steps.
    this.phase += clamp(this.speed.getValue(), 0, 1) * PHASE_RATE * dt;
    this.phase -= Math.floor(this.phase);

    // Musical time, accumulated rather than divided out of a wall clock, so that
    // moving the tempo knob changes the rate from here on instead of jumping the
    // phase to wherever the new tempo says it should already have been.
    double beatSec = 60 / lerp(40, 200, clamp(this.bpm.getValue(), 0, 1));
    this.beats += dt / beatSec;

    // A frame long enough to span two beats still gets both, in order.
    long beat = (long) Math.floor(this.beats);
    while (this.lastBeat < beat) {
      onBeat(++this.lastBeat);
    }

    double q = clamp((this.beats - this.shiftStart) * this.rateMul, 0, 1);
    this.camU = this.camBaseU + this.shiftDU * ease(q);
    this.camV = this.camBaseV + this.shiftDV * ease(q);

    updateItems();
    draw();
  }

  /**
   * Work out where the frame sits on the scene square.
   *
   * The square circumscribes the frame, which is what makes the world isotropic:
   * one unit means the same distance along either axis however oblong the model
   * is, so a rotation cannot skew and a line has one thickness rather than two.
   * Everything specified as a fraction of the frame — splits, line placements,
   * thicknesses — is converted through the margin and span this leaves.
   *
   * The square's actual size cancels out of every one of those conversions, so
   * it is the aspect ratio doing the work here and not the circumscribing.
   */
  private void updateFrame() {
    this.aspectX = 1;
    if (this.autoAspect.isOn() && this.model.xRange > 0 && this.model.yRange > 0) {
      this.aspectX = this.model.xRange / this.model.yRange;
    }

    this.sceneR = Math.sqrt(this.aspectX * this.aspectX + 1);
    this.axisSpan[0] = this.aspectX / this.sceneR;
    this.axisSpan[1] = 1 / this.sceneR;
    this.axisMargin[0] = (1 - this.axisSpan[0]) * .5;
    this.axisMargin[1] = (1 - this.axisSpan[1]) * .5;

    // The asked-for half of a span, or as much more as it takes to put a line's
    // end past the half-diagonal a turned frame can reach. See OVERDRAW.
    for (int a = 0; a < 2; ++a) {
      double wanted = OVERDRAW * this.axisSpan[a];
      double needed = .5 - this.axisSpan[a] * .5;
      this.axisOver[a] = (wanted > needed) ? wanted : needed;
    }
  }

  /**
   * Age every mark, forget the ones that can no longer be seen, and leave the
   * rest in screen coordinates for the renderer.
   *
   * What can still be seen is decided exactly rather than approximated, by
   * walking the list newest first and carrying the *uncovered window*: the
   * region no paint seen so far has already filled. A paint is an axis-aligned
   * half-plane, so the uncovered region always stays an axis-aligned rectangle —
   * intersecting it with one more half-plane just pushes one of its four sides
   * in. A mark that misses that window is buried, however recently it was laid
   * down, and when the window closes entirely every remaining mark is buried and
   * the walk can stop dead.
   *
   * All of this is done in world coordinates, and that is what makes it sound.
   * Marks never move relative to one another — only the camera moves, and it
   * moves all of them together — so the window is a fixed fact about the picture
   * and not something that can be invalidated by a later shift. Dropping a
   * buried mark is therefore permanent-safe: anything a paint hides sits on the
   * far side of that paint's boundary, so if the boundary is ever carried off
   * the frame, whatever it was hiding went with it.
   *
   * Survivors are compacted in place rather than filtered into a new array, so a
   * pattern that runs for hours allocates nothing after load.
   */
  private void updateItems() {
    int i;

    this.groundLevel = shiftTone(BG_LEVELS[BASE_INDEX]);

    // The frame in world coordinates, at each of the two margins: the loose one
    // decides what is worth keeping against a reversal, the tight one what is
    // actually drawn this frame.
    double lu0 = this.camU - PRUNE_MARGIN, lu1 = this.camU + 1 + PRUNE_MARGIN;
    double lv0 = this.camV - PRUNE_MARGIN, lv1 = this.camV + 1 + PRUNE_MARGIN;
    double tu0 = this.camU - EDGE_MARGIN, tu1 = this.camU + 1 + EDGE_MARGIN;
    double tv0 = this.camV - EDGE_MARGIN, tv1 = this.camV + 1 + EDGE_MARGIN;

    double wu0 = Double.NEGATIVE_INFINITY, wu1 = Double.POSITIVE_INFINITY;
    double wv0 = Double.NEGATIVE_INFINITY, wv1 = Double.POSITIVE_INFINITY;
    int floorIndex = 0;
    double paintBound = 0;

    for (i = this.itemN - 1; i >= 0; --i) {
      // The window clipped to the frame. Once this is empty nothing older than
      // this mark can ever show through again.
      double ku0 = (wu0 > lu0) ? wu0 : lu0, ku1 = (wu1 < lu1) ? wu1 : lu1;
      double kv0 = (wv0 > lv0) ? wv0 : lv0, kv1 = (wv1 < lv1) ? wv1 : lv1;
      if (ku0 > ku1 || kv0 > kv1) {
        floorIndex = i + 1;
        break;
      }

      int axis = this.itemAxis[i];
      int dir = this.itemDir[i];
      double bu0, bu1, bv0, bv1;   // the mark's own world bounds
      boolean extent = true;

      if (this.itemKind[i] == KIND_PAINT) {
        // A field slides the last of its overrun off before it is due, so that
        // by the time it is anyone's business it is already sitting on the
        // boundary.
        double pq = (this.beats - this.itemBorn[i]) * this.rateMul;
        if (pq < 0) { pq = 0; } else if (pq > 1) { pq = 1; }
        paintBound = this.itemBase[i] + dir * this.itemOd[i] * (1 - pq);

        bu0 = bv0 = Double.NEGATIVE_INFINITY;
        bu1 = bv1 = Double.POSITIVE_INFINITY;
        if (dir > 0) {
          if (axis == 0) { bu0 = paintBound; } else { bv0 = paintBound; }
        } else {
          if (axis == 0) { bu1 = paintBound; } else { bv1 = paintBound; }
        }
        this.itemSLo[i] = paintBound - ((axis == 0) ? this.camU : this.camV);
        this.itemSHi[i] = this.itemSLo[i];
        this.itemSPos[i] = 0;
      } else {
        // A line's two ends live on different clocks.
        //
        // Until its phase is cut it is being laid down, overrunning both ends of
        // the frame: it starts as nothing out beyond the near edge and its
        // leading end sweeps the whole overrun length. The cut then takes that
        // leading end instantly back to the boundary — that snap is the picture
        // coming apart, and it wants to be a cut and not a retraction. The
        // trailing overrun has no such reason to be abrupt, so it eases out
        // linearly across the shift, leaving the line exactly frame-length once
        // everything settles.
        double base = this.itemBase[i];
        double od = this.itemOd[i];
        double trail, head;

        if (this.beats < this.itemCut[i]) {
          double p = (this.beats - this.itemBorn[i]) * this.rateMul;
          double grown = (p >= 1) ? 1 : ease((p < 0) ? 0 : p);
          extent = grown > 0;
          trail = base - dir * od;
          head = trail + dir * (this.itemLen[i] + 2 * od) * grown;
        } else {
          double q = (this.beats - this.itemCut[i]) * this.rateMul;
          if (q < 0) { q = 0; } else if (q > 1) { q = 1; }
          trail = base - dir * od * (1 - q);
          head = base + dir * this.itemLen[i];
        }

        double alo = (trail < head) ? trail : head;
        double ahi = (trail < head) ? head : trail;
        double plo = this.itemPos[i] - this.itemHalf[i];
        double phi = this.itemPos[i] + this.itemHalf[i];

        if (axis == 0) {
          bu0 = alo; bu1 = ahi; bv0 = plo; bv1 = phi;
          this.itemSPos[i] = this.itemPos[i] - this.camV;
        } else {
          bv0 = alo; bv1 = ahi; bu0 = plo; bu1 = phi;
          this.itemSPos[i] = this.itemPos[i] - this.camU;
        }
        double camA = (axis == 0) ? this.camU : this.camV;
        this.itemSLo[i] = alo - camA;
        this.itemSHi[i] = ahi - camA;
      }

      this.itemKeep[i] = bu1 >= ku0 && bu0 <= ku1 && bv1 >= kv0 && bv0 <= kv1;

      double du0 = (wu0 > tu0) ? wu0 : tu0, du1 = (wu1 < tu1) ? wu1 : tu1;
      double dv0 = (wv0 > tv0) ? wv0 : tv0, dv1 = (wv1 < tv1) ? wv1 : tv1;
      this.itemDraw[i] = extent && du0 <= du1 && dv0 <= dv1 &&
        bu1 >= du0 && bu0 <= du1 && bv1 >= dv0 && bv0 <= dv1;

      // Older marks see the window with this paint's half-plane taken out of it.
      // Taken where the boundary is *now*, mid-slide or not — and a field only
      // ever covers more as it settles, so a mark buried under one stays buried
      // and dropping it is still permanent-safe.
      if (this.itemKind[i] == KIND_PAINT) {
        if (dir > 0) {
          if (axis == 0) { if (paintBound < wu1) { wu1 = paintBound; } }
          else { if (paintBound < wv1) { wv1 = paintBound; } }
        } else {
          if (axis == 0) { if (paintBound > wu0) { wu0 = paintBound; } }
          else { if (paintBound > wv0) { wv0 = paintBound; } }
        }
      }
    }

    int keep = 0;
    this.drawN = 0;

    for (i = floorIndex; i < this.itemN; ++i) {
      if (!this.itemKeep[i]) {
        continue;
      }

      if (this.itemDraw[i]) {
        this.drawKind[this.drawN] = this.itemKind[i];
        this.drawAxis[this.drawN] = this.itemAxis[i];
        this.drawPos[this.drawN] = this.itemSPos[i];
        this.drawLo[this.drawN] = this.itemSLo[i];
        this.drawHi[this.drawN] = this.itemSHi[i];
        this.drawHalf[this.drawN] = this.itemHalf[i];
        this.drawDir[this.drawN] = this.itemDir[i];
        this.drawLevel[this.drawN] = shiftTone(this.itemLevel[i]);
        ++this.drawN;
      }

      if (keep != i) {
        this.itemKind[keep] = this.itemKind[i];
        this.itemAxis[keep] = this.itemAxis[i];
        this.itemPos[keep] = this.itemPos[i];
        this.itemBase[keep] = this.itemBase[i];
        this.itemLen[keep] = this.itemLen[i];
        this.itemDir[keep] = this.itemDir[i];
        this.itemHalf[keep] = this.itemHalf[i];
        this.itemBorn[keep] = this.itemBorn[i];
        this.itemOd[keep] = this.itemOd[i];
        this.itemCut[keep] = this.itemCut[i];
        this.itemLevel[keep] = this.itemLevel[i];
      }
      ++keep;
    }

    this.itemN = keep;
  }

  private void draw() {
    final double hueDeg = this.hue.getValue() * 360;
    final double satPct = this.sat.getValue() * 100;
    final double lvl = this.level.getValue();

    for (LXPoint point : this.model.points) {
      // Into the scene square: center, correct the aspect, turn by the scene's
      // rotation backwards — rotating where we sample from is rotating what is
      // drawn — and scale so the square is the unit box.
      double cx = (point.xn - .5) * this.aspectX;
      double cy = point.yn - .5;
      double u = (cx * this.cosT + cy * this.sinT) / this.sceneR + .5;
      double v = (cy * this.cosT - cx * this.sinT) / this.sceneR + .5;

      // Newest mark first. Everything here is opaque and flat, so the first one
      // covering this point is the one that shows and the walk stops; the only
      // reason it carries a running total at all is the pixel of anti-aliasing
      // at each border, where a mark is partly covering and lets the rest
      // through.
      double accum = 0;
      double remaining = 1;

      for (int i = this.drawN - 1; i >= 0; --i) {
        int axis = this.drawAxis[i];
        double along = (axis == 0) ? u : v;
        double cover;

        if (this.drawKind[i] == KIND_PAINT) {
          cover = .5 + this.drawDir[i] * (along - this.drawLo[i]) / this.softW;
        } else {
          // Distance outside the rectangle, as the largest of the three ways of
          // being outside it. Negative inside. Taking the max rather than a true
          // Euclidean distance keeps the ends square, which is what a line
          // arrested exactly at the cut wants.
          double perp = (axis == 0) ? v : u;
          double d = Math.abs(perp - this.drawPos[i]) - this.drawHalf[i];
          double d0 = this.drawLo[i] - along;
          if (d0 > d) {
            d = d0;
          }
          double d1 = along - this.drawHi[i];
          if (d1 > d) {
            d = d1;
          }
          cover = .5 - d / this.softW;
        }

        if (cover <= 0) {
          continue;
        }
        if (cover > 1) {
          cover = 1;
        }

        accum += remaining * cover * this.drawLevel[i];
        remaining *= 1 - cover;
        if (remaining < .004) {
          break;
        }
      }

      // Whatever light got past every mark falls on the ground the picture
      // started from, which is the only thing under all of it. Already
      // phase-shifted, in step with every drawLevel above.
      accum += remaining * this.groundLevel;

      this.colors[point.index] =
        LXColor.hsb(hueDeg, satPct, clamp(accum, 0, 1) * lvl * 100);
    }
  }

  private static double clamp(double v, double low, double high) {
    return (v < low) ? low : (v > high) ? high : v;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
