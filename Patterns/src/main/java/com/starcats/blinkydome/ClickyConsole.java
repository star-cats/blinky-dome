package com.starcats.blinkydome;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.osc.LXOscComponent;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.TriggerParameter;

/**
 * The physical clicky console -- six push-pull pots and seven buttons -- as a
 * modulator.
 *
 * The console is a small board on the show network that fires
 * {@link ClickyPacket} datagrams at us. This class owns the socket that receives
 * them, mirrors the panel into a set of parameters, and hands the result to
 * {@link ClickyBinding}, which drives the Ethereal Juice Box pattern with it.
 *
 * <h2>Why this is not an OSC or Art-Net input</h2>
 *
 * Chromatik has UDP servers already -- OSC on 3030, Art-Net on 6454 -- and
 * either would have worked if the console spoke their wire format. It doesn't,
 * and making it would have bought nothing: the pull bits change what a pot
 * <em>means</em> rather than what it reads, which is interpretation that has to
 * live in Java either way. Once that code exists, owning eighteen bytes and a
 * {@link DatagramSocket} is the cheap part, and it keeps the magic, version and
 * sequence fields, none of which OSC or Art-Net would have carried for us.
 *
 * <h2>Threading</h2>
 *
 * The one thing worth copying from Chromatik's own receivers, and the thing that
 * makes rolling your own socket safe rather than reckless: the receive thread
 * does nothing but pull datagrams off the wire and queue them. Parameters are
 * only ever touched from {@link #computeValue}, which the engine calls on its
 * own thread. Setting an LXParameter from a socket thread races with the render
 * that is reading it.
 */
@LXCategory("Blinky Dome")
@LXModulator.Global("Clicky Console")
@LXComponent.Description("Receives the clicky console over UDP and drives the Ethereal Juice Box")
public class ClickyConsole extends LXModulator implements LXOscComponent {

  /** 49436 is 0xC11C, the packet magic, reused as the port. */
  public static final int DEFAULT_PORT = 49436;

  /** Comfortably over the 18 bytes we want, small enough to reject junk early. */
  private static final int RECEIVE_BUFFER_BYTES = 64;

  /**
   * Datagrams held between engine frames. Two or three is the normal depth at
   * 50 Hz; the cap is only there so a wedged engine cannot be turned into an
   * unbounded queue by a console that keeps talking. Overflow drops the
   * <em>oldest</em> packet, because every packet is full state and the newest
   * one is the only one that is definitely still true.
   */
  private static final int QUEUE_CAPACITY = 256;

  /**
   * How long a button stays pressed once it has been seen pressed, whatever the
   * console says next.
   *
   * A press and its release can easily land in the same engine frame: the
   * console coalesces sends to 50 Hz, and a loaded engine drops below that. The
   * pattern only reads parameters once per frame, so without this a real tap can
   * be delivered, applied to the parameter, and undone again before anything has
   * looked at it. Holding for two frames' worth of wall clock guarantees the
   * press is visible to at least one render.
   */
  private static final long MIN_PRESS_NANOS = 40_000_000L;

  /**
   * Silence that means the console is gone. The firmware heartbeats every
   * second even when nothing is moving, so this is two and a half missed
   * heartbeats -- long enough that ordinary Wi-Fi loss doesn't trip it.
   */
  private static final long LINK_TIMEOUT_NANOS = 2_500_000_000L;

  public final DiscreteParameter port =
    new DiscreteParameter("Port", DEFAULT_PORT, 1, 65535)
    .setUnits(LXParameter.Units.INTEGER)
    .setMappable(false)
    .setDescription("UDP port the console sends to");

  public final BooleanParameter connected =
    new BooleanParameter("Connected", false)
    .setMappable(false)
    .setDescription("Whether console packets are arriving");

  public final TriggerParameter activity =
    new TriggerParameter("Activity")
    .setMappable(false)
    .setDescription("Fires on every packet received");

