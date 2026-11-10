package com.starcats.blinkydome;

/**
 * The beat tracking itself, with no Chromatik in it.
 *
 * Feeds on a 0-1 level -- a bass gate -- and turns rising edges on it into a
 * steady clock. Every edge is a *sighting* of a beat; the intervals between
 * sightings are averaged into a tempo; the clock then runs at that tempo and
 * emits beats of its own rather than passing the gate through. A bass gate fires
 * late on a soft kick, twice on a sloppy one, and not at all through a
 * breakdown, and anything driven straight off it inherits all of that. Driven
 * off the average, the gate only has to be right *on average*.
 *
 * The clock is nudged, never snapped: each accepted sighting pulls the phase a
 * fraction of the way toward where the audio says the beat is, so the output
 * converges over a few beats instead of stuttering on every detection that lands
 * a millisecond off.
 *
 * Deliberately not an LXModulator and deliberately holding no parameters. The
 * owner passes tuning in as plain numbers before each {@link #loop}, which keeps
 * this class testable without a UI, without an engine, and without constructing
 * anything at all.
 */
public class BeatClock {

  /**
   * The input has to fall back to this fraction of the threshold before another
   * rising edge counts. Without the gap, a signal sitting right at the threshold
   * would chatter across it and register a burst of beats.
   */
  private static final double REARM_RATIO = .75;

  /**
   * Floor on the gap between two sightings, purely as debounce -- 50ms is 1200
   * BPM, far faster than any tempo worth tracking. Anything closer together than
   * this is one physical hit that crossed the threshold twice, not two beats.
   */
  private static final double DEBOUNCE_MS = 50;

  /**
   * How far off the running average an interval can be and still be believed.
   *
   * Tighter than it looks like it needs to be, because of how a stray hit fails:
   * one extra sighting inside a beat splits it into two intervals of roughly 0.4
   * and 0.6 beats. At a 25% tolerance the pair can drag the average down; at 15%
   * both are rejected outright. Real jitter is far smaller -- +/-20ms on a 128
   * BPM beat is only 4% -- so the room is not needed.
   */
  private static final double OUTLIER_TOLERANCE = .15;

  /**
   * Consecutive off-average intervals before we conclude the song changed tempo
   * rather than the gate having misfired. One stray reading is noise; four in a
   * row means the average is the thing that's wrong.
   */
  private static final int OUTLIER_PATIENCE = 4;

  /** Intervals needed before the clock will free-run on its own. */
  public static final int MIN_SAMPLES = 2;

  /**
   * How near a beat a sighting must land, as a fraction of a beat, before it is
   * allowed to pull the clock.
   *
   * A stray hit landing mid-beat is still a rising edge, and if every sighting
   * pulled, those would drag the clock back and forth across the beat forever.
   * Ignoring the ones that land nowhere near where we expect a beat costs
   * nothing when the gate is clean and saves the lock when it is not.
   */
  private static final double CORRECTION_WINDOW = .25;

  /**
   * Consecutive out-of-window sightings before we conclude the clock, not the
   * sightings, is in the wrong place -- and snap to them.
   */
  private static final int LOCK_PATIENCE = 8;

  /** Ring buffer size, and so the largest averaging window on offer. */
  public static final int MAX_WINDOW = 32;

  /**
   * Sightings retained purely so the UI can chart them. Nothing in the tracking
   * reads this -- it is a display tap, kept separate from the interval history,
   * which holds only the readings that survived filtering.
   */
  private static final int SIGHTING_HISTORY = 64;

  /**
   * Silence longer than this many beats is a dropout, not a very slow beat. The
   * tempo is probably still right on the other side, so the gap resyncs the
   * phase but is kept out of the average.
   */
  private static final double DROPOUT_BEATS = 4;

  // --- Tuning, set by the owner before each loop -----------------------------

  /** Level the input must rise across to register a sighting. */
  public double threshold = .5;

  /**
   * Hard floor on the reported tempo.
   *
   * Applied to the average, never to the beats: every sighting is a real thing
   * that happened and gets tracked, and it is only the tempo that comes out the
   * other side that is prevented from reading slower than this. Discarding the
   * beats instead would mean a slow passage silently stopped being tracked at
   * all, which is worse than a tempo that reads high.
   */
  public double minBpm = 95;

