# my-app

Hold the button for more than 1 second and the on-board RGB LED 3 blinks
green twice (200 ms on / 200 ms off). It fires once per press: release the
button before the next long press. All logic is in `sketch/sketch.ino`.

Deploy from the `projects/` directory: `./deploy.sh my-app`

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
4. Power the board, run `./deploy.sh my-app`, hold the button for 1 s.

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