  public final BooleanParameter log =
    new BooleanParameter("Log", false)
    .setMappable(false)
    .setDescription("Write link events and packet loss to the Chromatik log");

  public final BooleanParameter bind =
    new BooleanParameter("Bind", true)
    .setDescription("Drive the Ethereal Juice Box pattern from the console");

  private final BoundedParameter[] pots = new BoundedParameter[ClickyPacket.POT_COUNT];
  private final BooleanParameter[] pulls = new BooleanParameter[ClickyPacket.POT_COUNT];
  private final BooleanParameter[] buttons = new BooleanParameter[ClickyPacket.BUTTON_COUNT];

  private final ClickyBinding binding = new ClickyBinding(this);

  // --- Shared between the receive thread and the engine thread ----------------

  private final ArrayDeque<ClickyPacket> queue = new ArrayDeque<ClickyPacket>(QUEUE_CAPACITY);
  private final AtomicBoolean hasPackets = new AtomicBoolean(false);
  private volatile DatagramSocket socket = null;

  // --- Engine thread only -----------------------------------------------------

  private final List<ClickyPacket> drained = new ArrayList<ClickyPacket>(QUEUE_CAPACITY);
  private final long[] releaseAtNanos = new long[ClickyPacket.BUTTON_COUNT];
  private final boolean[] releasePending = new boolean[ClickyPacket.BUTTON_COUNT];
  private long lastPacketNanos = 0;
  private int lastSequence = 0;
  private long droppedPackets = 0;

  /**
   * Whether a packet has arrived since Chromatik started.
   *
   * The mirrored panel is made of ordinary parameters, so it is written into the
   * project file along with everything else and comes back on load -- including
   * a "connected" that was true when the file was saved. Without this, loading a
   * project with no console on the network would spend one frame believing the
   * link was up and push a stale snapshot of the panel straight over the
   * pattern's own saved knob positions. Nothing is pushed until the console has
   * actually said something.
   */
  private boolean everReceived = false;

  public ClickyConsole() {
    this("Clicky Console");
  }

  public ClickyConsole(String label) {
    super(label);
    addParameter("port", this.port);
    addParameter("connected", this.connected);
    addParameter("activity", this.activity);
    addParameter("log", this.log);
    addParameter("bind", this.bind);
    for (int i = 0; i < ClickyPacket.POT_COUNT; ++i) {
      final int n = i + 1;
      addParameter("pot" + n, this.pots[i] =
        new BoundedParameter("Pot " + n, 0)
        .setDescription("Pot " + n + " position"));
      addParameter("pull" + n, this.pulls[i] =
        new BooleanParameter("Pull " + n, false)
        .setDescription("Whether pot " + n + " is pulled out"));
    }
    for (int i = 0; i < ClickyPacket.BUTTON_COUNT; ++i) {
      final int n = i + 1;
      addParameter("button" + n, this.buttons[i] =
        new BooleanParameter("Btn " + n, false)
        .setDescription("Whether button " + n + " is pressed"));
    }
  }

  /** Pot position, 0-1. */
  public BoundedParameter pot(int index) {
    return this.pots[index];
  }

  /** Whether that pot is pulled out. */
  public BooleanParameter pull(int index) {
    return this.pulls[index];
  }

  /** Whether that button is held down. */
  public BooleanParameter button(int index) {
    return this.buttons[index];
  }

  @Override
  public void onParameterChanged(LXParameter parameter) {
    super.onParameterChanged(parameter);
    if (parameter == this.port && isRunning()) {
      closeSocket();
      openSocket();
    }
  }

  @Override
  protected void onStart() {
    openSocket();
  }

  @Override
  protected void onStop() {
    closeSocket();
    dropLink();
  }

