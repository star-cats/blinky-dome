Mood controller:

===== PrimaryController (it's a modulator) =====

## Inputs

- Low level
- Mid level
- High level

## Config

Beat Tracker configs:

- Threshold, Lock, Shift

Other config:

- num of beats to average for bpm calculation
- min bpm (default 95, never allow the bpm to go under this value - hard limit)
- beats until ambient (default 6)
- smoothing factor on intensity (RC factors for charge and discharge are two different values)

## Internal tracking

Computes high level metadata on the active audio.
Stores this metadata in a way that is accessible for other modulators to access in realtime (e.g. static variable assignment or other)

- current bpm
- current smoothed intensity
- current mood

# Moods

These are states and are constantly being monitored how to state change as a function of the inputs.

- AMBIENT: No bass beat pulses have been seen for N beat (transition from DRIVING with this condition)
- BUILDING: Must transition from ambient (and ambient had to happen for at least 4 seconds). Detects slow and steady increment in smoothed intensity while there are no bass beats. (hand off to driving when two high beats, only surrender to ambient if intensity has gone below the max smoothed build intensity for 10 seconds).
- DRIVING: When at least two high confidence bass beats arrive, we jump to this state from anywhere.

# UI

- Show the input row first
- Show the beat tracker config values row
- Show the other config values row
- Show the bass beat tracker with ticks (red dot on registered beats, dashed line on predicted default, solid line on phase shifted)
- Show BPM in top left of tracker
- Show intensity tracker over time as a line graph (right side is current, going left is historical, 15 second window). Top left show current mood.

===== Other modulators =====

# DriveTracker

- uses the primary controller's internal tracker data to forecast bass beats.
- emits beats as exp(-k\*t) with K as a config on this modulator
- can be used as trigger on the beat OR as smooth signal with the decay line.
- Uses an RC smoother to allow signal through only when in DRIVING mood (1), and other moods are (0). Decay time configurable but roughly 2 seconds to transition fully.
- UI is the config params and a simple vertical meter of the output pulse + clear indication of current mood for clarity on gating.

# AmbientTracker

- Functionally the same as the DriveTracker for beat emissions.
- The intensity multipliear should just track the primary controller's smoothed intensity.

# DropTracker

- Only triggered when transitioning from BUILDING to DRIVING moods.
- Spike to output value 1, then decay it over seconds to 0 linearly.
- Single trigger impulse on start, but the "smooth map" value is this decay.
- config vlaue is just that decay duration.
- UI is the config params and a simple vertical meter of the output pulse.

---

# Clarifications (resolved before implementation)

These answer questions the spec above left open. Where they conflict with a
loose reading of the text above, these win.

## Wiring between modulators

PrimaryController publishes its state through a static registry of live
instances. The supporting modulators auto-bind to the single active controller
and output 0 (saying so in their UI) when there isn't one. Controllers
deregister on dispose so a project reload doesn't leak a stale reference.

Children read the controller through monotonic counters rather than
"did it happen this frame" flags, so they behave correctly regardless of what
order the engine runs the modulators in. Worst case a child acts one frame late.

## Intensity

The three band inputs combine as a weighted sum with configurable per-band
weights, normalized by their total. Defaults: Low 0.5, Mid 0.3, High 0.2.

That single figure is then RC smoothed with separate charge and discharge time
constants (defaults 0.15s charge, 1.5s discharge) to produce the smoothed
intensity everything downstream uses. It is also PrimaryController's own
modulator value, so the controller can be mapped directly.

## Min BPM is a hard floor

An interval implying a tempo below Min BPM (default 95) is rejected outright: it
never enters the averaging window, and the previous tempo holds. Nothing is
doubled or halved to bring it into range -- octave folding stays removed. A
genuinely slower track therefore never locks, which is the intended trade.

A gap longer than several beats is still treated separately as a dropout, which
resyncs the phase without contributing an interval.

## The high band is not beat-tracked

Only the Low input drives the beat clock. Mid and High contribute to intensity
only. The separate hi-hat tracking that existed before this refactor is dropped.

Consequently "hand off to driving when two high beats" means two high-confidence
*bass* beats -- the same trigger as DRIVING from any other state. A high
confidence beat is a sighting that cleared debounce, the Min BPM floor and the
outlier filter while confidence was at least 0.5. Two consecutive ones enter
DRIVING.

## Mood transition details

- Initial state is AMBIENT.
- DRIVING is entered from any state on two consecutive high-confidence bass
  beats.
- DRIVING -> AMBIENT when no bass sighting has arrived for `beats until ambient`
  beats at the current tempo (default 6).
- AMBIENT -> BUILDING requires at least 4s in AMBIENT and smoothed intensity
  higher than it was N seconds ago by more than a configured amount (defaults
  +0.10 over 3s). The DRIVING rule preempts this whenever bass returns, so "while
  there are no bass beats" needs no separate test.
- BUILDING -> AMBIENT when no new intensity peak has been set for 10 continuous
  seconds. The peak resets on entry to BUILDING, so this reads as "the build
  stalled".

## Output envelopes

The beat envelope is exp(-t*k/10) with t in seconds, matching the existing
convention: the output falls to 1/e after 10/k seconds. A literal exp(-k*t) with
k ranging to 100 would be instantaneous and unusable.

DropTracker's ramp is linear from 1 to 0 over its configured duration, as
specified, and it fires only on the BUILDING -> DRIVING edge -- not on
AMBIENT -> DRIVING.

## Gating

- DriveTracker is gated by an RC follower toward 1 in DRIVING and 0 elsewhere,
  with a configurable time constant defaulting to about 2 seconds.
- AmbientTracker has no mood gate at all. Its smoothed-intensity multiplier does
  the shaping, so it never leaves a hole between states.

The supporting modulators emit on the controller's beat grid and have no shift
or phase of their own; Shift stays a controller-level control.

## Consequence for existing projects

BeatTracker and UIBeatTracker are deleted. The two instances in
Projects/FullCampLayout2026.lxp will not load and will appear as missing
components. Settings to carry over to the new controller, for reference:

    Bass Beat Tracker: Thresh 0.372, Min BPM 99.6, Avg 12, Lock 0.372, Shift -63.8ms
    Hi-Hat Tracker:    dropped, no longer needed

## Revision: Min BPM floors the tempo, it does not reject beats

Supersedes "Min BPM is a hard floor" above.

Rejecting a sighting is not allowed -- an actual bass beat happened and must be
tracked. Every interval is recorded whatever tempo it implies. The floor is
applied to the average instead: the smoothed BPM handed out is never allowed to
read below Min BPM, and the clock runs on that floored period.

One consequence is worth stating plainly. Below the floor the clock necessarily
runs faster than the music -- at 70 BPM against a floor of 95 it emits 95 -- so
the floor should be set below the slowest track that will actually be played. It
exists to stop noise dragging the tempo down, not to transpose real music.

The outlier filter compares incoming intervals against the *unfloored* average.
Comparing against the floored one would make every interval of a genuinely slow
track read as an outlier, clearing the history and reseeding forever.

## Revision: the DriveTracker gate is linear

Supersedes "DriveTracker is gated by an RC follower" above.

The gate ramps linearly, so Gate is the actual time to travel the full 0-1 range
in either direction -- 2 seconds on the dial means the gate is fully open two
seconds after entering DRIVING, and fully shut two seconds after leaving.

This is closer to the original spec than the follower was. "roughly 2 seconds to
transition fully" is not something an RC curve does: one time constant gets it to
63% and it approaches the rest asymptotically without ever arriving.

## Revision: BUILDING is gone; the drop is AMBIENT to DRIVING with a holdoff

Supersedes the three-state mood machine described above.

BUILDING is removed. Recognising a riser meant judging the *shape* of the
intensity curve rather than the presence of an event, and that judgement is too
fragile to hang a show on -- the thresholds that separate a build from a track
simply getting louder hold for one song and not the next, and it guessed wrong at
exactly the moments that mattered. Two states remain, AMBIENT and DRIVING, both
decided purely by whether bass is landing, which is a fact rather than a
judgement.

Removed along with it: the Rise and Window parameters, the build peak and stall
tracking, and the intensity-rise detector. Intensity is still measured, smoothed,
published and used by AmbientTracker -- it simply no longer decides anything.

DropTracker now fires on AMBIENT -> DRIVING, and carries a **Reset** cooldown
defaulting to 60 seconds. A transition arriving inside that window is ignored
rather than deferred, so the holdoff cannot leave a drop queued to fire the
instant it expires. The cooldown starts elapsed, so the first drop of a set is
never swallowed.
