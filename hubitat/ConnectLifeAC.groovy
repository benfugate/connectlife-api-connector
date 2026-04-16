/**
 * ConnectLife AC - Hubitat Driver
 *
 * Controls a Hisense/ConnectLife portable or split AC unit via the
 * connectlife-api-connector MQTT bridge.
 *
 * Repo: https://github.com/benfugate/connectlife-api-connector
 *
 * MQTT topics (all prefixed with the device puid):
 *   State  (subscribe): {id}/ac/mode/get, temperature/get, current-temperature/get, fan/get
 *   Command (publish):  {id}/ac/mode/set, temperature/set, fan/set, power/set
 */
metadata {
    definition(
        name:        "ConnectLife AC",
        namespace:   "benfugate",
        author:      "Ben Fugate",
        description: "Hisense/ConnectLife AC via connectlife-api-connector MQTT bridge"
    ) {
        capability "Thermostat"
        capability "ThermostatCoolingSetpoint"
        capability "ThermostatMode"
        capability "ThermostatFanMode"
        capability "TemperatureMeasurement"
        capability "Initialize"
        capability "Refresh"

        // Extended modes beyond the Thermostat capability standard
        command "setThermostatMode", [[name: "mode", type: "ENUM",
            constraints: ["off", "cool", "auto", "fan_only", "dry"]]]
        command "setThermostatFanMode", [[name: "fanMode", type: "ENUM",
            constraints: ["auto", "super_low", "low", "medium", "high", "super_high"]]]
        command "reconnect"

        attribute "thermostatMode",           "string"
        attribute "thermostatFanMode",        "string"
        attribute "thermostatOperatingState", "string"
        attribute "thermostatSetpoint",       "number"
        attribute "coolingSetpoint",          "number"
        attribute "temperature",              "number"
    }

    preferences {
        input name: "mqttBroker",
              type: "text",
              title: "MQTT Broker URL",
              description: "e.g. tcp://192.168.29.120:1883  or  tcp://sfftime.lan:1883",
              required: true

        input name: "mqttClientId",
              type: "text",
              title: "MQTT Client ID",
              defaultValue: "hubitat-connectlife",
              required: true

        input name: "mqttUser",
              type: "text",
              title: "MQTT Username (leave blank if none)",
              required: false

        input name: "mqttPass",
              type: "password",
              title: "MQTT Password (leave blank if none)",
              required: false

        input name: "deviceId",
              type: "text",
              title: "ConnectLife Device ID (puid)",
              description: "Found in the container logs, starts with 'pu'",
              required: true

        input name: "tempUnit",
              type: "enum",
              title: "Temperature Unit",
              options: ["F", "C"],
              defaultValue: "F",
              required: true

        input name: "logEnable",
              type: "bool",
              title: "Enable debug logging",
              defaultValue: true
    }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────────

def installed() {
    log.info "${device.displayName}: installed"
    initialize()
}

def updated() {
    log.info "${device.displayName}: preferences updated, reconnecting"
    initialize()
}

def initialize() {
    log.info "${device.displayName}: connecting to ${settings.mqttBroker}"
    try { interfaces.mqtt.disconnect() } catch (e) { /* not connected yet */ }
    pauseExecution(1000)
    interfaces.mqtt.connect(
        settings.mqttBroker,
        settings.mqttClientId,
        settings.mqttUser ?: null,
        settings.mqttPass ?: null,
        lastWillTopic:   "${settings.deviceId}/ac/hubitat/status",
        lastWillMessage: "offline",
        lastWillRetain:  true
    )
}

def reconnect() {
    initialize()
}

def refresh() {
    if (logEnable) log.debug "${device.displayName}: refresh — resubscribing to pull retained state"
    subscribeTopics()
}

// ── Incoming MQTT ──────────────────────────────────────────────────────────────

def parse(String description) {
    def msg     = interfaces.mqtt.parseMessage(description)
    def topic   = msg.topic
    def payload = msg.payload
    if (logEnable) log.debug "${device.displayName}: MQTT ← [${topic}] ${payload}"

    def unit = settings.tempUnit == "F" ? "°F" : "°C"
    def id   = settings.deviceId

    switch (topic) {
        case "${id}/ac/mode/get":
            sendEvent(name: "thermostatMode",           value: payload)
            sendEvent(name: "thermostatOperatingState", value: operatingStateFor(payload))
            break

        case "${id}/ac/temperature/get":
            def t = payload.toFloat()
            sendEvent(name: "thermostatSetpoint", value: t, unit: unit)
            sendEvent(name: "coolingSetpoint",    value: t, unit: unit)
            break

        case "${id}/ac/current-temperature/get":
            sendEvent(name: "temperature", value: payload.toFloat(), unit: unit)
            break

        case "${id}/ac/fan/get":
            sendEvent(name: "thermostatFanMode", value: payload)
            break
    }
}

def mqttClientStatus(String message) {
    log.info "${device.displayName}: MQTT status — ${message}"
    if (message.startsWith("Status: Connection succeeded")) {
        subscribeTopics()
    } else if (message.startsWith("Error")) {
        log.error "${device.displayName}: MQTT error, retrying in 30s"
        runIn(30, initialize)
    }
}

private subscribeTopics() {
    def id = settings.deviceId
    ["mode", "temperature", "current-temperature", "fan"].each { prop ->
        interfaces.mqtt.subscribe("${id}/ac/${prop}/get")
        if (logEnable) log.debug "${device.displayName}: subscribed to ${id}/ac/${prop}/get"
    }
    log.info "${device.displayName}: ready — subscribed to all topics for ${id}"
}

// ── Commands ───────────────────────────────────────────────────────────────────

def setThermostatMode(String mode) {
    if (logEnable) log.debug "${device.displayName}: setThermostatMode(${mode})"
    publish("mode", mode)
}

def setCoolingSetpoint(temp) {
    if (logEnable) log.debug "${device.displayName}: setCoolingSetpoint(${temp})"
    publish("temperature", temp.toInteger().toString())
}

def setThermostatFanMode(String fanMode) {
    if (logEnable) log.debug "${device.displayName}: setThermostatFanMode(${fanMode})"
    publish("fan", fanMode)
}

// Thermostat capability convenience methods
def cool()          { setThermostatMode("cool") }
def auto()          { setThermostatMode("auto") }
def off()           { setThermostatMode("off") }
def fanAuto()       { setThermostatFanMode("auto") }
def fanOn()         { setThermostatFanMode("high") }
def fanCirculate()  { setThermostatFanMode("medium") }

// ── Helpers ────────────────────────────────────────────────────────────────────

private publish(String property, String value) {
    def topic = "${settings.deviceId}/ac/${property}/set"
    if (logEnable) log.debug "${device.displayName}: MQTT → [${topic}] ${value}"
    interfaces.mqtt.publish(topic, value)
}

private String operatingStateFor(String mode) {
    switch (mode) {
        case "cool":     return "cooling"
        case "fan_only": return "fan only"
        case "dry":      return "drying"
        default:         return "idle"
    }
}
