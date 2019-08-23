# ODroid Ops Reference

This file describes how to run and debug our JARs on our odroid and raspi images.

Namings:

    - `stratocat`: The name of the odroid machine (first iteration was a cloud... get it?)
    - `starcat`: The user on the odroid machine image.
    - `icosa` / `icosastar`:  The original name for this project was `icosastar`, so some scripts and directories 
      reference the orig name.


# Where are things?

Startup scripts:

    - `/etc/init.d/sc-Xvfb`: Starts up virtual frame buffer (headless processing dependency)
    - `/etc/init.d/sc-pulseaudio`: Starts up pulseaudio server for sound reactivity
    - `/etc/init.d/sc-icosa-jar`: Starts up the headless Java app

JARs and resources:

    - `/etc/starcats/icosastar/`: Where the various jars live.

Basically, the `sc-icosa-jar` init.d script fires up one of the JARs in `/etc/starcats/icosastar/`.


# Ops

You've ssh'd into the odroid, now what?

## Is it running?

```sh
ps aux | grep java
```

## Starting / Stopping JAR as init.d service

```sh
sudo service sc-icosa-jar stop
sudo service sc-icosa-jar start
```

This uses the `/etc/init.d/sc-icosa-jar` script. Open up that script to see which JAR is getting loaded.


## Starting JAR manually

If the JAR won't start up with the service, it's useful to start it up manually to see the output and see why it's
crashing.

Also helpful to start up manually when testing a new JAR brought onto the machine.

```sh
sudo DISPLAY=":1" java -jar blinky-dome-2019-headless.jar
```


# Installing a new version of the jar

Now lets combine all our knowledge to 'install' a new version of the app onto the odroid.

First, make sure you're compiling the headless configuration (no P3LX):
    1. Open up `ConfigSupplier.java`
    1. Uncomment the 'headless' config: `return new BlinkyDomeConfig(p);`. Make the `GuiConfig()` commented out.

Next, compile and get the headless fat jar up onto the odroid.

You need to make sure the odroid is on the network and you found its IP (check your router page). Here we're gonna 
say it's on `192.168.1.100`

```sh
my-dev-machine$ ./gradlew headlessShadowJar
my-dev-machine$ scp ./build/libs/blinky-dome-headless-all.jar starcat@192.168.1.100:~/blinky-dome-some-name-like-2019-bm.jar'
```

Next, get onto the odroid and make sure it runs:

```sh
my-dev-machine$ ssh starcat@192.168.1.100

# stop the existing process
stratocat:~$ sudo service sc-icosa-jar stop

# run the new jar manually
stratocat:~$ sudo DISPLAY=":1" java -jar blinky-dome-some-name-like-2019-bm.jar

# Wait until you see the line 'LXEngine Render Thread started'

# Great, things work, kill the process
ctrl + c
```

Next, replace the jar targetted by the init.d script with the new jar

```sh
# Open up the init.d script to see what jar is being targetted by it
stratocat:~$ vim /etc/init.d/sc-icosa-jar

# Don't have to read the whole script, just look at the first uncommented line. It should be something like:
#   SCRIPT="...."
# That tells you which jar the script is targetting. Note the path

# Now replace it with your jar
stratocat:~$ sudo mv blinky-dome-some-name-like-2019-bm.jar /etc/starcats/icosastar/blinky-dome-2019.jar
```

Finally, run the JAR

```sh
stratocat:~$ sudo service sc-icosa-jar start

# wait a few sec, make sure it's running
stratocat:~$ ps aux | grep java
```

Takes up to 30-60 sec for it to start outputting blinkyness out across the network.
