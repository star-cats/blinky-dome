package com.starcats.blinkydome;

import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.LXTriggerSource;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXNormalizedParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * Listens to the room and works out what the music is doing, so nothing else has
 * to.
 *
 * One of these per show. It takes three band levels, tracks the bass for tempo,
 * smooths the bands into a single intensity figure, and runs a three-state mood
 * machine over the result. Everything it learns is published through
 * {@link MoodState} for the supporting trackers -- DriveTracker, AmbientTracker,
 * DropTracker -- to read, so the analysis happens once instead of once per
 * effect.
 *
 * The beat tracking lives in {@link BeatClock}, which has no Chromatik in it at
 * all. This class is the part that knows about parameters and moods.
 *
 * Its own value is the smoothed intensity, so the controller can be mapped
 * directly onto anything that should follow the energy of the room.
 */
@LXCategory("Blinky Dome")
@LXModulator.Global("Primary Controller")
@LXModulator.Device("Primary Controller")
@LXComponent.Description("Tracks tempo, intensity and mood from three band levels")
public class PrimaryController extends LXModulator implements LXNormalizedParameter, LXTriggerSource {

  /** Confidence a sighting needs before the mood machine will count it. */
  private static final double HIGH_CONFIDENCE = .5;

  /** Consecutive high-confidence sightings that mean the track is driving. */
  private static final int DRIVING_BEATS = 2;

  /** AMBIENT has to hold this long before a rise counts as a build. */
  private static final double AMBIENT_SETTLE_MS = 4000;

  /** A build with no new peak for this long has stalled; fall back to ambient. */
  private static final double BUILD_STALL_MS = 10000;

  /** Seconds of intensity history kept, which is what the UI graph spans. */
  public static final double HISTORY_SECONDS = 15;

  /** One sample per frame at 60fps, with room to spare for slower frames. */
  private static final int HISTORY_SAMPLES = 1024;

  // --- Inputs ----------------------------------------------------------------

  public final CompoundParameter low =
    new CompoundParameter("Low", 0, 0, 1)
    .setDescription("Bass level -- map a low-band meter here; this alone drives the beat clock");

  public final CompoundParameter mid =
    new CompoundParameter("Mid", 0, 0, 1)
    .setDescription("Mid level -- contributes to intensity only");

  public final CompoundParameter high =
    new CompoundParameter("High", 0, 0, 1)
    .setDescription("High level -- contributes to intensity only");

  // --- Beat tracker config ---------------------------------------------------

  public final CompoundParameter threshold =
    new CompoundParameter("Thresh", .37, 0, 1)
    .setDescription("Level the Low input must rise across to register a beat");

  public final CompoundParameter lock =
    new CompoundParameter("Lock", .37, 0, 1)
    .setDescription("How hard each beat pulls the clock back into alignment; 0 free-runs, 1 snaps");

  public final CompoundParameter shift =
    (CompoundParameter) new CompoundParameter("Shift", -64, -200, 200)
    .setUnits(LXParameter.Units.MILLISECONDS)
    .setPolarity(LXParameter.Polarity.BIPOLAR)
    .setDescription("Slide the emitted beat later (+) or earlier (-) than the audio");

  // --- Other config ----------------------------------------------------------

  public final DiscreteParameter window =
    new DiscreteParameter("Avg", 12, BeatClock.MIN_SAMPLES, BeatClock.MAX_WINDOW + 1)
    .setDescription("How many beat intervals the moving average covers");

  public final BoundedParameter minBpm =
    new BoundedParameter("Min BPM", 95, 40, 200)
    .setDescription("Hard tempo floor -- intervals implying anything slower are thrown away, never folded");

  public final DiscreteParameter beatsUntilAmbient =
    new DiscreteParameter("Amb Beats", 6, 1, 33)
    .setDescription("Beats of silence before DRIVING gives way to AMBIENT");

  public final CompoundParameter charge =
    (CompoundParameter) new CompoundParameter("Charge", .15, .01, 3)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("Intensity smoothing time constant while rising");

  public final CompoundParameter discharge =
    (CompoundParameter) new CompoundParameter("Release", 1.5, .01, 10)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("Intensity smoothing time constant while falling");

