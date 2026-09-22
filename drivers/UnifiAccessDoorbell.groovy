metadata {
    definition(name: "UniFi Access Doorbell", namespace: "georgeb", author: "George B") {
        capability "PushableButton"
        capability "Sensor"
        capability "Refresh"
        attribute "healthStatus", "string"
        attribute "doorbellStatus", "string"
        attribute "lastRingAt", "string"
        attribute "lastDoorbellEvent", "string"
    }
}

def installed() {
    sendEvent(name: "numberOfButtons", value: 1)
}

def refresh() {
    parent.refreshDoorbell(device.deviceNetworkId)
}

def push(buttonNumber) {
    log.warn "UniFi Access physical doorbell events cannot be simulated."
}

def updateDevice(Map accessDevice) {
    sendEvent(name: "healthStatus", value: accessDevice.online == true ? "online" : accessDevice.online == false ? "offline" : "unverified")
}

def recordDoorbellEvent(Map event) {
    def eventName = event.event instanceof String ? event.event : "unknown"
    sendEvent(name: "lastDoorbellEvent", value: eventName)
    if (eventName in ["access.remote_view", "access.doorbell.incoming", "access.doorbell.incoming.REN"]) {
        sendEvent(name: "doorbellStatus", value: "ringing")
        sendEvent(name: "lastRingAt", value: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"))
        sendEvent(name: "pushed", value: 1, isStateChange: true)
    } else if (eventName in ["access.remote_view.change", "access.doorbell.completed"]) {
        sendEvent(name: "doorbellStatus", value: "completed")
    }
}

def markStale() {
    sendEvent(name: "healthStatus", value: "stale")
}

def markOffline() {
    sendEvent(name: "healthStatus", value: "offline")
}
