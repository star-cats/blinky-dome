package com.github.starcats.blinkydome.configuration.dlo;

import com.pi4j.io.gpio.*;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListener;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;
import heronarts.lx.output.LXOutput;
import heronarts.lx.output.LXOutputGroup;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * Raspi GPIO controller abstraction
 */
public class DloRaspiGpio {
  private static final int DEBOUNCE_MS = 200;

  public interface DipSwitchListener {
    void onDipSwitchChange(float newValueF);
  }

  public static GpioController gpio = null;

  public static GpioPinDigitalInput toggle;
  public static GpioPinDigitalInput blackMoment;
  public static GpioPinDigitalInput yellowMoment;
  public static GpioPinDigitalInput resetMoment;
  public static GpioPinDigitalInput dip0;
  public static GpioPinDigitalInput dip1;
  public static GpioPinDigitalInput dip2;
  public static GpioPinDigitalInput dip3;

  private static final List<DipSwitchListener> dipSwitchListeners = new LinkedList<>();

  public static void init(LXOutputGroup output) { // only raspi models should init
    try {
      gpio = GpioFactory.getInstance();
    } catch (UnsatisfiedLinkError e) {
      System.err.println("[DLoRaspiGPIO] UnsatisfiedLinkError initializing RaspiGPIO. Probably not a raspi. Skipping GPIO.");
      return;
    }
    System.out.println("Initializing DloRaspiGPIO...");

    toggle = gpio.provisionDigitalInputPin(
        RaspiPin.GPIO_01,             // PIN NUMBER
        "Blue Toggle",                // PIN FRIENDLY NAME (optional)
        PinPullResistance.PULL_DOWN   // PIN RESISTANCE (optional)
    );
    toggle.setDebounce(DEBOUNCE_MS);

    blackMoment = gpio.provisionDigitalInputPin(
        RaspiPin.GPIO_04,
        "Black Wire Moment",
        PinPullResistance.PULL_DOWN
    );
    blackMoment.setDebounce(DEBOUNCE_MS);

    yellowMoment = gpio.provisionDigitalInputPin(
        RaspiPin.GPIO_05,
        "Yellow Wire Moment",
        PinPullResistance.PULL_DOWN
    );
    yellowMoment.setDebounce(DEBOUNCE_MS);

    resetMoment = gpio.provisionDigitalInputPin(
        RaspiPin.GPIO_06,
        "Reset Moment",
        PinPullResistance.PULL_DOWN
    );

    dip0 = gpio.provisionDigitalInputPin(
        RaspiPin.GPIO_07,
        "DIP x0",
        PinPullResistance.PULL_DOWN
    );
    dip0.setDebounce(DEBOUNCE_MS);

    dip1 = gpio.provisionDigitalInputPin(
        RaspiPin.GPIO_00,
        "DIP x1",
        PinPullResistance.PULL_DOWN
    );
    dip1.setDebounce(DEBOUNCE_MS);

    dip2 = gpio.provisionDigitalInputPin(
        RaspiPin.GPIO_02,
        "DIP x2",
        PinPullResistance.PULL_DOWN
    );
    dip2.setDebounce(DEBOUNCE_MS);

    dip3 = gpio.provisionDigitalInputPin(
        RaspiPin.GPIO_03,
        "DIP x3",
        PinPullResistance.PULL_DOWN
    );
    dip3.setDebounce(DEBOUNCE_MS);


    GpioPinListener onDipSwitchChanger = new GpioPinListenerDigital() {
      @Override
      public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
        DloRaspiGpio.onDipSwitchChange();
      }
    };
    dip0.addListener(onDipSwitchChanger);
    dip1.addListener(onDipSwitchChanger);
    dip2.addListener(onDipSwitchChanger);
    dip3.addListener(onDipSwitchChanger);


    // Reset is called reset because it shuts down the pi
    resetMoment.setDebounce(800); // note debounced event triggers on leading edge -- ie 800 ms before next one happens
    resetMoment.addListener(new GpioPinListenerDigital() {
      @Override
      public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
        if (event.getState().isHigh()) {

          System.out.println("Starting shutdown sequence...");
          long startShutdownMs = System.currentTimeMillis();

          (new Thread(new Runnable() {

            public void run () {

              // Make sure we hold down for 3 sec
              while (true) {
                if (System.currentTimeMillis() - startShutdownMs > 3000) {
                  break;
                } else {
                  try {
                    Thread.sleep(500);
                  } catch (InterruptedException e) {
                    System.out.println("Warning: shutdown wait sleep interrupted: " + e);
                  }
                }
              }
              if (!resetMoment.isHigh()) {
                System.out.println("Shutdown aborted -- reset not held for 3 sec");
                return;
              }

              System.out.println("Turning off all LEDs...");

              output.mode.setValue(LXOutput.Mode.OFF);
              output.send(null);
              for (int i=0; i<100000; i++)
                Thread.yield();


              System.out.println("All lights off.  Shutting down the raspi.  Goodnight.");
              for (int i=0; i<100000; i++)
                Thread.yield();

              Runtime rt = Runtime.getRuntime();
              try {
                rt.exec("sudo shutdown -h now");
              } catch (IOException e) {
                System.out.println("Error shutting down:" + e);
              }
            }
          })
          ).start();
        }
      }
    });
  }

  public static boolean isActive() {
    return gpio != null;
  }

  public static boolean isBlackMoment() {
    return blackMoment.isHigh();
  }

  public static boolean isYellowMoment() {
    return yellowMoment.isHigh();
  }

  public static boolean isResetMoment() {
    return resetMoment.isHigh();
  }

  public static boolean isToggle() {
    return toggle.getState().isHigh();
  }

  /**
   * Gets 4-bit DIP switch value from 0-15
   * @return 0-15
   */
  public static int getDipValue() {
    return 0b1111
        & (dip0.isHigh() ? 0b1111 : 0b1110)
        & (dip1.isHigh() ? 0b1111 : 0b1101)
        & (dip2.isHigh() ? 0b1111 : 0b1011)
        & (dip3.isHigh() ? 0b1111 : 0b0111);
  }

  /**
   * Returns normalized dip value from 0-1
   * @return float between 0-1
   */
  public static float getDipValuef() {
    return (float)getDipValue()/15f;
  }

  public static void addDipSwitchListener(DipSwitchListener listener) {
    dipSwitchListeners.add(listener);
  }

  private static void onDipSwitchChange() {
    float newValue = getDipValuef();
    for (DipSwitchListener listener : dipSwitchListeners) {
      listener.onDipSwitchChange(newValue);
    }
  }

  /**
   * Triggers are an interrupt-driven alternative to polling.  Here's how you make one.
   */
  public static void exampleTriggerBind() {
    resetMoment.addListener(new GpioPinListenerDigital() {
      @Override
      public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
        // display pin state on console
        System.out.println(" --> GPIO PIN STATE CHANGE: " + event.getPin() + " = "
            + event.getState());
      }
    });
  }



}
