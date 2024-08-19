package com.github.starcats.blinkydome.starpusher;

import java.util.List;

public interface StarPushableModel {
  List<? extends StarPushableLED> getSpLeds();
}
