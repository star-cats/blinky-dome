- Port image-pattern refs to jar-bundled resources (see ImageTest.java). Chromatik
  resolves ImagePattern fileName with Paths.get() against the JVM cwd, not the media
  folder, so there is no relative path that works — assets now live in Images/ but the
  refs are still absolute under ~/Chromatik/
- The front star should be vertical and model config position it with azimuth
- Try on window machine as sanity check

- Sync with Ben on game plan
- Add other fixtures if relevant

- Create Project with a calibration pattern. Same model/fixtures loaded, but only calibration pattern / calibration grid channel group
- ip + universe mappings

- TBD: java beat tracker and mood state controller
- bring animations to life (rip through the list of fun background animations)
- mids/highs with counter pulse channels (dodge, burn, substract)
