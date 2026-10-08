package com.starcats.blinkydome;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.bgfx.BGFX;

import heronarts.glx.DynamicVertexBuffer;
import heronarts.glx.GLX;
import heronarts.glx.View;
import heronarts.glx.VertexDeclaration;
import heronarts.glx.ui.UI;
import heronarts.glx.ui.UI3dComponent;
import heronarts.lx.LXEngine;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;

/**
 * 3D preview overlay that paints each "Triangle"-tagged fixture as a filled,
 * edge-lit panel instead of just three strings of dots.
 *
 * Why this exists: a BlinkyDome triangle is a Lexan sheet with LED strips
 * glued along its three edges.  In the room the sheet glows as a smooth
 * gradient between the three edges — the point renderer, which draws each
 * LED as an isolated dot, is a terrible preview of that.  This overlay
 * samples the strip LEDs where they meet at each corner, averages the pair,
 * and submits a single filled triangle whose per-vertex colors give an
 * interpolated gradient across the face.  Blended additively on top of the
 * normal LED cloud, so the LEDs still read as bright points inside the glow.
 *
 * How it finds triangles: walks the live model and collects every child
 * whose tag list contains "Triangle".  Each such child is expected to have
 * three descendants with four or more LED points along each strip; corners
 * are matched automatically as the three mutually-closest endpoint pairs
 * across the three strips.  Fixtures without that structure are skipped
 * silently, so adding this plugin to a non-dome project is a no-op.
 *
 * Performance: the triangle layout is captured once at startup (and once
 * per model-generation bump when the structure changes), after which the
 * only per-frame work is reading six LED colors per triangle, averaging
 * them into three vertex colors, writing twelve bytes into a dynamic
 * vertex buffer and submitting a single draw call.  For the ~hundreds of
 * triangles on the dome that is well under a millisecond per frame.
 *
 * Rendering state: BLEND_ADD, no depth test, no cull.  Additive on top of
 * the point cloud means LED points never dim; no depth test means the
 * panels show through each other without self-occluding; no cull means the
 * panels are visible from both sides when the user orbits the camera.
 */
public class UITriangleDiffuser extends UI3dComponent {

  /**
   * Prefix of the per-strip grouping tag used by compound fixtures.
   * Each strip in a compound carries a tag like "triangle1", "triangle2", … —
   * three strips sharing the same prefix+number form one triangle.
   */
  private static final String TRIANGLE_GROUP_PREFIX = "triangle";

  /** Scales corner color down so additive blending glows without blowing out. */
  private static final float ALPHA_SCALE = 0.45f;

  /** 3 vertices per triangle (filled gradient), POSITION + COLOR0 layout. */
  private static final int VERTS_PER_TRI = 3;

  /** POSITION(3*float=12B) + COLOR0(4*uint8=4B) = 16B per vertex. */
  private static final int STRIDE = 16;

  /** Draw state: additive blend, no depth test, no cull. */
  private static final long DRAW_STATE =
      BGFX.BGFX_STATE_WRITE_RGB
    | BGFX.BGFX_STATE_BLEND_ADD
    | BGFX.BGFX_STATE_MSAA;

  /** Record of one triangle fixture: the three corners (position + pair of LED indices). */
  private static final class TriLayout {
    /** Flat 9 floats: (x,y,z) for corners 0,1,2. */
    final float[] cornerPos = new float[9];
    /** Flat 6 ints: (ledA, ledB) for corners 0,1,2 — colors to average. */
    final int[] cornerLeds = new int[6];
  }

  private TriLayout[] triangles = new TriLayout[0];
  private DynamicVertexBuffer vb;
  /** Watched so we know when the model structure changed under us. */
  private LXModel cachedModel = null;
  private int cachedGeneration = -1;

  public UITriangleDiffuser(UI ui) {
    super();
  }

  @Override
  public void dispose() {
    disposeBuffer();
    super.dispose();
  }

  private void disposeBuffer() {
    if (this.vb != null) {
      this.vb.dispose();
      this.vb = null;
    }
  }

