import heronarts.p3lx.*;

Engine engine = new P3Engine();

void settings() {
  size(800, 800, P3D);
}

void setup() {
  Utils.sketchPath = sketchPath();
  engine.start();
}

void draw() {
  background(#222222);
}

class P3Engine extends Engine {

  P3LX lx;

  void start() {
    super.start();
    lx = (P3LX)super.lx;
    configureUI(lx);
  }

  P3LX createLX(LXModel model) {
    return new P3LX(DomeEngine.this, model);
  }

  void configureUI(P3LX lx) {
    UI3dContext context3d = new UI3dContext(lx.ui);
    context3d.addComponent(new UIPointCloud(lx, lx.model))
      //.setCenter(model.cx, 500, model.cz)
      //.setRadius(2000);
    //.setCenter(model.cx, model.heart.cy, model.cz)
    .setRadius(600);
    lx.ui.addLayer(context3d);

    lx.ui.addLayer(new UIChannelControl(lx.ui, lx, 4, 4));
  }

}