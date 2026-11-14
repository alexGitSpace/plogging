// Long-press demo: holding the button on D2 for 1 s blinks LED 3 green twice
// and tells the Linux side, which makes a web request (see ../python/main.py).
// Wiring is described in ../README.md.

#include <Arduino_RouterBridge.h>

const int BUTTON_PIN = 2;        // D2 (PB3) on JDIGITAL, button to GND
const uint32_t HOLD_MS = 1000;   // continuous press needed to trigger
const uint32_t BLINK_MS = 200;   // on time and off time of each blink
const uint32_t POLL_MS = 10;     // button sampling period

bool held = false;        // button was down on the previous sample
bool fired = false;       // this press has already triggered the blink
uint32_t held_since = 0;  // millis() when the current press started
uint32_t press_count = 0; // long presses since boot, sent to Linux for the log

// RGB LED 3 is active-low, so hide the inversion in one place.
void set_led(bool on) {
  digitalWrite(LED3_G, on ? LOW : HIGH);
}

// The user-visible confirmation that a long press was recognised.
void blink_twice() {
  for (int i = 0; i < 2; i++) {
    set_led(true);
    delay(BLINK_MS);
    set_led(false);
    delay(BLINK_MS);
  }
}

void setup() {
  pinMode(LED3_G, OUTPUT);
  set_led(false);
  // Internal pull-up: the pin reads HIGH when released, LOW when pressed.
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  Bridge.begin();
}

void loop() {
  bool pressed = digitalRead(BUTTON_PIN) == LOW;
  if (!pressed) {
    // Any release restarts the hold and re-arms the trigger.
    held = false;
    fired = false;
  } else if (!held) {
    held = true;
    held_since = millis();
  } else if (!fired && millis() - held_since >= HOLD_MS) {
    fired = true;  // once per press: release before it can fire again
    // notify = fire and forget: the blink never waits for Linux or the network.
    Bridge.notify("long_press", ++press_count);
    blink_twice();
  }
  delay(POLL_MS);
}
