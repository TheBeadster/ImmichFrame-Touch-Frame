# RP2350 touch controller

Tested on the Waveshare RP2350-Touch-LCD-2.8, SKU 30706.

1. Install the RP2040/RP2350 Arduino core and the Waveshare demo dependencies.
2. Put the files in this folder beside the matching Waveshare `GUI_Paint` and `CST328` drivers.
3. Build `ImmichFrameController.ino` for the Waveshare RP2350-Touch-LCD-2.8.
4. Hold BOOT while connecting USB, then copy the UF2 to the RPI-RP2 drive.

The included `DEV_Config` and `LCD_2in8` files are the tested Waveshare driver variant with LCD sleep/wake support. The Android helper talks to it over USB CDC. The controller exits any menu after 60 seconds, freezes the chosen photo before an action, and shows delivery confirmation after a send.