  public final CompoundParameter buildRise =
    new CompoundParameter("Rise", .10, .01, 1)
    .setDescription("How much intensity must climb over the Rise Window to read as a build");

  public final CompoundParameter buildWindow =
    (CompoundParameter) new CompoundParameter("Window", 3, .5, 10)
    .setUnits(LXParameter.Units.SECONDS)
    .setDescription("How far back to compare intensity when detecting a build");

  public final CompoundParameter lowWeight =
    new CompoundParameter("Lo W", .5, 0, 1)
    .setDescription("Weight of the Low band in the intensity mix");

  public final CompoundParameter midWeight =
    new CompoundParameter("Mid W", .3, 0, 1)
    .setDescription("Weight of the Mid band in the intensity mix");

  public final CompoundParameter highWeight =
    new CompoundParameter("Hi W", .2, 0, 1)
    .setDescription("Weight of the High band in the intensity mix");

  // --- Outputs ---------------------------------------------------------------

  public final BoundedParameter bpm =
    new BoundedParameter("BPM", 0, 0, 400)
    .setDescription("Tracked tempo (output)");

  public final BoundedParameter confidence =
    new BoundedParameter("Conf", 0, 0, 1)
    .setDescription("How consistent recent beat intervals have been (output)");

  public final BoundedParameter intensity =
    new BoundedParameter("Intensity", 0, 0, 1)
    .setDescription("Smoothed weighted level across the three bands (output)");

  public final TriggerParameter beat =
    new TriggerParameter("Beat")
    .setDescription("Fires on every beat of the tracked tempo (output)");

  public final TriggerParameter relearn =
    new TriggerParameter("Relearn")
    .setDescription("Forget the tempo and start listening from scratch");

  // --- State -----------------------------------------------------------------

  private final BeatClock clock = new BeatClock();

  private Mood mood = Mood.AMBIENT;
  private double msInMood = 0;
  private int highConfidenceRun = 0;

  /** Highest smoothed intensity seen in the current build, and time since it. */
  private double buildPeak = 0;
  private double msSincePeak = 0;

  private double smoothed = 0;
  private long lastBeatCount = 0;

  /**
   * Bumped every time BUILDING gives way to DRIVING. DropTracker watches this
   * rather than trying to observe the transition itself.
   */
  private long dropCount = 0;

  /** Ring of smoothed intensity samples with timestamps, for the graph and rise test. */
  private final double[] historyValue = new double[HISTORY_SAMPLES];
  private final double[] historyTime = new double[HISTORY_SAMPLES];
  private final Mood[] historyMood = new Mood[HISTORY_SAMPLES];
  private int historyCount = 0;
  private int historyHead = 0;

  public PrimaryController() {
    this("Primary Controller");
  }

  public PrimaryController(String label) {
    super(label);
    addParameter("low", this.low);
    addParameter("mid", this.mid);
    addParameter("high", this.high);
    addParameter("threshold", this.threshold);
    addParameter("lock", this.lock);
    addParameter("shift", this.shift);
    addParameter("window", this.window);
    addParameter("minBpm", this.minBpm);
    addParameter("beatsUntilAmbient", this.beatsUntilAmbient);
    addParameter("charge", this.charge);
    addParameter("discharge", this.discharge);
    addParameter("buildRise", this.buildRise);
    addParameter("buildWindow", this.buildWindow);
    addParameter("lowWeight", this.lowWeight);
    addParameter("midWeight", this.midWeight);
    addParameter("highWeight", this.highWeight);
    addParameter("bpm", this.bpm);
    addParameter("confidence", this.confidence);
    addParameter("intensity", this.intensity);
    addParameter("beat", this.beat);
    addParameter("relearn", this.relearn);
    this.relearn.onTrigger(this::forget);
    MoodState.register(this);
  }

  @Override
  public void dispose() {
    // Before super, which tears the component down -- a child looking this up
    // mid-teardown should find nothing rather than a half-disposed object.
    MoodState.unregister(this);
    super.dispose();
  }