  /**
   * Soft musical-range floor. Whenever the tracked tempo lands below this
   * we assume the bass gate is only catching every other beat and DOUBLE the
   * tempo. Set below the real minimum BPM you care about — e.g. 65 to allow
   * genuine 65 BPM songs through, or 90 to force everything toward the fast
   * end when the gate can't be trusted to catch every kick.
   *
   * Zero disables the check (raw output). Ignored if it isn't strictly
   * greater than {@link #minBpm}.
   */
  public double preferredMinBpm = 90;

  /**
   * Ceiling for the doubling check — no matter how slow the raw tempo, we
   * won't fold it above this. Prevents runaway doubling on very slow input.
   */
  public double preferredMaxBpm = 200;

  /** How many intervals the moving average covers. */
  public int averagingWindow = 8;

  /** How hard a sighting pulls the clock back into alignment, 0-1. */
  public double lock = .25;

  /** Emitted beats slide this many milliseconds later (+) or earlier (-). */
  public double shiftMs = 0;

  // --- State -----------------------------------------------------------------

  private final double[] intervals = new double[MAX_WINDOW];
  private int intervalCount = 0;
  private int intervalHead = 0;
  private int outlierRun = 0;
  private int missedCorrections = 0;

  /** Beat length the clock runs on, after the Min BPM floor. 0 until we have one. */
  private double periodMs = 0;

  /**
   * The same average before the floor is applied.
   *
   * Kept separately because the outlier test has to compare like with like. Test
   * an incoming interval against the floored period and a track slower than the
   * floor has every single interval read as an outlier -- which would clear the
   * history, reseed, floor again, and do it forever.
   */
  private double rawPeriodMs = 0;
  private double bpm = 0;
  private double confidence = 0;

  /**
   * Position in beats since the clock locked on, counting continuously: the
   * fractional part is where we are inside the current beat, the integer part is
   * which beat it is.
   *
   * One running number rather than a wrapped 0-1 phase so nothing has to
   * special-case a wrap: correction nudging across a beat boundary is plain
   * arithmetic, and a shift longer than a beat needs no modular fixup.
   */
  private double beatPosition = 0;

  private final double[] sightings = new double[SIGHTING_HISTORY];
  private int sightingCount = 0;
  private int sightingHead = 0;

  private double elapsedMs = 0;
  private double sinceOutputBeatMs = 0;
  private double sinceDetectMs = 0;
  private double sinceSightingMs = 0;

  private boolean sawFirstEdge = false;
  private boolean armed = true;
  private boolean firedThisFrame = false;
  private boolean acceptedThisFrame = false;

  /**
   * Monotonic count of emitted beats. Consumers watch this for changes rather
   * than reading a per-frame flag, so they stay correct no matter what order
   * things run in.
   */
  private long beatCount = 0;

  /** Advance one frame against the current input level. */
  public void loop(double deltaMs, double level) {
    this.firedThisFrame = false;
    this.acceptedThisFrame = false;
    this.elapsedMs += deltaMs;
    this.sinceDetectMs += deltaMs;
    this.sinceSightingMs += deltaMs;
    this.sinceOutputBeatMs += deltaMs;

    if (pollInput(level)) {
      onBeatSighted();
    }
    advanceClock(deltaMs);
  }

  /**
   * Rising-edge detector with hysteresis.
   *
   * Returns true at most once per excursion above the threshold: crossing up
   * consumes the arm, and only dropping back below REARM_RATIO of the threshold
   * restores it.
   */
  private boolean pollInput(double level) {
    if (this.armed) {
      if (level >= this.threshold) {
        this.armed = false;
        // Disarm either way, so a hit inside the debounce window is swallowed
        // rather than firing late the moment the window expires.
        return this.sinceDetectMs >= DEBOUNCE_MS;
      }
    } else if (level < this.threshold * REARM_RATIO) {
      this.armed = true;
    }
    return false;
  }

  private void onBeatSighted() {
    double interval = this.sinceDetectMs;
    this.sinceDetectMs = 0;
    this.sinceSightingMs = 0;

    // Logged before any filtering, so the chart shows what the gate actually
    // did -- including the hits that get rejected below. Seeing one sit off the
    // predicted grid is how you know to move the threshold.
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

    double slowestMs = 60000 / this.minBpm;
    if (interval > slowestMs * DROPOUT_BEATS) {
      // The music went away and came back. Averaging the silence in would drag
      // the tempo to a crawl, so take the phase and drop the interval.
      syncPhase();
      return;
    }
    recordInterval(interval);
    correctPhase();
  }

