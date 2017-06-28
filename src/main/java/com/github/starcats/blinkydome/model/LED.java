package com.github.starcats.blinkydome.model;

import heronarts.lx.model.LXPoint;
import processing.data.TableRow;

/**
 * Created by akesich on 6/25/17.
 */
public class LED extends LXPoint {
    final public int triangleIndex;
    final public int triangleSubindex;
    final public int ledIndex;
    final public int layer;

    final public float x, y, z;
    final public float triangleX, triangleY, triangleZ;

    final public float theta, phi;

    public LED(TableRow row) {
        super(row.getFloat("x"), row.getFloat("z"), -row.getFloat("y"));
        this.x = row.getFloat("x");
        this.y = row.getFloat("y");
        this.z = row.getFloat("z");

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
    }
}