  @Override
  protected double computeValue(double deltaMs) {
    this.clock.threshold = this.threshold.getValue();
    this.clock.minBpm = this.minBpm.getValue();
    this.clock.averagingWindow = this.window.getValuei();
    this.clock.lock = this.lock.getValue();
    this.clock.shiftMs = this.shift.getValue();
    this.clock.loop(deltaMs, this.low.getValue());

    if (this.clock.getBeatCount() != this.lastBeatCount) {
      this.lastBeatCount = this.clock.getBeatCount();
      this.beat.trigger();
    }
    this.bpm.setValue(this.clock.getBpm());
    this.confidence.setValue(this.clock.getConfidence());

    updateIntensity(deltaMs);
    updateMood(deltaMs);
    recordHistory(deltaMs);

    return this.smoothed;
  }

  /**
   * Weighted band mix, then a one-pole follower with separate time constants
   * going up and coming down.
   *
   * Two constants because the two directions are doing different jobs: charge
   * has to be quick enough that a riser reads as it happens, while release has to
   * be slow enough that the gap between kicks does not look like the energy
   * dropping. One shared constant cannot do both.
   */
  private void updateIntensity(double deltaMs) {
    double wLow = this.lowWeight.getValue();
    double wMid = this.midWeight.getValue();
    double wHigh = this.highWeight.getValue();
    double total = wLow + wMid + wHigh;

    double raw = (total > 0)
      ? (this.low.getValue() * wLow + this.mid.getValue() * wMid + this.high.getValue() * wHigh) / total
      : 0;

    double tau = (raw > this.smoothed ? this.charge.getValue() : this.discharge.getValue()) * 1000;
    // Exponential approach, framed so the step is correct for whatever deltaMs
    // actually was rather than assuming a fixed frame rate.
    double alpha = (tau <= 0) ? 1 : 1 - Math.exp(-deltaMs / tau);
    this.smoothed += (raw - this.smoothed) * alpha;
    this.intensity.setValue(this.smoothed);
  }

  private void updateMood(double deltaMs) {
    this.msInMood += deltaMs;

    // A sighting only counts once it has survived every filter in the clock and
    // the tempo it agrees with is itself consistent.
    if (this.clock.acceptedSightingThisFrame()) {
      if (this.clock.getConfidence() >= HIGH_CONFIDENCE) {
        ++this.highConfidenceRun;
      } else {
        this.highConfidenceRun = 0;
      }
    }

    // DRIVING wins from anywhere: bass landing where it should outranks whatever
    // the intensity curve happens to be doing.
    if (this.highConfidenceRun >= DRIVING_BEATS && this.mood != Mood.DRIVING) {
      if (this.mood == Mood.BUILDING) {
        ++this.dropCount;
      }
      setMood(Mood.DRIVING);
      return;
    }

    switch (this.mood) {
      case DRIVING: {
        double period = this.clock.getPeriodMs();
        if (period > 0
            && this.clock.getSinceSightingMs() > period * this.beatsUntilAmbient.getValuei()) {
          setMood(Mood.AMBIENT);
        }
        break;
      }
      case AMBIENT: {
        // The silence test is not redundant with the DRIVING rule, however much
        // it looks like it. DRIVING needs two high-confidence beats, which takes
        // a second or so to establish -- and in that window bass is playing but
        // has not yet earned the transition, so a build would slip in and hand
        // straight over, reporting a drop for nothing more than the music
        // starting. Requiring real silence first is what makes a drop mean
        // something.
        boolean silent = this.clock.getSinceSightingMs() >= AMBIENT_SETTLE_MS;
        if (silent && this.msInMood >= AMBIENT_SETTLE_MS && isRising()) {
          setMood(Mood.BUILDING);
          this.buildPeak = this.smoothed;
          this.msSincePeak = 0;
        }
        break;
      }
      case BUILDING: {
        if (this.smoothed > this.buildPeak) {
          this.buildPeak = this.smoothed;
          this.msSincePeak = 0;
        } else {
          this.msSincePeak += deltaMs;
          if (this.msSincePeak >= BUILD_STALL_MS) {
            // No new high in ten seconds: the build went nowhere.
            setMood(Mood.AMBIENT);
          }
        }
        break;
      }
    }
  }

  private void setMood(Mood next) {
    if (next != this.mood) {
      this.mood = next;
      this.msInMood = 0;
      this.highConfidenceRun = 0;
    }
  }