  private void recordInterval(double interval) {
    if (this.intervalCount >= MIN_SAMPLES) {
      double deviation = Math.abs(interval - this.rawPeriodMs) / this.rawPeriodMs;
      if (deviation > OUTLIER_TOLERANCE) {
        // Missed-beat check: an interval that's a near-integer multiple of
        // the tracked period isn't a tempo change, it's the gate skipping
        // beats. Treat it as if we caught them: reset the outlier counter,
        // do NOT record the fat interval, and let the clock keep running.
        // Without this, a stretch of saturating bass on a locked track drops
        // us to half BPM as soon as OUTLIER_PATIENCE elapses.
        for (int mult = 2; mult <= 4; ++mult) {
          double folded = interval / mult;
          double foldedDev = Math.abs(folded - this.rawPeriodMs) / this.rawPeriodMs;
          if (foldedDev < OUTLIER_TOLERANCE) {
            this.outlierRun = 0;
            return;
          }
        }
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
    // Only a reading that survived every filter counts as a beat the mood
    // machine is allowed to believe.
    this.acceptedThisFrame = true;
    recomputeTempo();
  }

  private void recomputeTempo() {
    int samples = Math.min(this.intervalCount, this.averagingWindow);
    double sum = 0;
    for (int i = 1; i <= samples; ++i) {
      sum += this.intervals[Math.floorMod(this.intervalHead - i, MAX_WINDOW)];
    }
    double mean = sum / samples;
    if (mean <= 0) {
      return;
    }
    this.rawPeriodMs = mean;

    // The floor lands here and nowhere else: every interval above was recorded
    // whatever it implied, and only the tempo handed out is held above Min BPM.
    double slowestMs = 60000 / this.minBpm;
    this.periodMs = Math.min(mean, slowestMs);
    this.bpm = 60000 / this.periodMs;

    if (samples < MIN_SAMPLES) {
      // A single interval always looks perfectly consistent with itself.
      this.confidence = 0;
      return;
    }
    double spread = 0;
    for (int i = 1; i <= samples; ++i) {
      spread += Math.abs(this.intervals[Math.floorMod(this.intervalHead - i, MAX_WINDOW)] - mean);
    }
    // Scaled so "average miss equals the outlier tolerance" reads as zero.
    double relative = (spread / samples) / mean;
    this.confidence = Math.max(0, Math.min(1, 1 - relative / OUTLIER_TOLERANCE));

    // Octave-up: if the tracked BPM landed under the preferred musical
    // range, assume the gate was under-counting and double until we clear
    // that floor. This runs after normal averaging, so a genuine slow song
    // (below preferredMinBpm) still gets the option — the user opts into
    // slow tracking by setting preferredMinBpm at or below their real min.
    applyOctavePreference();
  }

  /**
   * Double the tempo until it lands in [preferredMinBpm, preferredMaxBpm],
   * folding every stored interval alongside so future outlier checks are
   * against the new period. No-op if the preference is disabled or already
   * satisfied.
   */
  private void applyOctavePreference() {
    if (this.preferredMinBpm <= 0 || this.preferredMinBpm <= this.minBpm) return;
    if (this.bpm <= 0 || this.periodMs <= 0) return;
    // Guard: don't runaway on numerical junk.
    int guard = 0;
    while (this.bpm < this.preferredMinBpm
           && this.bpm * 2 <= this.preferredMaxBpm
           && guard++ < 8) {
      this.periodMs    /= 2;
      this.rawPeriodMs /= 2;
      this.bpm         *= 2;
      // The clock's position is measured in beats — double it so the
      // wall-clock beat time we're currently in stays where the audio
      // put it, just now split into two beats at the new tempo.
      this.beatPosition *= 2;
      for (int i = 0; i < MAX_WINDOW; ++i) {
        this.intervals[i] /= 2;
      }
    }
  }

  public boolean hasTempo() {
    return this.intervalCount >= MIN_SAMPLES && this.periodMs > 0;
  }

  /**
   * Hard alignment, for when there is no tempo to be gentle about.
   *
   * Only emits when there is no tempo yet. In that state sightings are the only
   * source of beats, and a shift in milliseconds has no beat to be relative to,
   * so they go out raw. Once the clock is running it owns the output.
   */
  private void syncPhase() {
    // Nearest whole beat rather than zero, so the count stays continuous with
    // where the clock already was instead of jumping backwards.
    this.beatPosition = Math.round(this.beatPosition);
    if (!hasTempo()) {
      fire();
      return;
    }
    // The snap puts the audio beat at this instant; the emitted beat is that
    // plus the shift. A positive shift is still ahead of us and the clock will
    // arrive there on its own. A negative one already went by. Zero means it is
    // now -- and the clock, just set to the top of a beat, would take a whole
    // period to work that out, swallowing a beat on every resync.
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
      // Nowhere near our beat -- most likely a stray hit, so ignore it rather
      // than let it drag the beat around. Unless it keeps happening, in which
      // case we are the ones adrift.
      if (++this.missedCorrections >= LOCK_PATIENCE) {
        this.missedCorrections = 0;
        syncPhase();
      }
      return;
    }
    this.missedCorrections = 0;
    // No wrapping: the position is continuous, so a nudge across a beat boundary
    // carries into the next beat and the count stays honest.
    this.beatPosition -= this.lock * error;
  }

  private void advanceClock(double deltaMs) {
    if (!hasTempo()) {
      // Until the tempo settles, sightings themselves are the only trigger.
      return;
    }
    double advance = deltaMs / this.periodMs;

    // Both readings use the shift as it is right now, so the only difference
    // between them is this frame's advance. That is what keeps turning the shift
    // from manufacturing a beat: the jump moves both ends equally and cancels.
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
      this.sinceOutputBeatMs = 0;
      ++this.beatCount;
    }
  }

