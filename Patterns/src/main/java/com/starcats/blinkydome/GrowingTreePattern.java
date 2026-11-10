package com.starcats.blinkydome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Fractal tree that grows out from a placed base and can shrink back.
 *
 * The performance split — static geometry, dynamic transform:
 *
 *   The tree skeleton is generated ONCE in a normalized "unit tree" frame
 *   (base at origin, trunk pointing +Y, tree fits roughly in a unit cube)
 *   and cached, along with a uniform spatial grid over its segments. Both
 *   are rebuilt only when a structural knob moves (Splits / Rate / Scatter
 *   / Curl / Seed). All the other knobs — Grow, Size, Angle, LocR, LocA,
 *   Thick, Hue, Tip — are pure per-frame values: they change the transform
 *   that carries each LED from world into the tree's local frame, and the
 *   gating that turns local hits into colors. No knob movement, no matter
 *   how fast, retriangulates or re-buckets anything.
 *
 * Per frame the loop is O(N_leds · k) where k is the number of segments in
 * a grid cell (single digits for the default tree). The naive version was
 * O(N_leds · N_segments) which is what was dragging the CPU.
 *
 * Growth model: every segment carries its normalized arc distance from the
 * root (0 at base, 1 at the furthest tip). An LED lights up when Grow is at
 * least the LED's nearest-segment arc position, so as Grow rises the light
 * spreads out along the tree from the trunk; as it drops it retracts along
 * the same paths — that's the "shrink backwards" behavior.
 *
 * Placement: LocR (radius, as a fraction of the model's half-diagonal) and
 * LocA (angle around vertical) place the base anywhere on the ground plane,
 * so multiple GrowingTree layers can grow from different points. Angle
 * rotates the whole tree around its own trunk.
 */
@LXCategory("Blinky Dome")
@LXComponent.Name("Growing Tree")
@LXComponent.Description(
  "Fractal tree with grow/shrink, size, and polar placement — all modulatable in real time.")
public class GrowingTreePattern extends LXPattern {

  /** Hard cap on skeleton segments so a bad knob combination can't runaway. */
  private static final int MAX_SEGMENTS = 2400;
  /** Recursion depth cap: 0 is the trunk. */
  private static final int MAX_DEPTH    = 4;

  /**
   * Grid cell size in unit-tree space. The tree fits roughly within a 2×2
   * box (trunk from 0..~1 vertically, branches out to ~±1 in x/z), so this
   * yields something like 15×15 cells per horizontal slab — small enough
   * that most cells hold single-digit segments, big enough that per-LED
   * neighbor queries stay bounded.
   */
  private static final float GRID_CELL = 0.12f;

  /** Cap on grid-search radius (in cells) so tiny Size can't blow up the search. */
  private static final int MAX_QUERY_CELLS = 8;

  // ── Dynamic knobs (cheap per frame — no rebuild) ─────────────────────────

  public final CompoundParameter growth =
    new CompoundParameter("Grow", 1.0, 0, 1)
    .setDescription("How much of the tree is drawn — 0 blank, 1 full. Sweep back to 0 to shrink.");

  public final CompoundParameter size =
    new CompoundParameter("Size", 1.0, 0.1, 2.0)
    .setDescription("Overall scale of the tree around its base");

  public final CompoundParameter angle =
    new CompoundParameter("Angle", 0, 0, 1)
    .setDescription("Rotation of the whole tree around vertical — 0..1 is one full turn");

  public final CompoundParameter locR =
    new CompoundParameter("LocR", 0, 0, 1)
    .setDescription("Base position radius from model center (0 = center, 1 = model edge)");

  public final CompoundParameter locA =
    new CompoundParameter("LocA", 0, 0, 1)
    .setDescription("Base position angle around model center — 0..1 is one full turn");

  public final CompoundParameter thickness =
    new CompoundParameter("Thick", 0.05, 0.005, 0.20)
    .setDescription("Branch thickness — fraction of model size");

  public final CompoundParameter hue =
    new CompoundParameter("Hue", 100, 0, 360)
    .setDescription("Base hue at the trunk");

  public final CompoundParameter tipShift =
    new CompoundParameter("Tip", 60, -180, 180)
    .setDescription("Hue shift from trunk to tip (positive = warmer at tips)");

  // ── Structural knobs (each change triggers one rebuild) ──────────────────

  public final CompoundParameter splits =
    new CompoundParameter("Splits", 2.0, 1.0, 5.0)
    .setDescription("Child branches per split point (rounded, structural)");

  public final CompoundParameter rate =
    new CompoundParameter("Rate", 3.0, 1.0, 8.0)
    .setDescription("How many times a branch splits along its length (rounded, structural)");

  public final CompoundParameter scatter =
    new CompoundParameter("Scatter", 0.3, 0, 1)
    .setDescription("Randomness of branch angles — 0 uniform, 1 chaotic (structural)");

  public final CompoundParameter curl =
    new CompoundParameter("Curl", 0.4, 0, 1)
    .setDescription("How much branches curl outward from the trunk axis (structural)");

  public final CompoundParameter seed =
    new CompoundParameter("Seed", 3, 0, 63)
    .setDescription("Change to redraw a different tree with the same knob values (structural)");

  // ── Cached geometry (unit-space, invariant under transform knobs) ────────

  private static final class Segment {
    /** Endpoints in unit-tree space. */
    final float x0, y0, z0, x1, y1, z1;
    /** Normalized arc position of the START of this segment (0..1 across the whole tree). */
    final float startLen;
    /** Normalized arc position of the END. */
    final float endLen;
    Segment(float x0, float y0, float z0, float x1, float y1, float z1,
            float startLen, float endLen) {
      this.x0=x0; this.y0=y0; this.z0=z0;
      this.x1=x1; this.y1=y1; this.z1=z1;
      this.startLen=startLen; this.endLen=endLen;
    }
  }

  private Segment[] segments = new Segment[0];
  private SegGrid   grid     = new SegGrid();

  // Snapshot of the last STRUCTURAL knobs; if these haven't moved we skip rebuild.
  private int   cachedSplits  = -1;
  private int   cachedRate    = -1;
  private float cachedScatter = Float.NaN;
  private float cachedCurl    = Float.NaN;
  private int   cachedSeed    = -1;

  public GrowingTreePattern(LX lx) {
    super(lx);
    addParameter("growth",    this.growth);
    addParameter("size",      this.size);
    addParameter("angle",     this.angle);
    addParameter("locR",      this.locR);
    addParameter("locA",      this.locA);
    addParameter("thickness", this.thickness);
    addParameter("hue",       this.hue);
    addParameter("tip",       this.tipShift);
    addParameter("splits",    this.splits);
    addParameter("rate",      this.rate);
    addParameter("scatter",   this.scatter);
    addParameter("curl",      this.curl);
    addParameter("seed",      this.seed);
  }

  // ── Per-frame render ─────────────────────────────────────────────────────

  @Override
  protected void run(double deltaMs) {
    rebuildIfNeeded();
    if (this.segments.length == 0) {
      Arrays.fill(this.colors, 0);
      return;
    }

    // Read all dynamic knobs ONCE, hoist everything the per-LED loop needs.
    float g       = this.growth.getValuef();
    float sizeVal = Math.max(1e-4f, this.size.getValuef());
    float invSize = 1f / sizeVal;
    float rotRad  = this.angle.getValuef() * (float)(2 * Math.PI);
    float cosA    = (float) Math.cos(-rotRad);   // world → local uses inverse rotation
    float sinA    = (float) Math.sin(-rotRad);

    // Place the base on the ground plane in polar-around-center coords.
    float halfDiag = 0.5f * (float) Math.hypot(
      this.model.xMax - this.model.xMin, this.model.zMax - this.model.zMin);
    float placeRad = this.locR.getValuef() * halfDiag;
    float placeAng = this.locA.getValuef() * (float)(2 * Math.PI);
    float baseX = this.model.cx + placeRad * (float) Math.cos(placeAng);
    float baseZ = this.model.cz + placeRad * (float) Math.sin(placeAng);
    float baseY = this.model.yMin;

    float maxDWorld = this.thickness.getValuef() * modelSize();
    float invMaxDWorld = 1f / Math.max(1e-6f, maxDWorld);
    float maxDLocal    = maxDWorld * invSize;   // convert world thickness into unit-tree space
    float maxDLocal2   = maxDLocal * maxDLocal;

    float hueBase = this.hue.getValuef();
    float hueSpan = this.tipShift.getValuef();

    // Grid query scratch — reused per LED.
    float[] outT   = new float[1];
    float[] outD2  = new float[1];

    Segment[] segs = this.segments;

    for (LXPoint p : this.model.points) {
      // World → local: subtract base, rotate around vertical, scale down by size.
      float dx = p.x - baseX;
      float dy = p.y - baseY;
      float dz = p.z - baseZ;
      float rx = cosA * dx + sinA * dz;
      float rz = -sinA * dx + cosA * dz;
      float lx = rx * invSize;
      float ly = dy * invSize;
      float lz = rz * invSize;

      int bestIdx = this.grid.queryNearest(lx, ly, lz, maxDLocal, maxDLocal2, segs, outT, outD2);
      if (bestIdx < 0) {
        this.colors[p.index] = 0;
        continue;
      }

      Segment s = segs[bestIdx];
      float t = outT[0];
      float arcPos = s.startLen + t * (s.endLen - s.startLen);
      if (arcPos > g) {
        this.colors[p.index] = 0;
        continue;
      }

      float dWorld = (float) Math.sqrt(outD2[0]) * sizeVal;
      float tRel = 1f - dWorld * invMaxDWorld;
      float b = 100f * tRel * tRel;               // squared falloff for a soft edge
      float h = hueBase + arcPos * hueSpan;
      this.colors[p.index] = LXColor.hsb(h, 90, b);
    }
  }

  // ── Skeleton generation (unit-tree space) ────────────────────────────────

  private void rebuildIfNeeded() {
    int   sSplits  = clampInt(Math.round(this.splits.getValuef()), 1, 5);
    int   sRate    = clampInt(Math.round(this.rate.getValuef()),   1, 8);
    float sScatter = this.scatter.getValuef();
    float sCurl    = this.curl.getValuef();
    int   sSeed    = clampInt(Math.round(this.seed.getValuef()),   0, 4096);

    if (sSplits == cachedSplits
     && sRate   == cachedRate
     && Float.compare(sScatter, cachedScatter) == 0
     && Float.compare(sCurl,    cachedCurl)    == 0
     && sSeed   == cachedSeed) {
      return;
    }
    cachedSplits  = sSplits;
    cachedRate    = sRate;
    cachedScatter = sScatter;
    cachedCurl    = sCurl;
    cachedSeed    = sSeed;

    build(sSplits, sRate, sScatter, sCurl, sSeed);
  }

  private void build(int splits, int rate, float scatter, float curl, int seed) {
    // Deterministic seed remix so consecutive integers give visibly different trees.
    Random rng = new Random(seed * 0x9E3779B1L + 1);

    List<Segment> raw = new ArrayList<>(256);
    float[] maxLen = { 0 };

    // Trunk starts at origin (unit-tree space) pointing +Y, half a unit tall.
    grow(raw, maxLen,
         0f, 0f, 0f,
         0f, 1f, 0f,
         0.55f,          // initial branch length in unit space
         0f,
         0,
         splits, rate, scatter, curl, rng);

    // Normalize arc positions to 0..1 across the whole tree.
    float total = Math.max(1e-6f, maxLen[0]);
    Segment[] arr = new Segment[raw.size()];
    for (int i = 0; i < arr.length; i++) {
      Segment s = raw.get(i);
      arr[i] = new Segment(s.x0, s.y0, s.z0, s.x1, s.y1, s.z1,
                           s.startLen / total, s.endLen / total);
    }
    this.segments = arr;
    this.grid.build(arr, GRID_CELL);
  }

  private void grow(List<Segment> out, float[] maxLen,
                    float x, float y, float z,
                    float dx, float dy, float dz,
                    float length,
                    float startLen,
                    int depth,
                    int splits, int rate, float scatter, float curl,
                    Random rng) {
    if (out.size() >= MAX_SEGMENTS) return;
    if (depth > MAX_DEPTH)         return;
    if (length < 0.02f)            return;

    int subSegs = Math.max(1, rate);
    float segLen = length / subSegs;
    // Curl bend per sub-segment, normalized so total bend along a branch is
    // independent of Rate (higher Rate → smaller per-step bend).
    float curlPerStep = curl * 0.6f / subSegs;
    float sagPerStep  = curl * 0.15f / subSegs;

    for (int i = 0; i < subSegs; i++) {
      if (curl > 0f) {
        // Bend outward from the tree's central axis (x=0, z=0 in unit space),
        // plus a small downward sag — this is what "curl away from base" looks like.
        float outX = x;
        float outZ = z;
        float rlen = (float) Math.sqrt(outX*outX + outZ*outZ);
        if (rlen > 1e-4f) { outX /= rlen; outZ /= rlen; }
        else {
          double ang = rng.nextDouble() * 2 * Math.PI;
          outX = (float) Math.cos(ang);
          outZ = (float) Math.sin(ang);
        }
        dx += outX * curlPerStep;
        dz += outZ * curlPerStep;
        dy -= sagPerStep;
        float dLen = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (dLen > 1e-6f) { dx/=dLen; dy/=dLen; dz/=dLen; }
      }

      float x1 = x + dx * segLen;
      float y1 = y + dy * segLen;
      float z1 = z + dz * segLen;

      out.add(new Segment(x, y, z, x1, y1, z1, startLen, startLen + segLen));
      if (out.size() >= MAX_SEGMENTS) return;
      x = x1; y = y1; z = z1;
      startLen += segLen;
      if (startLen > maxLen[0]) maxLen[0] = startLen;

      // Spawn children at each interior split point (never at the very tip).
      if (i < subSegs - 1 && depth < MAX_DEPTH) {
        int childCount = Math.max(1, splits);
        for (int b = 0; b < childCount; b++) {
          double baseAz    = 2.0 * Math.PI * b / childCount;
          double azJitter  = (rng.nextDouble() - 0.5) * scatter * Math.PI;
          double az        = baseAz + azJitter;
          double basePitch = Math.PI * 0.28;
          double pitchJit  = (rng.nextDouble() - 0.5) * scatter * Math.PI * 0.4;
          double pitch     = basePitch + pitchJit;

          float[] u = perpendicular(dx, dy, dz);
          float[] v = cross(dx, dy, dz, u[0], u[1], u[2]);

          float sp = (float) Math.sin(pitch);
          float cp = (float) Math.cos(pitch);
          float ca = (float) Math.cos(az);
          float sa = (float) Math.sin(az);
          float cdx = cp * dx + sp * (u[0] * ca + v[0] * sa);
          float cdy = cp * dy + sp * (u[1] * ca + v[1] * sa);
          float cdz = cp * dz + sp * (u[2] * ca + v[2] * sa);
          float cdL = (float) Math.sqrt(cdx*cdx + cdy*cdy + cdz*cdz);
          if (cdL > 1e-6f) { cdx/=cdL; cdy/=cdL; cdz/=cdL; }

          grow(out, maxLen, x, y, z, cdx, cdy, cdz,
               length * 0.55f, startLen,
               depth + 1, splits, rate, scatter, curl, rng);
          if (out.size() >= MAX_SEGMENTS) return;
        }
      }
    }
  }

  // ── Uniform spatial grid over unit-space segments ────────────────────────

  /**
   * Bounding-box-bucketed uniform grid. Each segment is registered in every
   * cell its AABB overlaps, so a query cell's list is exact. The "visited"
   * bitset dedups segments that live in more than one cell during a query.
   */
  private static final class SegGrid {
    private float cellSize = 1f;
    private float bMinX, bMinY, bMinZ;
    private int   nx = 0, ny = 0, nz = 0;
    /** cells[flatIdx] holds segment indices in that cell (null = empty). */
    private int[][] cells = new int[0][];
    /** Per-segment visited version, cleared incrementally via queryVersion. */
    private int[] visited = new int[0];
    private int queryVersion = 0;

    void build(Segment[] segs, float cellSize) {
      this.cellSize = cellSize;
      if (segs.length == 0) {
        this.cells = new int[0][];
        this.visited = new int[0];
        this.nx = this.ny = this.nz = 0;
        return;
      }
      float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
      float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
      for (Segment s : segs) {
        minX = Math.min(minX, Math.min(s.x0, s.x1));
        minY = Math.min(minY, Math.min(s.y0, s.y1));
        minZ = Math.min(minZ, Math.min(s.z0, s.z1));
        maxX = Math.max(maxX, Math.max(s.x0, s.x1));
        maxY = Math.max(maxY, Math.max(s.y0, s.y1));
        maxZ = Math.max(maxZ, Math.max(s.z0, s.z1));
      }
      float pad = cellSize;
      this.bMinX = minX - pad;
      this.bMinY = minY - pad;
      this.bMinZ = minZ - pad;
      this.nx = Math.max(1, (int) Math.ceil((maxX + pad - this.bMinX) / cellSize));
      this.ny = Math.max(1, (int) Math.ceil((maxY + pad - this.bMinY) / cellSize));
      this.nz = Math.max(1, (int) Math.ceil((maxZ + pad - this.bMinZ) / cellSize));

      int total = this.nx * this.ny * this.nz;
      List<List<Integer>> tmp = new ArrayList<>(total);
      for (int i = 0; i < total; i++) tmp.add(null);
      for (int si = 0; si < segs.length; si++) {
        Segment s = segs[si];
        int cx0 = clampInt((int) Math.floor((Math.min(s.x0, s.x1) - this.bMinX) / cellSize), 0, this.nx - 1);
        int cx1 = clampInt((int) Math.floor((Math.max(s.x0, s.x1) - this.bMinX) / cellSize), 0, this.nx - 1);
        int cy0 = clampInt((int) Math.floor((Math.min(s.y0, s.y1) - this.bMinY) / cellSize), 0, this.ny - 1);
        int cy1 = clampInt((int) Math.floor((Math.max(s.y0, s.y1) - this.bMinY) / cellSize), 0, this.ny - 1);
        int cz0 = clampInt((int) Math.floor((Math.min(s.z0, s.z1) - this.bMinZ) / cellSize), 0, this.nz - 1);
        int cz1 = clampInt((int) Math.floor((Math.max(s.z0, s.z1) - this.bMinZ) / cellSize), 0, this.nz - 1);
        for (int cz = cz0; cz <= cz1; cz++)
          for (int cy = cy0; cy <= cy1; cy++)
            for (int cx = cx0; cx <= cx1; cx++) {
              int idx = (cz * this.ny + cy) * this.nx + cx;
              List<Integer> l = tmp.get(idx);
              if (l == null) { l = new ArrayList<>(4); tmp.set(idx, l); }
              l.add(si);
            }
      }
      this.cells = new int[total][];
      for (int i = 0; i < total; i++) {
        List<Integer> l = tmp.get(i);
        if (l == null) continue;
        int[] arr = new int[l.size()];
        for (int j = 0; j < arr.length; j++) arr[j] = l.get(j);
        this.cells[i] = arr;
      }
      this.visited = new int[segs.length];
      this.queryVersion = 0;
    }

    /**
     * Nearest segment to (x,y,z) within maxR (with maxR^2 = maxR2). Writes
     * the closest-point parameter t (0..1) into outT[0] and the squared
     * distance into outD2[0]. Returns -1 if nothing found within maxR.
     */
    int queryNearest(float x, float y, float z, float maxR, float maxR2,
                     Segment[] segs, float[] outT, float[] outD2) {
      if (this.cells.length == 0) return -1;
      int cx = (int) Math.floor((x - this.bMinX) / this.cellSize);
      int cy = (int) Math.floor((y - this.bMinY) / this.cellSize);
      int cz = (int) Math.floor((z - this.bMinZ) / this.cellSize);
      int r  = clampInt((int) Math.ceil(maxR / this.cellSize), 1, MAX_QUERY_CELLS);

      // Bump the version so previous queries' "visited" marks are stale.
      // Reset the array on overflow — costs at worst once per ~2 billion queries.
      this.queryVersion++;
      if (this.queryVersion < 0) {
        Arrays.fill(this.visited, 0);
        this.queryVersion = 1;
      }
      int qv = this.queryVersion;

      int bestIdx = -1;
      float bestD2 = maxR2;
      float bestT  = 0;

      int cxLo = Math.max(0, cx - r), cxHi = Math.min(this.nx - 1, cx + r);
      int cyLo = Math.max(0, cy - r), cyHi = Math.min(this.ny - 1, cy + r);
      int czLo = Math.max(0, cz - r), czHi = Math.min(this.nz - 1, cz + r);

      for (int iz = czLo; iz <= czHi; iz++) {
        int stripZ = iz * this.ny;
        for (int iy = cyLo; iy <= cyHi; iy++) {
          int stripY = (stripZ + iy) * this.nx;
          for (int ix = cxLo; ix <= cxHi; ix++) {
            int[] arr = this.cells[stripY + ix];
            if (arr == null) continue;
            for (int k = 0; k < arr.length; k++) {
              int si = arr[k];
              if (this.visited[si] == qv) continue;
              this.visited[si] = qv;
              Segment s = segs[si];
              float abx = s.x1 - s.x0;
              float aby = s.y1 - s.y0;
              float abz = s.z1 - s.z0;
              float apx = x - s.x0;
              float apy = y - s.y0;
              float apz = z - s.z0;
              float len2 = abx*abx + aby*aby + abz*abz;
              float t = 0f;
              if (len2 > 1e-8f) {
                t = (apx*abx + apy*aby + apz*abz) / len2;
                if      (t < 0f) t = 0f;
                else if (t > 1f) t = 1f;
              }
              float qx = s.x0 + t * abx;
              float qy = s.y0 + t * aby;
              float qz = s.z0 + t * abz;
              float ex = x - qx, ey = y - qy, ez = z - qz;
              float d2 = ex*ex + ey*ey + ez*ez;
              if (d2 < bestD2) {
                bestD2 = d2;
                bestIdx = si;
                bestT = t;
              }
            }
          }
        }
      }
      outT[0]  = bestT;
      outD2[0] = bestD2;
      return bestIdx;
    }
  }

  // ── Math helpers ─────────────────────────────────────────────────────────

  /** A unit vector perpendicular to (x,y,z); world-axis-picking avoids collinearity. */
  private static float[] perpendicular(float x, float y, float z) {
    float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
    float hx = 0, hy = 0, hz = 0;
    if      (ax <= ay && ax <= az) hx = 1;
    else if (ay <= ax && ay <= az) hy = 1;
    else                            hz = 1;
    float rx = y*hz - z*hy;
    float ry = z*hx - x*hz;
    float rz = x*hy - y*hx;
    float rl = (float) Math.sqrt(rx*rx + ry*ry + rz*rz);
    if (rl < 1e-6f) return new float[] { 1, 0, 0 };
    return new float[] { rx/rl, ry/rl, rz/rl };
  }

  private static float[] cross(float x1, float y1, float z1,
                               float x2, float y2, float z2) {
    return new float[] {
      y1*z2 - z1*y2,
      z1*x2 - x1*z2,
      x1*y2 - y1*x2
    };
  }

  private static int clampInt(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  private float modelSize() {
    float sx = this.model.xMax - this.model.xMin;
    float sy = this.model.yMax - this.model.yMin;
    float sz = this.model.zMax - this.model.zMin;
    return Math.max(sx, Math.max(sy, sz));
  }
}
