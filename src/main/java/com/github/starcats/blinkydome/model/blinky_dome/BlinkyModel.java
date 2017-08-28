package com.github.starcats.blinkydome.model.blinky_dome;

import com.github.starcats.blinkydome.pixelpusher.PixelPushableLED;
import com.github.starcats.blinkydome.pixelpusher.PixelPushableModel;
import heronarts.lx.model.LXModel;

import java.util.*;
import java.util.stream.Collectors;

/**
 * General model for some installation of a bunch of {@link BlinkyTriangle}'s
 */
public class BlinkyModel extends LXModel implements PixelPushableModel {
  /** Like LXModel.points, but up-cast to our LED class */
  public final List<BlinkyLED> leds;

  public final List<BlinkyTriangle> allTriangles;

  private final Map<Integer, List<BlinkyTriangle>> trianglesByGroup;

  /**
   * Mini factory that does standard slicing-and-dicing of a list of triangles to produce a BlinkyModel
   */
  public static BlinkyModel makeModel(List<BlinkyTriangle> allTriangles) {
    Map<Integer, List<BlinkyTriangle>> trianglesByGroup = allTriangles.stream()
        .collect(Collectors.groupingBy(triangle -> triangle.domeGroup));

    // Separate LED's into a single flat list (needed for LXModel constructor)
    List<BlinkyLED> allLeds = allTriangles.stream()
        .map(BlinkyTriangle::getPointsTyped)

        .flatMap(Collection::stream)
        .collect(Collectors.toList());

    // Sort by globally unique order (probably like this already? but guarantee to prevent any index problems)
    allLeds.sort(Comparator.comparingInt(blinkyLed -> blinkyLed.index));

    return new BlinkyModel(allLeds, allTriangles, trianglesByGroup);
  }

  /** Package constructor; use one of the package's factories to make these */
  public BlinkyModel(List<BlinkyLED> allLEDs,
                     List<BlinkyTriangle> allTriangles,
                     Map<Integer, List<BlinkyTriangle>> trianglesByGroup) {
    super(new ArrayList<>(allLEDs)); // dupe LXPoint-typed ArrayList needed for java generics type inference ಠ_ಠ

    this.leds = Collections.unmodifiableList(allLEDs);


    // Make fixture definitions immutable:

    this.allTriangles = Collections.unmodifiableList(allTriangles);


    Map<Integer, List<BlinkyTriangle>> immutableTrianglesByGroup = new HashMap<>(); // (the *values* will be immutable)
    trianglesByGroup.forEach(
        (index, triangles) -> immutableTrianglesByGroup.put(index, Collections.unmodifiableList(triangles))
    );
    this.trianglesByGroup = Collections.unmodifiableMap(immutableTrianglesByGroup);
  }

  public List<BlinkyTriangle> getTrianglesByGroup(Integer index) {
    return trianglesByGroup.get(index);
  }

  public Set<Integer> getTriangleGroupsKeys() {
    return trianglesByGroup.keySet();
  }

  @Override
  public List<? extends PixelPushableLED> getPpLeds() {
    return leds;
  }

  public List<String> getPPGroupKeys() {
    Set<String> keys = new HashSet<>();

    for (BlinkyTriangle triangle : allTriangles) {
      keys.add(triangle.ppGroup + "-" + triangle.ppPort);
    }

    List<String> keysList = new ArrayList<>(keys);
    keysList.sort(Comparator.naturalOrder());
    return keysList;
  }

  public List<BlinkyTriangle> getTriangleByPPGroupKey(String key) {
    int ppGroup = Integer.valueOf(key.substring(0,1));
    int ppPort = Integer.valueOf(key.substring(2,3));

    return allTriangles.stream().filter(
            triangle -> triangle.ppGroup == ppGroup && triangle.ppPort == ppPort
    ).collect(Collectors.toList());
  }
}
