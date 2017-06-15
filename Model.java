import java.util.ArrayList;
import java.util.List;

import processing.data.Table;
import processing.data.TableRow;

import heronarts.lx.model.LXAbstractFixture;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;

class Model extends LXModel {

  final List<LED> leds;

  @SuppressWarnings("unchecked")
  Model(Table ledData) {
    super(new Fixture(ledData));
    Fixture fixture = (Fixture)fixtures.get(0);
    leds = (List)fixture.getPoints();
  }

  static class Fixture extends LXAbstractFixture {
    Fixture(Table ledData) {
      for (TableRow row : ledData.rows()) {
        addPoint(new LED(row));
      }
    }
  }
}

class LED extends LXPoint {

  final int triangleIndex;
  final int triangleSubindex;
  final int ledIndex;

  LED(TableRow row) {
    super(row.getFloat("x"), row.getFloat("z"), -row.getFloat("y"));
    this.triangleIndex = row.getInt("index");
    this.triangleSubindex = row.getInt("sub_index");
    this.ledIndex = row.getInt("led_num");
  }
}