  @Override
  protected void onDraw(UI ui, View view) {
    final GLX glx = ui.lx;
    if (glx == null || glx.uiFrame == null) return;

    final LXEngine.Frame frame = glx.uiFrame;
    final LXModel model = frame.getModel();
    if (model == null) return;

    rebuildIfModelChanged(glx, model);
    if (this.triangles.length == 0 || this.vb == null) return;

    final int[] colors = frame.getColors();
    if (colors == null) return;

    // Fill the color bytes of every vertex from the current frame.  Positions
    // were written once at buffer-creation time; they don't move unless the
    // model's generation bumps, which rebuilt the buffer above.
    final ByteBuffer vd = this.vb.getVertexData();
    for (int ti = 0; ti < this.triangles.length; ti++) {
      final TriLayout t = this.triangles[ti];
      for (int ci = 0; ci < 3; ci++) {
        final int a = t.cornerLeds[2 * ci];
        final int b = t.cornerLeds[2 * ci + 1];
        final int ca = colors[a];
        final int cb = colors[b];
        // Blend the two converging edge LEDs' colors to approximate what the
        // Lexan at that corner would actually glow with.
        final int r = (((ca >>> 16) & 0xFF) + ((cb >>> 16) & 0xFF)) >> 1;
        final int g = (((ca >>>  8) & 0xFF) + ((cb >>>  8) & 0xFF)) >> 1;
        final int bl = ((ca & 0xFF) + (cb & 0xFF)) >> 1;
        final int rs = Math.min(255, (int)(r  * ALPHA_SCALE));
        final int gs = Math.min(255, (int)(g  * ALPHA_SCALE));
        final int bs = Math.min(255, (int)(bl * ALPHA_SCALE));
        final int off = (ti * 3 + ci) * STRIDE + 12;    // skip the 12 position bytes
        vd.put(off,     (byte) rs);
        vd.put(off + 1, (byte) gs);
        vd.put(off + 2, (byte) bs);
        vd.put(off + 3, (byte) 0xFF);
      }
    }
    this.vb.update();

    // Submit.  vertexFill reads per-vertex color from COLOR0; the view's
    // model-view-proj matrix is already applied to that shader.
    glx.program.vertexFill.submit(view, DRAW_STATE, this.vb);
  }

  // ── Model-driven buffer rebuild ───────────────────────────────────────────

  private void rebuildIfModelChanged(GLX glx, LXModel model) {
    final int gen = model.getGeneration();
    if (model == this.cachedModel && gen == this.cachedGeneration && this.vb != null) {
      return;
    }
    this.cachedModel = model;
    this.cachedGeneration = gen;

    final List<TriLayout> found = new ArrayList<>();
    collectTriangles(model, found);
    this.triangles = found.toArray(new TriLayout[0]);

    disposeBuffer();
    if (this.triangles.length == 0) return;

    final int totalVerts = this.triangles.length * VERTS_PER_TRI;
    this.vb = new DynamicVertexBuffer(glx, totalVerts,
        VertexDeclaration.Attribute.POSITION,
        VertexDeclaration.Attribute.COLOR0);

    // Positions are static — write them once.  Color bytes stay zero until
    // the first onDraw pass fills them in.
    final ByteBuffer vd = this.vb.getVertexData();
    for (int ti = 0; ti < this.triangles.length; ti++) {
      final TriLayout t = this.triangles[ti];
      for (int ci = 0; ci < 3; ci++) {
        final int off = (ti * 3 + ci) * STRIDE;
        vd.putFloat(off,     t.cornerPos[ci * 3]);
        vd.putFloat(off + 4, t.cornerPos[ci * 3 + 1]);
        vd.putFloat(off + 8, t.cornerPos[ci * 3 + 2]);
        // alpha-channel default: 0 so initial frame doesn't flash color
        vd.put(off + 12, (byte) 0);
        vd.put(off + 13, (byte) 0);
        vd.put(off + 14, (byte) 0);
        vd.put(off + 15, (byte) 0xFF);
      }
    }
    this.vb.update();
  }

  /**
   * Depth-first scan.  Handles two very different fixture layouts:
   *
   *   A. Single-triangle fixture (e.g. the standalone BlinkyTriangle).  A
   *      parent model tagged just "triangle" or "Triangle" with exactly
   *      three strip-leaf descendants.
   *
   *   B. Compound fixture (e.g. v3_quintile_module, 15 triangles).  No
   *      enclosing per-triangle parent; every strip carries a tag like
   *      "triangle1", "triangle2", … and three strips with the same
   *      numbered tag — inside the same parent — form one triangle.
   */
  private static void collectTriangles(LXModel m, List<TriLayout> out) {
    if (m == null) return;

    // Strategy A: single-triangle wrapper.
    if (looksLikeSingleTriangleWrapper(m)) {
      final List<LXPoint[]> edges = new ArrayList<>();
      collectLeafPointGroups(m, edges);
      if (edges.size() == 3) {
        final TriLayout layout = cornersFromEdges(edges.get(0), edges.get(1), edges.get(2));
        if (layout != null) out.add(layout);
        return;
      }
      // Too many or too few leaves — fall through to Strategy B so a
      // mis-tagged compound doesn't collapse to a single triangle.
    }

    if (m.children == null) return;

    // Strategy B: group this node's leaf-strip children by the "triangleN"
    // tag they carry.  Each group of three → one triangle.  Interior
    // children (nested modules) recurse.
    final Map<String, List<LXPoint[]>> groups = new LinkedHashMap<>();
    final List<LXModel> interior = new ArrayList<>();
    for (LXModel c : m.children) {
      if (isLeafStrip(c)) {
        final String key = triangleGroupKey(c);
        if (key != null) {
          groups.computeIfAbsent(key, k -> new ArrayList<>()).add(c.points);
        }
      } else {
        interior.add(c);
      }
    }
    for (List<LXPoint[]> g : groups.values()) {
      if (g.size() == 3) {
        final TriLayout layout = cornersFromEdges(g.get(0), g.get(1), g.get(2));
        if (layout != null) out.add(layout);
      }
    }
    for (LXModel c : interior) {
      collectTriangles(c, out);
    }
  }

