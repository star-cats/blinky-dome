package com.github.starcats.blinkydome.configuration.dlo;

import com.pi4j.io.gpio.*;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListener;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;
import com.pi4j.platform.Platform;
import com.pi4j.platform.PlatformAlreadyAssignedException;
import com.pi4j.platform.PlatformManager;
import heronarts.lx.output.LXOutput;
import heronarts.lx.output.LXOutputGroup;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * ODroid GPIO controller abstraction
 */
public class BlinkyDomeOdroidGpio {
  private static final int DEBOUNCE_MS = 200;

  public static GpioController gpio = null;

  public static GpioPinDigitalInput orange;
  public static GpioPinDigitalInput yellow;
  public static GpioPinDigitalInput green;
  public static GpioPinDigitalInput blue;
  public static GpioPinDigitalInput pink;
//  public static GpioPinDigitalInput brown;

  public static void init() { // only odroid models should init
    try {
      PlatformManager.setPlatform(Platform.ODROID);
    } catch (PlatformAlreadyAssignedException e) {
      System.err.println("[BlinkyDomeOdroidGPIO] Cannot configure for odroid -- PlatformAlreadyAssigned\n" + e);
      return;
    }
    try {
      gpio = GpioFactory.getInstance();
    } catch (UnsatisfiedLinkError e) {
      System.err.println("[BlinkyDomeOdroidGPIO] UnsatisfiedLinkError initializing ODroidGPIO. Probably not an ODroid. Skipping GPIO.");
      return;
    }

    System.out.println("Initializing BlinkyDomeOdroidGPIO...");

    blue = gpio.provisionDigitalInputPin(
        OdroidXU4Pin.GPIO_06,             // PIN NUMBER
        "Blue Button",                // PIN FRIENDLY NAME (optional)
        PinPullResistance.PULL_DOWN   // PIN RESISTANCE (optional)
    );
    blue.setDebounce(DEBOUNCE_MS);

    green = gpio.provisionDigitalInputPin(
            OdroidXU4Pin.GPIO_23,
            "Green Button",
            PinPullResistance.PULL_DOWN
    );
    green.setDebounce(DEBOUNCE_MS);

    orange = gpio.provisionDigitalInputPin(
            OdroidXU4Pin.GPIO_21,
            "Orange Button",
            PinPullResistance.PULL_DOWN
    );
    orange.setDebounce(DEBOUNCE_MS);

    pink = gpio.provisionDigitalInputPin(
            OdroidXU4Pin.GPIO_26,
            "Pink Button",
            PinPullResistance.PULL_DOWN
    );
    pink.setDebounce(DEBOUNCE_MS);

    yellow = gpio.provisionDigitalInputPin(
            OdroidXU4Pin.GPIO_22,
            "Yellow Button",
            PinPullResistance.PULL_DOWN
    );
    yellow.setDebounce(DEBOUNCE_MS);
  }

  public static boolean isActive() {
    return gpio != null;
  }

  public static boolean isOrange() {
    return orange.isHigh();
  }

  public static boolean isYellow() {
    return yellow.isHigh();
  }

  public static boolean isGreen() {
    return green.isHigh();
  }

  public static boolean isBlue() {
    return blue.isHigh();
  }

  public static boolean isPink() {
    return pink.isHigh();
  }


  // Brown unconnected
//   public static boolean isBrown() {
//    return brown.isHigh();
//  }

  /**
   * Triggers are an interrupt-driven alternative to polling.  Here's how you make one.
   */
  public static void exampleTriggerBind() {
    orange.addListener(new GpioPinListenerDigital() {
      @Override
      public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) { // can also do lambda
        // display pin state on console
        System.out.println(" --> GPIO PIN STATE CHANGE: " + event.getPin() + " = "
            + event.getState());
      }
    });
  }



}
