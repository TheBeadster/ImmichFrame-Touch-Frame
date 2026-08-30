# Android helper

This is the final helper used on the X88. It bridges the composer to the RP2350 over USB, approves the exact controller USB prompt, keeps ImmichFrame in front, reports MQTT state and recovers both displays after standby.

Before building, edit these two files:

- `ControllerBridgeService.java`: Docker host address.
- `MqttPowerClient.java`: broker, client name and topics.

Build with Android Studio or `gradlew assembleDebug`, install the APK with ADB, then grant the appliance permissions:

```sh
adb install -r app-debug.apk
adb shell pm grant uk.co.manor.immichframe.controller android.permission.WRITE_SECURE_SETTINGS
adb shell appops set uk.co.manor.immichframe.controller GET_USAGE_STATS allow
adb shell appops set uk.co.manor.immichframe.controller SYSTEM_ALERT_WINDOW allow
adb shell cmd deviceidle whitelist +uk.co.manor.immichframe.controller
```

In Android Accessibility settings, enable **Frame Controller Helper**. Open ImmichFrame, set its Server URL to `http://DOCKER-HOST:8081/frame/portrait_three`, leave Authorization Secret blank, and make ImmichFrame the Home app.

MQTT uses retained QoS 1 messages. Send exact `ON` or `OFF`; state is `ON`, `OFF`, `STARTING` or `ERROR`, and availability is `online` or `offline`.