  @Override
  protected double computeValue(double deltaMs) {
    final boolean wasConnected = this.connected.isOn();
    drainQueue();
    final long now = System.nanoTime();
    releaseHeldButtons(now);
    checkLink(now);
    // The panel is read onto the pattern while connected, and once more on the
    // frame the link drops. That last read is what carries the releases out of
    // dropLink() to the pattern; without it a button held at the moment the
    // cable came out would stay on over there forever. After that the binding
    // stops writing the pattern's controls, which is what lets the on-screen
    // ones work with no console attached.
    //
    // The binding is still called every frame, though, because it owns the
    // waterfall faders as well as the pattern: a fade in flight when the cable
    // came out has to run to the end rather than stop where it was. Nothing at
    // all happens until the console has spoken once, so opening a project with
    // no hardware on the network leaves every fader exactly as it was saved.
    if (this.everReceived && this.bind.isOn()) {
      this.binding.update(this.lx, deltaMs, this.connected.isOn() || wasConnected);
    }
    // The modulator's own value is the link state, so "is the console alive"
    // can be mapped onto something visible without opening this panel.
    return this.connected.isOn() ? 1 : 0;
  }

  // ------------------------------------------------------------------ the socket

  private void openSocket() {
    if (this.socket != null) {
      return;
    }
    final int port = this.port.getValuei();
    try {
      final DatagramSocket socket = new DatagramSocket(port);
      this.socket = socket;
      final Thread thread = new Thread(() -> receiveLoop(socket), "ClickyConsole-" + port);
      thread.setDaemon(true);
      thread.start();
      LX.log("Clicky console listening on UDP " + port);
    } catch (SocketException sx) {
      LX.error(sx, "Clicky console could not bind UDP port " + port);
      this.lx.pushError(sx, "Clicky console could not bind UDP port " + port + "\n"
        + sx.getLocalizedMessage());
      // Deferred rather than immediate: we are inside the listener that turned
      // running on, and turning it back off from in here re-enters that same
      // parameter. One engine task later it is an ordinary state change.
      this.lx.engine.addTask(() -> stop());
    }
  }

  private void closeSocket() {
    final DatagramSocket socket = this.socket;
    if (socket == null) {
      return;
    }
    // Closing is how the receive thread is stopped. It is parked inside a
    // blocking receive(), which interrupt() does not disturb; close() makes that
    // call throw and the loop sees isClosed() and returns.
    this.socket = null;
    socket.close();
    synchronized (this.queue) {
      this.queue.clear();
    }
    this.hasPackets.set(false);
  }

  /** Receive thread. Touches nothing but the queue. */
  private void receiveLoop(DatagramSocket socket) {
    // Read up front: a closed socket reports its local port as -1, and this is
    // only wanted for the line logged on the way out.
    final int port = socket.getLocalPort();
    final byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
    final DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
    while (!socket.isClosed()) {
      try {
        // receive() shrinks the datagram to what it read, so the capacity has to
        // be restored or the second packet onward is silently truncated.
        datagram.setLength(buffer.length);
        socket.receive(datagram);
        final ClickyPacket packet = ClickyPacket.decode(buffer, datagram.getLength());
        if (packet == null) {
          continue;
        }
        synchronized (this.queue) {
          if (this.queue.size() >= QUEUE_CAPACITY) {
            this.queue.removeFirst();
          }
          this.queue.addLast(packet);
        }
        this.hasPackets.set(true);
      } catch (IOException iox) {
        if (!socket.isClosed()) {
          LX.error(iox, "Clicky console receive error: " + iox.getLocalizedMessage());
        }
      }
    }
    LX.log("Clicky console stopped listening on UDP " + port);
  }

  // ------------------------------------------------------------- the engine side

  private void drainQueue() {
    if (this.hasPackets.compareAndSet(true, false)) {
      synchronized (this.queue) {
        this.drained.addAll(this.queue);
        this.queue.clear();
      }
    }
    // Every queued packet is applied, in order, rather than only the newest.
    // Positions would be fine either way -- the last one wins -- but a button
    // that went down and back up between two frames only exists in the packets
    // in between, and skipping them is exactly how a fast tap gets lost.
    for (int i = 0; i < this.drained.size(); ++i) {
      apply(this.drained.get(i));
    }
    this.drained.clear();
  }

