from arduino.app_utils import App

# All behaviour lives in the sketch; the Linux side only keeps the app alive.
print("Hello from Linux!")
App.run()
