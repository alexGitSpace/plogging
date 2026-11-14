from arduino.app_utils import *
import time

led_state = False

def hello_from_mcu(count: int):
    print(f"Hello from the MCU! count={count}")

Bridge.provide("hello_from_mcu", hello_from_mcu)

def loop():
    global led_state
    time.sleep(1)
    led_state = not led_state
    Bridge.call("set_led", led_state)

print("Hello from Linux!")
App.run(user_loop=loop)
