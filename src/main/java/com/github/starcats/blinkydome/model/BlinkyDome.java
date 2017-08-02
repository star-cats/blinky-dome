package com.github.starcats.blinkydome.model;

import com.github.starcats.blinkydome.pixelpusher.PixelPushableLed;
import com.github.starcats.blinkydome.pixelpusher.PixelPushableModel;
import com.github.starcats.blinkydome.util.SCAbstractFixture;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import processing.core.PApplet;
import processing.data.Table;
import processing.data.TableRow;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Model of StarCats dome
 */
public class BlinkyDome extends LXModel implements PixelPushableModel {
  /** Like LXModel.points, but up-cast to our LED class */
  public final List<LED> leds;

  public final List<TriangleFixture> allTriangles;

  private final Map<Integer, List<TriangleFixture>> trianglesByLayer;
  private final Map<Integer, List<TriangleFixture>> trianglesByIndex;

  /**
   * Factory to construct a new instance from CSV LED position map
   * @param p processing instance
   * @return new BlinkyDome instance
   */
  public static BlinkyDome makeModel(PApplet p) {

    Table ledTable = p.loadTable("led-locations.csv", "header,csv");
    if (ledTable == null) {
      throw new RuntimeException("Error: could not load LED position data");
    }

    // TODO: tony: is this used for anything?
//    Table vertexTable = p.loadTable("vertex-locations.csv", "header,csv");
//    if (vertexTable == null) {
//      throw new RuntimeException("Error: could not load vertex locations");
//    }

    List<LED> allLeds = new ArrayList<>();
    for (TableRow row : ledTable.rows()) {
      allLeds.add(new LED(row));
    }

    // Group LEDs into triangles
    Map<String, List<LED>> ledsByTriangleHash = allLeds.stream().collect(Collectors.groupingBy(
        led -> "" + led.triangleX + ", " + led.triangleY + ", " + led.triangleZ
    ));

    List<TriangleFixture> allTriangles = ledsByTriangleHash.values().stream()
        .map(TriangleFixture::new).collect(Collectors.toList());

    Map<Integer, List<TriangleFixture>> trianglesByLayer = allTriangles.stream()
        .collect(Collectors.groupingBy(triangle -> triangle.layer));

    Map<Integer, List<TriangleFixture>> trianglesByIndex = allTriangles.stream()
        .collect(Collectors.groupingBy(triangle -> triangle.index));

    return new BlinkyDome(allLeds, allTriangles, trianglesByLayer, trianglesByIndex, p);
  }

  /**
   * BlinkyDome LED definition
   */
  public static class LED extends LXPoint implements PixelPushableLed {
    final public int triangleIndex;
    final public int triangleSubindex;
    final public int ledIndex; // in the triangle
    final public int layer;

    final public float triangleX, triangleY, triangleZ;

    final public float theta, phi;

    final public int ppGroup, ppStrip, ppIndex;

    public LED(TableRow row) {
      super(row.getFloat("x"), row.getFloat("z"), -row.getFloat("y"));

      this.triangleIndex = row.getInt("index");
      this.triangleSubindex = row.getInt("sub_index");
      this.ledIndex = row.getInt("led_num");
      this.layer = row.getInt("layer");

      this.triangleX = row.getFloat("triangle_center_x");
      this.triangleY = row.getFloat("triangle_center_y");
      this.triangleZ = row.getFloat("triangle_center_z");

      double r = Math.sqrt(x * x + y * y + z * z);

      this.theta = (float)Math.acos(z / r);
      this.phi = (float)Math.atan2(y, x);

//      this.ppGroup = row.getInt("pp_group");
//      this.ppStrip = row.getInt("pp_strip");
//      this.ppIndex = row.getInt("pp_index");


      // 0 105 210 315

      if (triangleIndex == 1 && triangleSubindex == 9) {
        this.ppGroup = 0;
        this.ppStrip = 1;
        this.ppIndex = 0 + ledIndex;

      } else if (triangleIndex == 1 && triangleSubindex == 5) {
        this.ppGroup = 0;
        this.ppStrip = 1;
        this.ppIndex = 105 + ledIndex;

      } else if (triangleIndex == 0 && triangleSubindex == 1) {
        this.ppGroup = 0;
        this.ppStrip = 1;
        this.ppIndex = 210 + ledIndex;

      } else if (triangleIndex == 0 && triangleSubindex == 9) {
        this.ppGroup = 0;
        this.ppStrip = 1;
        this.ppIndex = 315 + ledIndex;

      } else {
        this.ppGroup = -1;
        this.ppStrip = -1;
        this.ppIndex = -1;
      }
    }

    @Override
    public int getPpGroup() {
      return this.ppGroup;
    }

    @Override
    public int getPpStripIndex() {
      return this.ppStrip;
    }

    @Override
    public int getPpLedIndex() {
      return  this.ppIndex;
    }

    @Override
    public LXPoint getPoint() {
      return this;
    }
  }

  /**
   * BlinkyDome triangle definition
   */
  public static class TriangleFixture extends SCAbstractFixture {
    final public int index;
    final public int subindex;
    final public float x, y, z;
    final public int layer;

    private TriangleFixture(List<LED> leds) {
      if (leds == null || leds.size() == 0) {
        throw new RuntimeException("Configuration exception: no LED's passed to Triangle constructor!");
      }

      LED canonical = leds.get(0);
      this.index = canonical.triangleIndex;
      this.subindex = canonical.triangleSubindex;
      this.x = canonical.triangleX;
      this.y = canonical.triangleY;
      this.z = canonical.triangleZ;
      this.layer = canonical.layer;

      leds.forEach(this::addPointFast);
      this.initCentroid();
    }

    public String toString() {
      return "[Triangle index:" + index + " subindex:" + subindex + "]";
    }
  }

  private BlinkyDome(List<LED> allLEDs,
                     List<TriangleFixture> allTriangles,
                     Map<Integer, List<TriangleFixture>> trianglesByLayer,
                     Map<Integer, List<TriangleFixture>> trianglesByIndex, PApplet p) {
    super(new ArrayList<>(allLEDs)); // dupe LXPoint-typed ArrayList needed for java generics type inference ಠ_ಠ

    this.leds = Collections.unmodifiableList(allLEDs);


    // Make fixture definitions immutable:

    this.allTriangles = Collections.unmodifiableList(allTriangles);

    Map<Integer, List<TriangleFixture>> immutableTrianglesByLayer = new HashMap<>();
    trianglesByLayer.forEach(
        (layer, triangles) -> immutableTrianglesByLayer.put(layer, Collections.unmodifiableList(triangles))
    );
    this.trianglesByLayer = Collections.unmodifiableMap(immutableTrianglesByLayer);

    Map<Integer, List<TriangleFixture>> immutableTrianglesByIndex = new HashMap<>();
    trianglesByIndex.forEach(
        (index, triangles) -> immutableTrianglesByIndex.put(index, Collections.unmodifiableList(triangles))
    );
    this.trianglesByIndex = Collections.unmodifiableMap(immutableTrianglesByIndex);
  }

  public List<TriangleFixture> getTrianglesByLayer(Integer layer) {
    return trianglesByLayer.get(layer);
  }

  public Set<Integer> getLayerKeys() {
    return trianglesByLayer.keySet();
  }

  public List<TriangleFixture> getTrianglesByIndex(Integer index) {
    return trianglesByIndex.get(index);
  }

  public Set<Integer> getTriangleIndexKeys() {
    return trianglesByIndex.keySet();
  }

  @Override
  public List<? extends PixelPushableLed> getPpLeds() {
    return leds;
  }
}
