# ImmichFrame Touch Frame

A portrait Immich photo frame with one big image, two smaller ones and a USB touch controller. It boots back into the frame after a power cut, can sleep over MQTT, and can send the selected photo over Reticulum.

```text
X88 Android -> Frame Composer -> ImmichFrame -> Immich
          \-> USB RP2350 controller
          \-> Reticulum/LXMF sender
```

## Hardware

- X88 Pro 13 Android TV box
- Portrait HDMI IPS screen
- Waveshare RP2350-Touch-LCD-2.8 (SKU 30706)
- A proper regulated 5V/4A barrel supply
- 5.5 x 2.1 mm DC splitter
- Right-angle 5.5 x 2.1 mm female to USB-C male adapter for the screen

The splitter is passive: the supply must be **5V**, centre-positive. Do not feed 12V into a USB-C screen just because a splitter advert calls the cable “12V”.

## 1. Docker host

Install Docker Compose on the same LAN as Immich. Clone this repo, copy `.env.example` to `.env`, and set `HOST_IP` to the Docker host's LAN address.

The original build runs both Immich and this frame stack in Docker on a Windows 11 PC. A Linux Docker host works too; the important bit is that the X88 can reach ports 2283, 8080 and 8081 over the LAN.

Copy `config/Settings.example.yml` to `config/Settings.yml`. Set the Immich URL and paste an API key made by the Immich account whose photos will be shown. In Immich: profile picture -> **Account Settings** -> **API Keys**. The current ImmichFrame permissions are:

`album.read`, `album.statistics`, `asset.view`, `asset.read`, `asset.statistics`, `face.read`, `memory.read`, `person.read`, `person.statistics`, `tag.read`.

Then start it:

```sh
docker compose up -d --build
docker compose ps
curl http://HOST-IP:8081/health
```

Keep ports 8080 and 8081 on the LAN. Do not forward them on the router.

## 2. X88 Android box

Install the official ImmichFrame Android APK. Its Server URL is:

```text
http://HOST-IP:8081/frame/portrait_three
```

Leave Authorization Secret blank. Make ImmichFrame the Home app. Build and install `controller/android-helper` only if you are fitting the RP2350 controller or want the MQTT appliance recovery.

## 3. Touch controller

Flash `controller/rp2350`, connect it to the X88 by USB, then enable the helper's accessibility service once. The final helper automatically handles the controller's USB permission prompt after cold boot.

## 4. Optional messaging and power

- `controller/android-reticulum` sends the frozen selected photo through LXMF. It works with [MeshChatX](https://github.com/Quad4-Software/MeshChatX), [Columba](https://github.com/torlando-tech/columba) and the [Reticulum stack](https://reticulum.network/manual/).
- MQTT topics and broker live in `MqttPowerClient.java`. The helper gives retained power and availability feedback as well as ON/OFF control.

## Frame tweaks

Edit `composer-config/frame.yml`, then reload the frame. Low-resolution photos are accepted only in the two small panels. Tag anything `ImmichFrame/Ignore` in Immich to keep it off the frame.

## Updates and backup

```sh
docker compose pull
docker compose up -d --build
```

Back up `.env`, `config/Settings.yml`, `composer-config/frame.yml`, the helper APK, the controller UF2, and the Reticulum identity. Keep API keys and identities out of Git.

Built from the setup described on [Beady's blog](https://www.beady.com/blog/keeping-swmbo-happy-1244555/). Upstream projects: [Immich](https://immich.app/), [ImmichFrame](https://immichframe.dev/), [MeshChatX](https://meshchatx.com/), [Columba](https://github.com/torlando-tech/columba) and [Reticulum](https://reticulum.network/).

