#include <Arduino_RouterBridge.h>

uint32_t count = 0;

void set_led(bool state) {
  digitalWrite(LED3_G, state ? HIGH : LOW);
}

void setup() {
  pinMode(LED3_G, OUTPUT);
  Bridge.begin();
  Bridge.provide("set_led", set_led);
  delay(5000);  // give the Python side time to start
}

void loop() {
  count++;
  Bridge.call("hello_from_mcu", count);
  delay(2000);
}