  private void apply(ClickyPacket packet) {
    final long now = System.nanoTime();

    if (this.connected.isOn()) {
      final int drops = packet.dropsSince(this.lastSequence);
      if (drops > 0) {
        this.droppedPackets += drops;
        if (this.log.isOn()) {
          LX.log("Clicky console missed " + drops + " packet(s), "
            + this.droppedPackets + " since start");
        }
      }
    } else {
      this.connected.setValue(true);
      this.binding.linkUp();
      if (this.log.isOn()) {
        LX.log("Clicky console link up");
      }
    }
    this.lastSequence = packet.sequence;
    this.lastPacketNanos = now;
    this.everReceived = true;

    for (int i = 0; i < ClickyPacket.POT_COUNT; ++i) {
      this.pots[i].setValue(packet.position(i));
      // No minimum hold on the pulls, unlike the buttons: a knob physically
      // cannot be pulled and pushed back inside one frame.
      this.pulls[i].setValue(packet.pull(i));
    }
    for (int i = 0; i < ClickyPacket.BUTTON_COUNT; ++i) {
      setButton(i, packet.button(i), now);
    }
    this.activity.trigger();
  }

  /**
   * A press is applied immediately and cannot be taken back for
   * {@link #MIN_PRESS_NANOS}; a release that arrives inside that window is
   * remembered and served by {@link #releaseHeldButtons}.
   *
   * Two taps inside one hold window collapse into one press. That is the honest
   * limit of representing a button as a per-frame boolean, and it is the right
   * side to lose on: a swallowed second tap is a missed accent, where a swallowed
   * release is a control stuck on.
   */
  private void setButton(int index, boolean pressed, long now) {
    if (pressed) {
      this.releaseAtNanos[index] = now + MIN_PRESS_NANOS;
      this.releasePending[index] = false;
      this.buttons[index].setValue(true);
    } else if (now >= this.releaseAtNanos[index]) {
      this.releasePending[index] = false;
      this.buttons[index].setValue(false);
    } else {
      this.releasePending[index] = true;
    }
  }

  private void releaseHeldButtons(long now) {
    for (int i = 0; i < ClickyPacket.BUTTON_COUNT; ++i) {
      if (this.releasePending[i] && (now >= this.releaseAtNanos[i])) {
        this.releasePending[i] = false;
        this.buttons[i].setValue(false);
      }
    }
  }

  private void checkLink(long now) {
    if (!this.connected.isOn()) {
      return;
    }
    if (!this.everReceived) {
      // Restored from a project file that was saved with the link up. Not a
      // loss, so it is corrected without the alarming log line.
      dropLink();
      return;
    }
    if (now - this.lastPacketNanos >= LINK_TIMEOUT_NANOS) {
      // Logged unconditionally, and with log() rather than warning(): losing the
      // console mid-show is the one event you will want in the log afterwards,
      // and LX.warning is compiled past unless LOG_WARNINGS was turned on.
      LX.log("Clicky console link lost, no packet for "
        + (LINK_TIMEOUT_NANOS / 1000000) + "ms");
      dropLink();
    }
  }

  /**
   * Give up on the console.
   *
   * Everything it was holding down is released, because a console that has gone
   * quiet is not still pressing anything, and a button left latched on by a
   * yanked cable would drive the pattern for the rest of the night. Pot and pull
   * positions are deliberately left where they are: those are physical states
   * still visible on the panel, and re-seating a cable should not move them.
   */
  private void dropLink() {
    this.connected.setValue(false);
    for (int i = 0; i < ClickyPacket.BUTTON_COUNT; ++i) {
      this.releasePending[i] = false;
      this.buttons[i].setValue(false);
    }
    this.binding.reset();
  }

  @Override
  public void dispose() {
    closeSocket();
    super.dispose();
  }
}
