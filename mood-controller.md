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
