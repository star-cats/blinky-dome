# starcats init.d scripts
--------

These scripts start up all services needed to make stuff run on the ODROID.  Should also work on a raspi.

To install them:
- Check the scripts and make sure all apps are installed and in expected locations (eg `Xvfb`, jars in `/etc/starcats/`, etc.)
- `chmod +x sc-*`
- `sudo cp sc-* /etc/init.d`
- For each, `sudo touch /var/log/fc-XXX.log && sudo chown starcat /var/log/fc-XXX.log` (filename is per the `LOGFILE` in each script)
- For each, `sudo update-rc.d <SCRIPT NAME> defaults <START> <END>`
  - where `<SCRIPT NAME>` is `sc-blinky-jar` or whatever
  - `<START>` is start order:
    - Xvfb, pulseaudio: 90
    - fcserver: 91
    - blinky-jar: 92
  - `<END>` is `100 - <START>`.  eg 10 for Xvfb, 9 for fcserver, etc.
- To test, run `sudo service fc-XXX start`.  Look for proper output in `/var/log/syslog`

## Debugging
- Look for outputs in `/var/log/syslog` (try `tail -f`'ing it)
- `sudo service fc-XXX start` / `sudo service fc-XXX end` to start/stop 

## Reference
- Script templates came from here: [https://gist.github.com/naholyr/4275302](https://gist.github.com/naholyr/4275302)
- [https://help.ubuntu.com/community/UbuntuBootupHowto](https://help.ubuntu.com/community/UbuntuBootupHowto)
