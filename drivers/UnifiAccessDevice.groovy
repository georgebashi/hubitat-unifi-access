metadata {
    definition(name: "UniFi Access Device", namespace: "georgeb", author: "George B") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        command "setAccessMethod", [[name: "Method", type: "STRING"], [name: "Enabled", type: "STRING"]]
        command "setPinShuffle", [[name: "Enabled", type: "STRING"]]
        command "setFaceSensitivity", [[name: "Anti-spoofing", type: "STRING"], [name: "Distance", type: "STRING"]]
        command "ringDoorbell", [[name: "Intercom room name", type: "STRING"]]
        command "cancelDoorbell"
        attribute "healthStatus", "string"
        attribute "deviceType", "string"
        attribute "adopted", "string"
        attribute "connected", "string"
        attribute "lastSuccessfulPoll", "string"
        attribute "accessMethodsStatus", "string"
        attribute "accessMethods", "string"
        attribute "doorbellEventsStatus", "string"
        attribute "reportedCapabilities", "string"
        attribute "doorbellStatus", "string"
        attribute "lastDoorbellEvent", "string"
        attribute "lastCommandStatus", "string"
        attribute "lastCommandAction", "string"
    }
}

def refresh() {
    parent.refreshDevice(device.deviceNetworkId)
}

def setAccessMethod(String method, String enabled) {
    parent.setAccessMethod(device.deviceNetworkId, method, enabled)
}

def setPinShuffle(String enabled) {
    parent.setPinShuffle(device.deviceNetworkId, enabled)
}

def setFaceSensitivity(String antiSpoofing, String distance) {
    parent.setFaceSensitivity(device.deviceNetworkId, antiSpoofing, distance)
}

def ringDoorbell(String roomName = "") {
    parent.triggerDoorbell(device.deviceNetworkId, roomName)
}

def cancelDoorbell() {
    parent.cancelDoorbell(device.deviceNetworkId)
}

def updateDevice(Map accessDevice) {
    sendEvent(name: "healthStatus", value: accessDevice.online == true ? "online" : accessDevice.online == false ? "offline" : "unverified")
    sendEvent(name: "deviceType", value: accessDevice.type ?: "unknown")
    sendEvent(name: "reportedCapabilities", value: groovy.json.JsonOutput.toJson(accessDevice.reportedCapabilities ?: []))
    sendEvent(name: "adopted", value: booleanStatus(accessDevice.adopted))
    sendEvent(name: "connected", value: booleanStatus(accessDevice.connected))
    sendEvent(name: "lastSuccessfulPoll", value: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"))
}

def markStale() {
    sendEvent(name: "healthStatus", value: "stale")
}

def markOffline() {
    sendEvent(name: "healthStatus", value: "offline")
}

def updateAccessMethods(Map methods) {
    def supported = methods && !methods.isEmpty()
    sendEvent(name: "accessMethodsStatus", value: supported ? "available" : "unsupported")
    def sanitized = methods.collectEntries { key, value ->
        [(key): value instanceof Map ? value.findAll { field, setting -> field in ["enabled", "pin_code_shuffle", "anti_spoofing_level", "detect_distance"] } : [:]]
    }
    sendEvent(name: "accessMethods", value: groovy.json.JsonOutput.toJson(sanitized))
}

def setAccessMethodsStatus(String status) {
    sendEvent(name: "accessMethodsStatus", value: status)
}

def setDoorbellEventsStatus(String status) {
    sendEvent(name: "doorbellEventsStatus", value: status == "detected" ? "detected" : "unknown")
}

def setCommandStatus(String status) {
    sendEvent(name: "lastCommandStatus", value: status)
}

def setCommandAction(String action) {
    sendEvent(name: "lastCommandAction", value: action)
}

def recordDoorbellEvent(Map event) {
    def name = event.event instanceof String ? event.event : "unknown"
    sendEvent(name: "lastDoorbellEvent", value: name)
    sendEvent(name: "doorbellStatus", value: name.contains("incoming") || name == "access.remote_view" ? "ringing" : "completed")
}

private String booleanStatus(value) {
    value == true ? "yes" : value == false ? "no" : "unknown"
}