  /** The output's running position in emitted beats; the shift lives here only. */
  private double outputPosition(double beats) {
    return beats - this.shiftMs / this.periodMs;
  }

  private double trackingPhase() {
    return this.beatPosition - Math.floor(this.beatPosition);
  }

  private double outputPhase() {
    if (this.periodMs <= 0) {
      return trackingPhase();
    }
    double position = outputPosition(this.beatPosition);
    return position - Math.floor(position);
  }

  private void clearSamples() {
    this.intervalCount = 0;
    this.intervalHead = 0;
    this.outlierRun = 0;
  }

  /** Throw away everything learned and start listening from scratch. */
  public void forget() {
    clearSamples();
    this.sightingCount = 0;
    this.sightingHead = 0;
    this.missedCorrections = 0;
    this.periodMs = 0;
    this.rawPeriodMs = 0;
    this.bpm = 0;
    this.confidence = 0;
    this.beatPosition = 0;
    this.sinceDetectMs = 0;
    this.sinceSightingMs = 0;
    this.sawFirstEdge = false;
    this.armed = true;
  }

  // --- Readouts --------------------------------------------------------------

  public double getBpm() {
    return this.bpm;
  }

  public double getConfidence() {
    return this.confidence;
  }

  /** Beat length in milliseconds, or 0 before a tempo is established. */
  public double getPeriodMs() {
    return this.periodMs;
  }

  /** Milliseconds since the last emitted beat -- the t in the output envelope. */
  public double getSinceBeatMs() {
    return this.sinceOutputBeatMs;
  }

  /** Milliseconds since the last sighting of any kind, accepted or not. */
  public double getSinceSightingMs() {
    return this.sinceSightingMs;
  }

  /** Monotonic count of emitted beats; watch it for changes rather than polling a flag. */
  public long getBeatCount() {
    return this.beatCount;
  }

  /** True on the frame a sighting survived every filter and updated the tempo. */
  public boolean acceptedSightingThisFrame() {
    return this.acceptedThisFrame;
  }

  /** Position within the current emitted beat, 0-1, resetting on each beat. */
  public double getOutputPhase() {
    return outputPhase();
  }

  /** Position within the beat as the audio has it, ignoring the shift. */
  public double getTrackingPhase() {
    return trackingPhase();
  }

  /** Running time, the time base sightings are stamped against. */
  public double getElapsedMs() {
    return this.elapsedMs;
  }

  /**
   * Copies recent sighting timestamps into {@code out}, newest first, returning
   * how many were written. Newest-first lets a caller drawing a fixed time window
   * stop as soon as it walks off the left edge.
   */
  public int getRecentSightings(double[] out) {
    int count = Math.min(out.length, this.sightingCount);
    for (int i = 0; i < count; ++i) {
      out[i] = this.sightings[Math.floorMod(this.sightingHead - 1 - i, SIGHTING_HISTORY)];
    }
    return count;
  }
}