  /**
   * True when intensity is meaningfully higher than it was a Rise Window ago.
   *
   * Compares against a sample from the history ring rather than tracking a
   * derivative, because a derivative of a signal this noisy needs its own
   * smoothing and then has its own lag to argue about. A straight then-and-now
   * comparison is what the graph shows, so what triggers a build is exactly what
   * you can see happening.
   */
  private boolean isRising() {
    double windowMs = this.buildWindow.getValue() * 1000;
    double now = this.clock.getElapsedMs();
    Double oldest = intensityAt(now - windowMs);
    Double middle = intensityAt(now - windowMs / 2);
    if (oldest == null || middle == null) {
      return false;
    }

    double rise = this.buildRise.getValue();
    if ((this.smoothed - oldest) < rise) {
      return false;
    }
    // "Slow and steady", tested rather than assumed. A track simply starting
    // steps the intensity up in one frame, which clears any total-rise bar you
    // care to set and would read as a build that instantly "drops" the moment
    // the bass lands. Requiring both halves of the window to have climbed tells
    // a ramp from a step, because a step leaves one half perfectly flat.
    double floor = rise / 4;
    return (middle - oldest) >= floor && (this.smoothed - middle) >= floor;
  }

  /** Nearest recorded intensity at or before the given time, or null if too old. */
  private Double intensityAt(double timeMs) {
    if (this.historyCount == 0 || timeMs < 0) {
      return null;
    }
    for (int i = 1; i <= this.historyCount; ++i) {
      int idx = Math.floorMod(this.historyHead - i, HISTORY_SAMPLES);
      if (this.historyTime[idx] <= timeMs) {
        return this.historyValue[idx];
      }
    }
    // Every sample we have is newer than the requested time, so the history does
    // not reach back far enough yet.
    return null;
  }

  private void recordHistory(double deltaMs) {
    this.historyValue[this.historyHead] = this.smoothed;
    this.historyTime[this.historyHead] = this.clock.getElapsedMs();
    this.historyMood[this.historyHead] = this.mood;
    this.historyHead = (this.historyHead + 1) % HISTORY_SAMPLES;
    if (this.historyCount < HISTORY_SAMPLES) {
      ++this.historyCount;
    }
  }

  private void forget() {
    this.clock.forget();
    this.mood = Mood.AMBIENT;
    this.msInMood = 0;
    this.highConfidenceRun = 0;
    this.buildPeak = 0;
    this.msSincePeak = 0;
    this.historyCount = 0;
    this.historyHead = 0;
    this.bpm.setValue(0);
    this.confidence.setValue(0);
  }

  // --- Published state -------------------------------------------------------

  public Mood getMood() {
    return this.mood;
  }

  public double getIntensity() {
    return this.smoothed;
  }

  public double getBpm() {
    return this.clock.getBpm();
  }

  public double getPeriodMs() {
    return this.clock.getPeriodMs();
  }

  /** Milliseconds since the last emitted beat -- the t in every child's envelope. */
  public double getSinceBeatMs() {
    return this.clock.getSinceBeatMs();
  }

  /**
   * Monotonic count of emitted beats. Children fire when this changes rather
   * than reading a per-frame flag, which keeps them right whatever order the
   * engine runs modulators in.
   */
  public long getBeatCount() {
    return this.clock.getBeatCount();
  }

  /** Monotonic count of BUILDING -> DRIVING transitions, for DropTracker. */
  public long getDropCount() {
    return this.dropCount;
  }

  public BeatClock getClock() {
    return this.clock;
  }

  /** Copies recent intensity samples for the graph, newest first. Returns the count. */
  public int getIntensityHistory(double[] outTime, double[] outValue, Mood[] outMood) {
    int count = Math.min(Math.min(outTime.length, outValue.length), this.historyCount);
    for (int i = 0; i < count; ++i) {
      int idx = Math.floorMod(this.historyHead - 1 - i, HISTORY_SAMPLES);
      outTime[i] = this.historyTime[idx];
      outValue[i] = this.historyValue[idx];
      if (outMood != null) {
        outMood[i] = this.historyMood[idx];
      }
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

  /** Output only -- intensity is measured, not driven. */
  @Override
  public LXNormalizedParameter setNormalized(double value) {
    return this;
  }

  @Override
  public boolean isWrappable() {
    return false;
  }
}
