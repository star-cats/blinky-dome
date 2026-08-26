package com.starcats.blinkydome;

/**
 * One ClickyPacketV1 datagram off the wire, decoded.
 *
 * The console -- six push-pull pots and seven buttons -- sends its whole state
 * in eighteen bytes, on every change and once a second regardless:
 *
 * <pre>
 *   offset size  field
 *   0      2     magic     0xC11C, little-endian
 *   2      1     ver       1
 *   3      1     seq       send counter, wraps at 256
 *   4      1     pullBits  bit i set = pot i pulled out
 *   5      1     btnBits   bit i set = button i pressed
 *   6      12    pos[6]    uint16 little-endian, 0..10000
 * </pre>
 *
 * Every packet is full state, so there is nothing to reassemble and nothing to
 * acknowledge. A dropped datagram costs only the latency to the next one, and a
 * receiver that has seen nothing but the most recent packet is completely up to
 * date. That is the whole reason the sequence number is advisory -- it exists to
 * count losses, not to recover from them.
 *
 * Deliberately holds no Chromatik. This is the wire format and nothing else, so
 * it can be exercised without an engine, a socket or a UI.
 */
public final class ClickyPacket {

  /** Every valid packet is exactly this long; anything else is not ours. */
  public static final int LENGTH = 18;

  public static final int MAGIC = 0xC11C;
  public static final int VERSION = 1;

  public static final int POT_COUNT = 6;
  public static final int BUTTON_COUNT = 7;

  /** Full-scale pot reading. The firmware sends hundredths of a percent. */
  public static final int POSITION_MAX = 10000;

  /** Send counter, 0-255. Consecutive packets differ by one, modulo 256. */
  public final int sequence;

  private final int pullBits;
  private final int buttonBits;
  private final int[] positions;

  private ClickyPacket(int sequence, int pullBits, int buttonBits, int[] positions) {
    this.sequence = sequence;
    this.pullBits = pullBits;
    this.buttonBits = buttonBits;
    this.positions = positions;
  }

  /**
   * Decode a received datagram, or return <code>null</code> if it isn't one of
   * ours.
   *
   * A UDP port will happily hand over anything at all that is addressed to it --
   * a stray broadcast, a port scan, the second half of some other protocol's
   * conversation -- so length, magic and version are all checked before any of
   * the payload is believed. Rejection is silent and cheap by design: on an open
   * network this is the common case, not the error case.
   *
   * @param buffer Receive buffer, which may be longer than the datagram
   * @param length Bytes actually received
   * @return Decoded packet, or null if the datagram is not a ClickyPacketV1
   */
  public static ClickyPacket decode(byte[] buffer, int length) {
    if (length != LENGTH) {
      return null;
    }
    if (u16(buffer, 0) != MAGIC || u8(buffer, 2) != VERSION) {
      return null;
    }
    final int[] positions = new int[POT_COUNT];
    for (int i = 0; i < POT_COUNT; ++i) {
      // Clamped rather than trusted. The firmware promises 0..10000, but a pot
      // reading is an ADC value with a scale factor on it, and one bad unit
      // sending 65535 should cost a pinned knob rather than a knob that has
      // travelled a hundred and eighty times further than the box is wide.
      positions[i] = Math.min(POSITION_MAX, u16(buffer, 6 + 2 * i));
    }
    return new ClickyPacket(u8(buffer, 3), u8(buffer, 4), u8(buffer, 5), positions);
  }

  /** Pot position, normalized to 0-1. */
  public double position(int pot) {
    return this.positions[pot] / (double) POSITION_MAX;
  }

  /** Whether pot <code>pot</code> is pulled out. */
  public boolean pull(int pot) {
    return (this.pullBits & (1 << pot)) != 0;
  }

  /** Whether button <code>button</code> is held down. */
  public boolean button(int button) {
    return (this.buttonBits & (1 << button)) != 0;
  }

  /**
   * How many packets went missing between the one carrying {@code previous} and
   * this one. Zero when this is the next packet in sequence.
   *
   * The counter is a byte, so this cannot tell 3 losses from 259; over a link
   * that drops that heavily the exact figure has stopped being the interesting
   * part.
   */
  public int dropsSince(int previousSequence) {
    return (this.sequence - previousSequence - 1) & 0xff;
  }

  private static int u8(byte[] buffer, int offset) {
    return buffer[offset] & 0xff;
  }

  private static int u16(byte[] buffer, int offset) {
    return (buffer[offset] & 0xff) | ((buffer[offset + 1] & 0xff) << 8);
  }
}
