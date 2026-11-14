# grabber: developer guide

Hold the button for more than 1 second and the on-board RGB LED 3 blinks
green twice (200 ms on / 200 ms off). It fires once per press: release the
button before the next long press. On each long press the sketch
(`sketch/sketch.ino`) also notifies the Linux side (`python/main.py`), which
takes a photo with the USB camera, classifies it on the board and, when the
most confident label reaches 10 %, sends a `pick` event to the API
([contract](../contract/README.md)). It prints both outcomes.

The blink does not depend on Linux: it comes at once, even offline or
without a camera. Only the log tells what the request and the photo gave.

Deploy from the `projects/` directory:

- over USB: `./deploy.sh grabber`
- over Wi-Fi (needed once the camera hub occupies the USB port):
  `BOARD=arduino@<board-ip> ./deploy.sh grabber`

## Wiring the button (Arduino UNO Q)

Parts: one 4-pin tactile push button and two jumper wires. No resistor is
needed, the sketch enables the internal pull-up on D2.

| Button leg                     | UNO Q pin | Header                         |
| ------------------------------ | --------- | ------------------------------ |
| any leg                        | `D2`      | JDIGITAL, pin 3 (MCU pin PB3)  |
| the diagonally opposite leg    | `GND`     | JDIGITAL, pin 15 (next to AREF) |

Any other `GND` pin works too (e.g. the two on the JANALOG power header).

A 4-pin button is really a 2-contact switch: its legs are joined in pairs
inside the body, and pressing connects the two pairs. Two diagonally
opposite legs always belong to different pairs, so using a diagonal works
whichever way the button is turned.

Safety notes:

- Unplug the board while wiring.
- The headers are 3.3 V logic. Connect the button only to `D2` and `GND`;
  never to `+5V` or `VIN`.
- `GND` on JDIGITAL sits right next to `AREF`: check the silkscreen label
  before plugging in.

### Step 1: with a breadboard

1. Push the button across the centre gap of the breadboard so that two legs
   sit on each side of the gap.
2. Run a male-to-male jumper from the row of one leg to `D2`.
3. Run a male-to-male jumper from the row of the diagonally opposite leg
   (other side of the gap, other end of the button) to `GND`.
4. Power the board, run `./deploy.sh grabber`, hold the button for 1 s.

### Step 2: without a breadboard

Once step 1 works, replace the breadboard with two female-to-male jumpers:

1. Push the female ends onto two diagonally opposite legs of the button
   (straighten the legs a little if they do not grip).
2. Plug the male ends into `D2` and `GND`.

A loose contact looks like a release and restarts the 1 s hold, so if the
blink does not come, squeeze the connectors tighter or go back to step 1.

### Troubleshooting

- LED blinks twice about 1 s after start-up without pressing, then never
  again: you picked two legs of the same pair. They are permanently joined,
  so D2 is tied to GND and looks like a button that is never released. Use
  the diagonal.
- Nothing happens at all: a wire is loose or in the wrong socket, so D2
  never reaches GND. Re-check both ends against the silkscreen labels.
- LED is on all the time and goes *off* twice on a long press: the LED
  polarity is inverted on your core version; swap `LOW`/`HIGH` in
  `set_led()` in the sketch.

## Connecting the board to Wi-Fi

Wi-Fi belongs to the Linux side of the UNO Q and is remembered by the OS, so
this is done once per network, not by the app. Open a shell on the board
(`adb shell` on the host) and run:

```sh
nmcli device wifi list                           # is your network visible?
sudo nmcli --ask device wifi connect "MY_SSID"   # prompts for the password
```

`--ask` keeps the password out of the shell history. The board reconnects
by itself after a reboot. Check the result:

```sh
nmcli device status           # the wifi line should say "connected"
curl -I https://httpbin.org/get   # first line should end in 200
```

Wrong password or network? `sudo nmcli connection delete "MY_SSID"` and
connect again.

## Connecting the camera

The UNO Q has a single USB-C port, so a USB webcam goes through a USB-C hub
that has its own power input:

1. Plug a 5 V / 3 A USB-C charger into the hub's power (PD) port.
2. Plug the webcam into one of the hub's USB-A ports.
3. Plug the hub's USB-C cable into the board. The hub now powers the board.

The laptop is no longer connected by cable, so reach the board over Wi-Fi.
Find its address while it is still on USB (`adb shell hostname -I`), then:

```sh
ssh arduino@<board-ip> "ls /dev/video*"     # the webcam shows up as /dev/video0
BOARD=arduino@<board-ip> ./deploy.sh grabber
```

Plug the camera in before starting the app: the app opens it at start-up
and stops with a camera error if there is none.

The first deploy takes longer, because the board downloads the image
classification service (the `arduino:image_classification` Brick in
`app.yaml`); this needs the Wi-Fi connection from the previous section.

The model is the Brick's stock one: MobileNetV2 trained on ImageNet, 1000
everyday labels. It knows e.g. `water bottle`, `pop bottle` and `carton`,
but has no drink-can or paper class; those need a custom Edge Impulse model.

## Watching the results

On the host, follow the Python side's output:

```sh
adb shell "docker logs -f grabber-main-1"            # over USB
ssh arduino@<board-ip> "docker logs -f grabber-main-1"   # over Wi-Fi
```

Each long press prints two lines, each starting with the board's date and
time (the same clock as in the photo names):

```
2026-11-14 15:30:12 long press 1: camera sees -> water bottle 71 %, carton 12 % (0.9 s) [photos/20261114-153012-press1.jpg]
2026-11-14 15:30:13 long press 1: pick of water bottle -> OK, accepted (0.5 s)
2026-11-14 15:31:40 long press 2: camera sees -> nothing above 10 % (0.9 s) [photos/20261114-153140-press2.jpg]
2026-11-14 15:31:40 long press 2: no pick sent, nothing above 10 %
2026-11-14 15:32:05 long press 3: camera sees -> pop bottle 64 % (0.9 s) [photos/20261114-153205-press3.jpg]
2026-11-14 15:32:09 long press 3: pick of pop bottle -> FAILED, no answer before the deadline (4.1 s)
2026-11-14 15:32:11 long press 4: DROPPED, waited more than 5 s for the previous one
```

A long press gets 5 s from the moment it reaches Linux. The request is
cut off when they run out, and a press that waited the whole 5 s behind an
earlier one is dropped without a photo or a request.

A pick is sent only when the most confident label reaches 10 %
(`_PICK_CONFIDENCE` in `python/main.py`). The event is the contract's
`pick`: its `t` is the moment the press reached Linux, and it carries no
label, as the contract has no field for one. A pick that fails is not
resent.

The camera line lists the model's three most confident labels. `nothing
above 10 %` means it recognised nothing; `FAILED` means the camera gave no
picture or the classification service did not answer.

### Inspecting the photos

Every photo that was classified is saved on the board in the app folder,
under the name shown in brackets on its log line (date-time and press
number). Copy them to the host to look at them:

```sh
scp -r arduino@<board-ip>:ArduinoApps/grabber/photos ./photos
```

Copy them before the next deploy: `deploy.sh` replaces the whole app folder
on the board, photos included. Nothing else deletes them, so clear the
folder yourself after a long session.

On the pick line, `OK, accepted` means the API stored the event,
`REJECTED` shows the API's reason, and `FAILED` shows why there was no
answer (no Wi-Fi, DNS, the 5 s running out, or an HTTP error status). The counter starts again at 1 whenever the sketch restarts.
