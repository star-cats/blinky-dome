package com.starcats.blinkydome;

import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.LXTriggerSource;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXNormalizedParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * Turns a twitchy bass gate into a steady, on-beat clock the whole rig can share.
 *
 * Map an audio thresholder -- Chromatik's own Band Gate, set to the bass range --
 * onto {@link #input}. Every rising edge there is treated as a sighting of a
 * beat. The tracker averages the intervals between sightings into a tempo, then
 * runs its own clock at that tempo and emits {@link #beat} from the clock rather
 * than from the gate.
 *
 * That indirection is the entire point. A bass gate fires late on a soft kick,
 * early on a loud bass note, twice on a sloppy one, and not at all through a
 * breakdown. Downstream patterns driven straight off the gate inherit all of it.
 * Driven off the averaged clock they get an even pulse that rides through the
 * dropouts, because the clock only needs the gate to be right *on average* --
 * individual mistakes are outvoted by the window.
 *
 * The clock is nudged, never snapped. Each accepted beat pulls the phase a
 * fraction of the way toward where the audio says the beat is ({@link #lock}),
 * so the output converges on the music over a few beats instead of stuttering
 * every time a detection lands a few milliseconds off.
 *
 * Outputs, in order of usefulness downstream:
 *
 *   value    the beat phase, a 0-1 ramp that resets on every beat. Map this
 *            onto anything that wants to move continuously in time with the
 *            music -- it is the modulator's own value, so it is what you get
 *            when you use this as a modulation source.
 *   beat     a trigger on each downbeat, for anything that fires discretely.
 *   bpm      the tracked tempo.
 *   conf     how much to believe the tempo (see below).
 */
@LXCategory("Blinky Dome")
@LXModulator.Global("Beat Tracker")
@LXModulator.Device("Beat Tracker")
@LXComponent.Description("Averages a bass gate into a tempo and emits a steady on-beat trigger")
public class BeatTracker extends LXModulator implements LXNormalizedParameter, LXTriggerSource {

  /**
   * The input has to fall back to this fraction of the threshold before another
   * rising edge counts. Without the gap, a signal sitting right at the threshold
   * would chatter across it and register a burst of beats.
   */
  private static final double REARM_RATIO = .75;

  /**
   * Floor on the gap between two sightings, purely as debounce -- 50ms is 1200
   * BPM, far faster than any tempo we would want to fold down from. Anything
   * closer together than this is one physical hit that crossed the threshold
   * twice, not two beats.
   */
  private static final double DEBOUNCE_MS = 50;

  /**
   * How far off the running average an interval can be and still be believed.
   *
   * Tighter than it looks like it needs to be, because of how a stray hit fails:
   * one extra sighting inside a beat splits it into two intervals that fold back
   * to roughly 0.8 and 1.2 beats. At a 25% tolerance both slip through and drag
   * the average down; at 15% both are rejected. Real jitter is far smaller than
   * this -- +/-20ms on a 128 BPM beat is only 4% -- so the room is not needed.
   */
  private static final double OUTLIER_TOLERANCE = .15;

  /**
   * Consecutive off-average intervals before we conclude the song changed tempo
   * rather than the gate having misfired. One stray reading is noise; four in a
   * row means the average is the thing that's wrong.
   */
  private static final int OUTLIER_PATIENCE = 4;

  /** Intervals needed before the clock will free-run on its own. */
  private static final int MIN_SAMPLES = 2;

  /**
   * How near a beat a sighting must land, as a fraction of a beat, before it is
   * allowed to pull the clock.
   *
   * Folding fixes the tempo but not the phase: a gate catching every eighth note
   * reports the right interval while half its sightings sit on the off-beat. If
   * every sighting pulled, those would drag the clock back and forth across the
   * downbeat forever. Stray hits between beats do the same.
   */
  private static final double CORRECTION_WINDOW = .25;

  /**
   * Consecutive out-of-window sightings before we conclude the clock, not the
   * sightings, is in the wrong place -- and snap to them.
   */
  private static final int LOCK_PATIENCE = 8;

  /** Ring buffer size, and so the largest averaging window on offer. */
  private static final int MAX_WINDOW = 32;

  /**
   * Sightings retained purely so the UI can chart them. Nothing in the tracking
   * reads this -- it is a display tap, deliberately kept separate from the
   * interval history, which holds only the readings that survived filtering.
   */
  private static final int SIGHTING_HISTORY = 64;

  /**
   * Silence longer than this many beats is a dropout, not a very slow beat. The
   * tempo is probably still right on the other side, so the gap resyncs the
   * phase but is kept out of the average.
   */
  private static final double DROPOUT_BEATS = 4;

  public final CompoundParameter input =
    new CompoundParameter("Input", 0, 0, 1)
    .setDescription("Map a bass gate onto this -- its rising edges are the beats");

  public final CompoundParameter threshold =
    new CompoundParameter("Thresh", .5, 0, 1)
    .setDescription("Level the input must rise across to register a beat");

  public final BoundedParameter minBpm =
    new BoundedParameter("Min BPM", 70, 40, 200)
    .setDescription("Bottom of the tempo range; intervals fold into Min BPM up to twice Min BPM");

  public final DiscreteParameter window =
    new DiscreteParameter("Avg", 8, MIN_SAMPLES, MAX_WINDOW + 1)
    .setDescription("How many beat intervals the moving average covers -- higher is steadier but slower to follow");

  public final CompoundParameter lock =
    new CompoundParameter("Lock", .25, 0, 1)
    .setDescription("How hard each beat pulls the clock back into alignment; 0 free-runs, 1 snaps");

  /**
   * How often to emit, relative to the beat being tracked.
   *
   * Only the output rate changes -- the tracked tempo is whatever the music is
   * doing, and BPM keeps reporting that regardless of what comes out.
   */
  public enum Rate {
    HALF("Half", .5),
    SINGLE("Single", 1),
    DOUBLE("Double", 2);

    /** Emitted beats per tracked beat. */
    public final double multiplier;

    private final String label;

    private Rate(String label, double multiplier) {
      this.label = label;
      this.multiplier = multiplier;
    }

    @Override
    public String toString() {
      return this.label;
    }
  }

  public final EnumParameter<Rate> rate =
    new EnumParameter<Rate>("Rate", Rate.SINGLE)
    .setDescription("Emit on every beat, every other beat, or twice per beat");

  public final CompoundParameter shift =
    (CompoundParameter) new CompoundParameter("Shift", 0, -200, 200)
    .setUnits(LXParameter.Units.MILLISECONDS)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Slide the emitted beat later (+) or earlier (-) than the audio, in milliseconds");

  public final BoundedParameter bpm =
    new BoundedParameter("BPM", 0, 0, 400)
    .setDescription("Tracked tempo (output)");

  public final BoundedParameter confidence =
    new BoundedParameter("Conf", 0, 0, 1)
    .setDescription("How consistent recent intervals have been (output)");

  public final TriggerParameter beat =
    new TriggerParameter("Beat")
    .setDescription("Fires on every beat of the tracked tempo (output)");

  // Not "reset" -- LXModulator already claims that parameter path.
  public final TriggerParameter relearn =
    new TriggerParameter("Relearn")
    .setDescription("Forget the tempo and start listening from scratch");

  /** Most recent accepted intervals, oldest overwritten first. */
  private final double[] intervals = new double[MAX_WINDOW];
  private int intervalCount = 0;
  private int intervalHead = 0;
  private int outlierRun = 0;
  private int missedCorrections = 0;

  /** Current best estimate of one beat, in milliseconds. 0 until we have one. */
  private double periodMs = 0;

  /**
   * Position in beats since the tracker locked on, counting continuously: the
   * fractional part is where we are inside the current beat, the integer part
   * is which beat it is.
   *
   * Held as one running number rather than a wrapped 0-1 phase because half
   * time needs to know *which* beat this is, and a phase that resets every beat
   * cannot say. It also makes correction cleaner -- nudging the clock across a
   * beat boundary is just arithmetic here, with no wrap to special-case.
   */
  private double beatPosition = 0;

  /** Every sighting that cleared debounce, timestamped on the elapsed clock. */
  private final double[] sightings = new double[SIGHTING_HISTORY];
  private int sightingCount = 0;
  private int sightingHead = 0;

  /** Milliseconds of running time, and the time base the UI charts against. */
  private double elapsedMs = 0;

  private double sinceDetectMs = 0;
  private boolean sawFirstEdge = false;
  private boolean armed = true;
  private boolean firedThisFrame = false;

  public BeatTracker() {
    this("Beat Tracker");
  }

  public BeatTracker(String label) {
    super(label);
    addParameter("input", this.input);
    addParameter("threshold", this.threshold);
    addParameter("minBpm", this.minBpm);
    addParameter("window", this.window);
    addParameter("lock", this.lock);
    addParameter("rate", this.rate);
    addParameter("shift", this.shift);
    addParameter("bpm", this.bpm);
    addParameter("confidence", this.confidence);
    addParameter("beat", this.beat);
    addParameter("relearn", this.relearn);
    this.relearn.onTrigger(this::forgetTempo);
  }

  @Override
  protected double computeValue(double deltaMs) {
    this.firedThisFrame = false;
    this.elapsedMs += deltaMs;
    this.sinceDetectMs += deltaMs;

    if (pollInput()) {
      onBeatSighted();
    }
    advanceClock(deltaMs);

    // The shifted phase, not the tracking one, so the ramp and the trigger
    // always describe the same beat. Downstream sees one coherent output.
    return outputPhase();
  }

  /**
   * Where the emitted beat sits, as opposed to where the audio beat sits.
   *
   * Shift is applied here at the output rather than folded into {@link #phase},
   * which stays locked to what the gate actually heard. Keeping the two apart
   * means the tracking maths -- correction, dropout resync, the whole averaging
   * loop -- never has to know the knob exists, and moving the knob cannot
   * disturb the lock.
   *
   * Positive shift emits later: at +100ms on a 500ms beat the output wraps a
   * fifth of a beat after the audio does.
   */
  private double outputPhase() {
    if (this.periodMs <= 0) {
      return trackingPhase();
    }
    double position = outputPosition(this.beatPosition);
    return position - Math.floor(position);
  }

  /**
   * The output's own running position, in emitted beats.
   *
   * Shift and rate compose here and nowhere else: slide by the shift, then
   * rescale by the rate. Every whole number this passes is a beat to emit,
   * which is what makes half and double time fall out of the same arithmetic
   * as single instead of needing cases of their own.
   */
  private double outputPosition(double beats) {
    double shifted = beats - this.shift.getValue() / this.periodMs;
    return shifted * this.rate.getEnum().multiplier;
  }

  /** Where we are inside the tracked beat, 0-1. */
  private double trackingPhase() {
    return this.beatPosition - Math.floor(this.beatPosition);
  }

  /**
   * Rising-edge detector with hysteresis.
   *
   * Returns true at most once per excursion above the threshold: crossing up
   * consumes the arm, and only dropping back below REARM_RATIO of the threshold
   * restores it.
   */
  private boolean pollInput() {
    double level = this.input.getValue();
    double open = this.threshold.getValue();

    if (this.armed) {
      if (level >= open) {
        this.armed = false;
        // Disarm either way, so a hit inside the debounce window is swallowed
        // rather than firing late the moment the window expires.
        return this.sinceDetectMs >= DEBOUNCE_MS;
      }
    } else if (level < open * REARM_RATIO) {
      this.armed = true;
    }
    return false;
  }

  private void onBeatSighted() {
    double interval = this.sinceDetectMs;
    this.sinceDetectMs = 0;

    // Logged before any filtering, so the chart shows what the gate actually
    // did -- including the hits the tracker goes on to reject. Seeing those sit
    // off the predicted grid is how you know to move the threshold.
    this.sightings[this.sightingHead] = this.elapsedMs;
    this.sightingHead = (this.sightingHead + 1) % SIGHTING_HISTORY;
    if (this.sightingCount < SIGHTING_HISTORY) {
      ++this.sightingCount;
    }

    if (!this.sawFirstEdge) {
      // Nothing to measure against yet -- one edge is not an interval.
      this.sawFirstEdge = true;
      syncPhase();
      return;
    }

    double slowestMs = 60000 / this.minBpm.getValue();
    if (interval > slowestMs * DROPOUT_BEATS) {
      // The music went away and came back. Averaging the silence in would drag
      // the tempo to a crawl, so take the phase and drop the interval.
      syncPhase();
      return;
    }

    recordInterval(foldIntoOctave(interval, slowestMs));
    correctPhase();
  }

  /**
   * Folds an interval into the one-octave window starting at Min BPM.
   *
   * A bass gate that reliably catches every eighth note, or only every other
   * downbeat, is still telling the truth about the tempo -- just in the wrong
   * octave. Doubling or halving into a fixed range makes those readings agree
   * with each other instead of fighting the average.
   */
  private double foldIntoOctave(double intervalMs, double slowestMs) {
    double fastestMs = slowestMs / 2;
    double folded = intervalMs;
    while (folded < fastestMs) {
      folded *= 2;
    }
    while (folded > slowestMs) {
      folded /= 2;
    }
    return folded;
  }

  private void recordInterval(double interval) {
    if (this.intervalCount >= MIN_SAMPLES) {
      double deviation = Math.abs(interval - this.periodMs) / this.periodMs;
      if (deviation > OUTLIER_TOLERANCE) {
        if (++this.outlierRun < OUTLIER_PATIENCE) {
          return;
        }
        // Enough disagreement in a row that the average has gone stale. Drop the
        // history and let this interval seed the new tempo.
        clearSamples();
      } else {
        this.outlierRun = 0;
      }
    }

    this.intervals[this.intervalHead] = interval;
    this.intervalHead = (this.intervalHead + 1) % MAX_WINDOW;
    if (this.intervalCount < MAX_WINDOW) {
      ++this.intervalCount;
    }
    recomputeTempo();
  }

  private void recomputeTempo() {
    int samples = Math.min(this.intervalCount, this.window.getValuei());
    double sum = 0;
    for (int i = 1; i <= samples; ++i) {
      sum += this.intervals[Math.floorMod(this.intervalHead - i, MAX_WINDOW)];
    }
    double mean = sum / samples;
    if (mean <= 0) {
      return;
    }
    this.periodMs = mean;
    this.bpm.setValue(60000 / mean);

    if (samples < MIN_SAMPLES) {
      // A single interval always looks perfectly consistent with itself.
      this.confidence.setValue(0);
      return;
    }
    double spread = 0;
    for (int i = 1; i <= samples; ++i) {
      spread += Math.abs(this.intervals[Math.floorMod(this.intervalHead - i, MAX_WINDOW)] - mean);
    }
    // Scaled so that "average miss equals the outlier tolerance" reads as zero.
    double relative = (spread / samples) / mean;
    this.confidence.setValue(Math.max(0, Math.min(1, 1 - relative / OUTLIER_TOLERANCE)));
  }

  private boolean hasTempo() {
    return this.intervalCount >= MIN_SAMPLES && this.periodMs > 0;
  }

  /**
   * Hard alignment, for when there is no tempo to be gentle about.
   *
   * Only emits when there is no tempo yet. In that state sightings are the only
   * source of beats, and a shift measured in milliseconds has no beat to be
   * relative to, so they go out raw. Once the clock is running it owns the
   * output: firing here too would put an unshifted beat in the middle of a
   * shifted stream every time a resync happened.
   */
  private void syncPhase() {
    // Snap to the nearest whole beat rather than zeroing the count: half time
    // alternates on that count, so throwing it away would let a resync flip
    // which beats get emitted.
    this.beatPosition = Math.round(this.beatPosition);
    if (!hasTempo()) {
      fire();
      return;
    }
    // The snap puts the audio beat at this instant; the emitted beat is that
    // plus the shift. A positive shift is still ahead of us, and the clock will
    // arrive there on its own in exactly that many milliseconds. A negative one
    // already went by. Zero means it is now -- and the clock, having just been
    // set to the top of a beat, would take a whole period to work that out,
    // swallowing a beat on every resync.
    if (outputPhase() <= 0) {
      fire();
    }
  }

  /**
   * Pulls the clock a fraction of the way toward the beat we just saw.
   *
   * The error is signed distance to the *nearest* beat boundary, so a sighting
   * just before the clock's own beat pulls it forward rather than dragging it
   * almost all the way around.
   */
  private void correctPhase() {
    if (!hasTempo()) {
      syncPhase();
      return;
    }
    double error = trackingPhase();
    if (error > .5) {
      error -= 1;
    }
    if (Math.abs(error) > CORRECTION_WINDOW) {
      // Nowhere near our beat, so most likely an off-beat subdivision or a
      // stray hit -- ignore it rather than let it drag the downbeat around.
      // Unless it keeps happening, in which case we are the ones adrift.
      if (++this.missedCorrections >= LOCK_PATIENCE) {
        this.missedCorrections = 0;
        syncPhase();
      }
      return;
    }
    this.missedCorrections = 0;
    // No wrapping: the position is continuous, so a nudge across a beat
    // boundary just carries into the next beat and the count stays honest.
    this.beatPosition -= this.lock.getValue() * error;
  }

  private void advanceClock(double deltaMs) {
    if (!hasTempo()) {
      // Until the tempo settles, sightings themselves are the only trigger.
      return;
    }
    double advance = deltaMs / this.periodMs;

    // Both readings use the knobs as they are right now, so the only difference
    // between them is this frame's advance. That is what keeps turning Shift or
    // switching Rate from manufacturing a beat: the jump moves both ends
    // equally and cancels, where comparing against last frame's stored output
    // would see it as a crossing.
    double before = outputPosition(this.beatPosition);
    this.beatPosition += advance;
    double after = outputPosition(this.beatPosition);

    if (Math.floor(after) > Math.floor(before)) {
      fire();
    }
  }

  /** At most one beat per frame, however many ways we got here. */
  private void fire() {
    if (!this.firedThisFrame) {
      this.firedThisFrame = true;
      this.beat.trigger();
    }
  }

  private void clearSamples() {
    this.intervalCount = 0;
    this.intervalHead = 0;
    this.outlierRun = 0;
  }

  private void forgetTempo() {
    clearSamples();
    // Clears the chart too -- leaving old sightings up next to a blank grid
    // would read as though the tracker still knew something.
    this.sightingCount = 0;
    this.sightingHead = 0;
    this.missedCorrections = 0;
    this.periodMs = 0;
    this.beatPosition = 0;
    this.sinceDetectMs = 0;
    this.sawFirstEdge = false;
    this.armed = true;
    this.bpm.setValue(0);
    this.confidence.setValue(0);
  }

  // ---------------------------------------------------------------------------
  // Visualizer tap. Read from the UI thread while the engine thread writes, with
  // no synchronization: the worst case is a chart frame that draws a sighting a
  // frame late, which is not worth a lock on the audio path.
  // ---------------------------------------------------------------------------

  /** Running time in milliseconds, the time base sightings are stamped against. */
  public double getElapsedMs() {
    return this.elapsedMs;
  }

  /** Current beat length in milliseconds, or 0 before a tempo is established. */
  public double getPeriodMs() {
    return this.periodMs;
  }

  /**
   * Milliseconds between emitted beats, which is the tracked period divided by
   * the rate. Equal to {@link #getPeriodMs()} at single time.
   */
  public double getOutputPeriodMs() {
    return this.periodMs / this.rate.getEnum().multiplier;
  }

  /**
   * Copies recent sighting timestamps into {@code out}, newest first, and
   * returns how many were written. Newest-first lets a caller drawing a fixed
   * time window stop as soon as it walks off the left edge.
   */
  public int getRecentSightings(double[] out) {
    int count = Math.min(out.length, this.sightingCount);
    for (int i = 0; i < count; ++i) {
      out[i] = this.sightings[Math.floorMod(this.sightingHead - 1 - i, SIGHTING_HISTORY)];
    }
    return count;
  }

  @Override
  public BooleanParameter getTriggerSource() {
    return this.beat;
  }

  @Override
  public double getNormalized() {
    return getValue();
  }

  /** Output only -- the phase is computed, not something you can drive. */
  @Override
  public LXNormalizedParameter setNormalized(double value) {
    return this;
  }

  /** The phase is a ramp that wraps, so modulation should treat it that way. */
  @Override
  public boolean isWrappable() {
    return true;
  }
}
