metadata {
    definition(name: "UniFi Access Request To Enter", namespace: "georgeb", author: "George B") {
        capability "PushableButton"
        capability "Sensor"
        capability "Refresh"
        attribute "healthStatus", "string"
        attribute "lastRingAt", "string"
    }
}

def installed() {
    sendEvent(name: "numberOfButtons", value: 1)
}

def refresh() {
    parent.refreshRequestToEnter(device.deviceNetworkId)
}

def push(buttonNumber) {
    log.warn "UniFi Access request-to-enter events cannot be simulated."
}

def setDoorHealth(String status) {
    sendEvent(name: "healthStatus", value: status in ["online", "offline", "stale", "unverified"] ? status : "unverified")
}

def recordRenEvent() {
    sendEvent(name: "lastRingAt", value: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"))
    sendEvent(name: "pushed", value: 1, isStateChange: true)
}

def markStale() {
    sendEvent(name: "healthStatus", value: "stale")
}

def markOffline() {
    sendEvent(name: "healthStatus", value: "offline")
}
