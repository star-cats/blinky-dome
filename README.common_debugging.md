# Fixing Beat-Reactive Audio on Raspi

Raspi seems to have a glitch where only Minim OR LXAudio can be running... if both try to run, they don't work.

Since my code is mostly built around Minim beat detection, need to turn off LXAudio.

Best way of doing it is to make the code load up an .lxp config, then manually disable LXAudio in it.

Open up your .lxp and look for a block like this:

```
    "audio": {
      "id": 20,
      "class": "heronarts.lx.audio.LXAudioEngine",
      "parameters": {
        "label": "Audio",
        "enabled": false,  # Make sure this is 'false'!
        "mode": 0
      },
      ...
    },
```

If it doesn't have the 'Audio' parameter, add it and make sure it is `enabled: false`.
