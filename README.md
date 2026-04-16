# ConnectLife API / MQTT integration

[aarch64-shield]: https://img.shields.io/badge/aarch64-yes-green.svg
[amd64-shield]: https://img.shields.io/badge/amd64-yes-green.svg
[armv6-shield]: https://img.shields.io/badge/armv6-yes-green.svg
[armv7-shield]: https://img.shields.io/badge/armv7-yes-green.svg
[i386-shield]: https://img.shields.io/badge/i386-yes-green.svg
![aarch64-shield]
![amd64-shield]
![armv6-shield]
![armv7-shield]
![i386-shield]

Polls the ConnectLife cloud API (reverse-engineered from the
[ConnectLife mobile app](https://en.connectlife.io)) and bridges device
state to an MQTT broker. Supports Home Assistant auto-discovery and
includes a native Hubitat driver.

Originally forked from [bilan/connectlife-api-connector](https://github.com/bilan/connectlife-api-connector).

---

## How it works

```
Hisense/ConnectLife device
         │  WiFi
  ConnectLife Cloud API
         │
  connectlife-api-connector  (Docker container)
         │  MQTT
      MQTT broker
         │
  Home Assistant  /  Hubitat
```

The container polls the ConnectLife API every 60 seconds and publishes
device state as **retained** MQTT messages, so any subscriber receives
current state immediately on connect.

---

## Run as standalone Docker container

This fork defaults to MQTT-only mode — no Home Assistant Supervisor required.

#### Pull from Docker Hub
```bash
docker pull benfugate/connectlife-api-connector:latest
```

#### Run (MQTT mode)
```bash
docker run -d --restart unless-stopped \
  -e CONNECTLIFE_LOGIN=your@email.com \
  -e CONNECTLIFE_PASSWORD=your-password \
  -e MQTT_HOST=your-mqtt-host \
  -e MQTT_PORT=1883 \
  -e MQTT_SSL=false \
  -e LOG_LEVEL=info \
  benfugate/connectlife-api-connector:latest
```

#### Build locally
```bash
docker build . --build-arg='BUILD_FROM=alpine:3.20' -t connectlife-api-connector
```

#### Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `CONNECTLIFE_LOGIN` | Yes | ConnectLife account email |
| `CONNECTLIFE_PASSWORD` | Yes | ConnectLife account password |
| `MQTT_HOST` | Yes | MQTT broker hostname or IP |
| `MQTT_PORT` | No | MQTT broker port (default: 1883) |
| `MQTT_USER` | No | MQTT username |
| `MQTT_PASSWORD` | No | MQTT password |
| `MQTT_SSL` | No | Enable TLS (`true`/`false`, default: false) |
| `LOG_LEVEL` | No | `debug`, `info`, `warning` (default: info) |
| `DEVICES_CONFIG` | No | JSON device feature overrides (see below) |
| `BEEPING` | No | AC beep on command (`0`/`1`, default: 0) |

> **Note:** ConnectLife accounts using Google/social login require a
> native password to be set. Use "Forgot Password" on the login screen
> to add one.

#### DEVICES_CONFIG

Optional per-device mode/fan/swing overrides keyed by `deviceFeatureCode`.
If omitted, the default split AC config is used (feature code 117).

```bash
-e DEVICES_CONFIG='{"117":{"t_work_mode":["fan only","heat","cool","dry","auto"],"t_fan_speed":{"0":"auto","5":"super low","6":"low","7":"medium","8":"high","9":"super high"}}}'
```

---

## MQTT topics

All topics are prefixed with the device `puid` (e.g. `pu000068abc.../ac/...`).
State topics are published as **retained messages**.

| Topic | Direction | Payload |
|-------|-----------|---------|
| `{id}/ac/mode/get` | connector → broker | `off`, `cool`, `heat`, `auto`, `fan_only`, `dry` |
| `{id}/ac/temperature/get` | connector → broker | integer (target temp) |
| `{id}/ac/current-temperature/get` | connector → broker | integer (room temp) |
| `{id}/ac/fan/get` | connector → broker | `auto`, `low`, `medium`, `high`, etc. |
| `{id}/ac/attributes/get` | connector → broker | JSON of raw device statusList |
| `{id}/ac/mode/set` | broker → connector | same values as mode/get |
| `{id}/ac/temperature/set` | broker → connector | integer string |
| `{id}/ac/fan/set` | broker → connector | fan speed string |
| `{id}/ac/power/set` | broker → connector | `1` (on) or `0` (off) |

To find your device `puid`, check the container logs on startup or run
the HTTP API (see below) and look for the `puid` field.

---

## Hubitat integration

A native Hubitat driver is included at [`hubitat/ConnectLifeAC.groovy`](hubitat/ConnectLifeAC.groovy).

### Setup

1. In Hubitat, go to **Drivers Code → New Driver**, paste the contents of
   `hubitat/ConnectLifeAC.groovy`, and click **Save**.
2. Go to **Devices → Add Device → Virtual**, select driver **"ConnectLife AC"**.
3. Open the device and fill in **Preferences**:

| Preference | Value |
|------------|-------|
| MQTT Broker URL | `tcp://your-broker-host:1883` |
| MQTT Client ID | Any unique string (e.g. `hubitat-connectlife`) |
| MQTT Username | Leave blank if no auth |
| MQTT Password | Leave blank if no auth |
| ConnectLife Device ID | Your device `puid` from the container logs |
| Temperature Unit | `F` or `C` |

4. Click **Save Preferences**. The driver connects, subscribes, and
   immediately receives current state from retained messages.

### Capabilities

- `Thermostat` — mode, setpoint, operating state
- `ThermostatCoolingSetpoint`
- `ThermostatMode` — `off`, `cool`, `auto`, `fan_only`, `dry`
- `ThermostatFanMode` — `auto`, `super_low`, `low`, `medium`, `high`, `super_high`
- `TemperatureMeasurement` — current room temperature
- `Refresh` — re-subscribes to pull latest retained state immediately

---

## Home Assistant integration

This image publishes Home Assistant MQTT discovery messages automatically.
Add it via the [bilan/home-assistant-addons](https://github.com/bilan/home-assistant-addons/)
repository in HA Supervisor, or run standalone and point it at your HA MQTT broker.

---

## HTTP API

Run with `-p 8000:8000` and `DISABLE_HTTP_API` unset (or `false`) to also
expose a REST API.

- `GET /api/devices` — list all devices and current status

  ```bash
  curl http://your-host:8000/api/devices
  ```

- `POST /api/devices/{puid}` — update device properties directly

  ```bash
  curl http://your-host:8000/api/devices/pu12345 \
    -d '{"t_temp":72}' \
    -H "Content-Type: application/json"
  ```

---

## Air conditioner properties

> Values documented for split AC (`deviceFeatureCode` 117, `deviceTypeCode` 009).
> Portable AC units may differ.

| Property | Description | Type | Values |
|----------|-------------|------|--------|
| `t_power` | Power | uint | `0` off, `1` on |
| `t_temp` | Target temperature | uint | e.g. `72` |
| `t_temp_type` | Temperature unit | string | `"0"` Fahrenheit, `"1"` Celsius |
| `t_work_mode` | Mode | uint | see below |
| `t_fan_speed` | Fan speed | uint | see below |
| `t_swing_direction` | Horizontal swing | uint | see below |
| `t_swing_angle` | Vertical swing | uint | see below |
| `t_beep` | Beep on command | uint | `0`, `1` |
| `t_fan_mute` | Silence mode | uint | `0`, `1` |
| `t_super` | Fast mode | uint | `0`, `1` |
| `t_eco` | Eco mode | uint | `0`, `1` |

`t_work_mode`: `0` fan only · `1` heat · `2` cool · `3` dry · `4` auto

`t_fan_speed`: `0` auto · `5` super low · `6` low · `7` medium · `8` high · `9` super high

`t_swing_direction`: `0` straight · `1` right · `2` both sides · `3` swing · `4` left

`t_swing_angle`: `0` swing · `2`–`7` bottom to top

---

## Useful links

- https://api.connectlife.io/swagger/index.html
- https://www.home-assistant.io/integrations/mqtt/#mqtt-discovery
- https://www.home-assistant.io/integrations/climate.mqtt/
