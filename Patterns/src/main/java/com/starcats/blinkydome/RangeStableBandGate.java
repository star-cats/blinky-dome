package com.starcats.blinkydome;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.audio.BandGate;

/**
 * BandGate variant whose saved minFreq / maxFreq survive project reloads.
 *
 * The stock {@link BandGate} inherits a load-order bug from BandFilter: the
 * parameter map registers minFreq before maxFreq, so on load LX sets minFreq
 * first — and BandFilter.onParameterChanged clamps minFreq to the CURRENT
 * maxFreq (still the default 120 Hz) if the incoming value is larger. The
 * user's stored minFreq is silently reset to 120 Hz on every load. Anyone
 * running a mid/high band gate (e.g. HighHat at 5 kHz - 14 kHz) sees their
 * setting evaporate.
 *
 * Fix: pop minFreq out of the JSON before super.load(), let the parent load
 * everything else (which sets maxFreq to its stored value), then apply the
 * saved minFreq. maxFreq is now the stored value, not the default, so the
 * clamp doesn't fire and the range is preserved end-to-end.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Band Gate (Stable Range)")
@LXComponent.Description("BandGate that preserves minFreq/maxFreq across project reloads")
public class RangeStableBandGate extends BandGate {

  private static final String KEY_PARAMETERS = "parameters";
  private static final String KEY_MIN_FREQ   = "minFreq";

  public RangeStableBandGate(LX lx) {
    super(lx);
  }

  @Override
  public void load(LX lx, JsonObject obj) {
    // Copy first — we're going to mutate the JSON before handing it to super,
    // and the caller may still want to read the original.
    JsonObject copy = obj.deepCopy();
    JsonElement savedMin = null;
    if (copy.has(KEY_PARAMETERS)) {
      JsonObject params = copy.getAsJsonObject(KEY_PARAMETERS);
      if (params != null && params.has(KEY_MIN_FREQ)) {
        savedMin = params.remove(KEY_MIN_FREQ);
      }
    }
    super.load(lx, copy);
    // maxFreq has now been restored from JSON; the constraint (min <= max)
    // will be evaluated against the real max instead of the default max.
    if (savedMin != null && savedMin.isJsonPrimitive()) {
      this.minFreq.setValue(savedMin.getAsDouble());
    }
  }
}