  /** A leaf strip is a model with its own LED points and no further children. */
  private static boolean isLeafStrip(LXModel m) {
    return m != null
        && (m.children == null || m.children.length == 0)
        && m.points != null && m.points.length >= 2;
  }

  /**
   * True if this model's tag list suggests it's a single-triangle wrapper:
   * either a bare "triangle"/"Triangle" tag, or a specific "triangleN"
   * group tag applied to the whole wrapper (some fixture authors do this
   * instead of per-strip).
   */
  private static boolean looksLikeSingleTriangleWrapper(LXModel m) {
    if (m.tags == null) return false;
    for (String t : m.tags) {
      if ("triangle".equals(t) || "Triangle".equals(t)) return true;
      if (triangleGroupSuffixOrNull(t) != null) return true;
    }
    return false;
  }

  /**
   * Returns the "triangleN"-style tag on this strip, or null if it has none.
   * Used as the equivalence key when grouping compound-fixture strips.
   */
  private static String triangleGroupKey(LXModel m) {
    if (m.tags == null) return null;
    for (String t : m.tags) {
      if (triangleGroupSuffixOrNull(t) != null) return t;
    }
    return null;
  }

  /** Returns the digit suffix of "triangleN" / "TriangleN" tags, else null. */
  private static String triangleGroupSuffixOrNull(String tag) {
    if (tag == null) return null;
    final int plen = TRIANGLE_GROUP_PREFIX.length();
    if (tag.length() <= plen) return null;
    // Accept both lowercase ("triangle1") and capitalized ("Triangle1").
    final boolean lower = tag.regionMatches(false, 0, TRIANGLE_GROUP_PREFIX, 0, plen);
    final boolean upper = tag.regionMatches(true,  0, TRIANGLE_GROUP_PREFIX, 0, plen);
    if (!lower && !upper) return null;
    final String suffix = tag.substring(plen);
    for (int i = 0; i < suffix.length(); i++) {
      if (!Character.isDigit(suffix.charAt(i))) return null;
    }
    return suffix;
  }

  /**
   * Compute the 3 corner positions and 6 LED-color-sources from a set of
   * three edge-point arrays (one per strip).  Returns null if any edge
   * doesn't have enough points to pick an endpoint from.
   */
  private static TriLayout cornersFromEdges(LXPoint[] e0, LXPoint[] e1, LXPoint[] e2) {
    if (e0.length < 2 || e1.length < 2 || e2.length < 2) return null;
    final TriLayout t = new TriLayout();
    setCorner(t, 0, e0, e1);
    setCorner(t, 1, e1, e2);
    setCorner(t, 2, e2, e0);
    return t;
  }

  /**
   * For strips a and b, find the (a-endpoint, b-endpoint) pair that are
   * closest in 3D space.  Record their position as the midpoint, and their
   * point indices as the color sources.
   */
  private static void setCorner(TriLayout t, int cornerIdx, LXPoint[] a, LXPoint[] b) {
    final LXPoint[] aEnds = { a[0], a[a.length - 1] };
    final LXPoint[] bEnds = { b[0], b[b.length - 1] };
    float bestD2 = Float.POSITIVE_INFINITY;
    LXPoint bestA = aEnds[0];
    LXPoint bestB = bEnds[0];
    for (LXPoint ae : aEnds) {
      for (LXPoint be : bEnds) {
        final float dx = ae.x - be.x;
        final float dy = ae.y - be.y;
        final float dz = ae.z - be.z;
        final float d2 = dx*dx + dy*dy + dz*dz;
        if (d2 < bestD2) {
          bestD2 = d2;
          bestA = ae;
          bestB = be;
        }
      }
    }
    t.cornerPos[cornerIdx * 3    ] = 0.5f * (bestA.x + bestB.x);
    t.cornerPos[cornerIdx * 3 + 1] = 0.5f * (bestA.y + bestB.y);
    t.cornerPos[cornerIdx * 3 + 2] = 0.5f * (bestA.z + bestB.z);
    t.cornerLeds[cornerIdx * 2    ] = bestA.index;
    t.cornerLeds[cornerIdx * 2 + 1] = bestB.index;
  }

  /**
   * Walk a Triangle-fixture subtree and collect groups of adjacent LED
   * points — one group per strip.  A "leaf" model (a child with no children
   * of its own) that holds points is treated as one strip.
   */
  private static void collectLeafPointGroups(LXModel m, List<LXPoint[]> out) {
    if (m == null) return;
    if (m.children == null || m.children.length == 0) {
      if (m.points != null && m.points.length > 0) {
        out.add(m.points);
      }
      return;
    }
    for (LXModel c : m.children) {
      collectLeafPointGroups(c, out);
    }
  }
}
