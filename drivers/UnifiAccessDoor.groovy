metadata {
    definition(name: "UniFi Access Door", namespace: "georgeb", author: "George B") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "ContactSensor"
        capability "Lock"
        command "keepLocked"
        command "keepUnlocked"
        command "unlockForMinutes", [[name: "Minutes", type: "NUMBER"]]
        command "resetTemporaryRule"
        command "endUnlockScheduleEarly"
        command "lockNow"
        command "gateOpen"
        command "gateClose"
        command "gateStop"
        command "gateOpenIn"
        command "gateOpenOut"
        attribute "lockRelay", "string"
        attribute "doorPosition", "string"
        attribute "healthStatus", "string"
        attribute "hubBound", "string"
        attribute "lastSuccessfulPoll", "string"
        attribute "lastCommandStatus", "string"
        attribute "lastCommandAction", "string"
        attribute "lockRuleStatus", "string"
        attribute "lockRule", "string"
        attribute "lockRuleEndsAt", "number"
    }
}

def refresh() {
    parent.refreshDoor(device.deviceNetworkId)
}

def unlock() {
    parent.unlockDoor(device.deviceNetworkId)
}

def lock() {
    lockNow()
}

def keepLocked() {
    parent.setDoorRule(device.deviceNetworkId, "keep_lock", null)
}

def keepUnlocked() {
    parent.setDoorRule(device.deviceNetworkId, "keep_unlock", null)
}

def unlockForMinutes(minutes) {
    parent.setDoorRule(device.deviceNetworkId, "custom", minutes)
}

def resetTemporaryRule() {
    parent.setDoorRule(device.deviceNetworkId, "reset", null)
}

def endUnlockScheduleEarly() {
    parent.setDoorRule(device.deviceNetworkId, "lock_early", null)
}

def lockNow() {
    parent.setDoorRule(device.deviceNetworkId, "lock_now", null)
}

def gateOpen() {
    parent.gateCommand(device.deviceNetworkId, "open", null)
}

def gateClose() {
    parent.gateCommand(device.deviceNetworkId, "close", null)
}

def gateStop() {
    parent.gateCommand(device.deviceNetworkId, "stop", null)
}

def gateOpenIn() {
    parent.gateCommand(device.deviceNetworkId, null, "in")
}

def gateOpenOut() {
    parent.gateCommand(device.deviceNetworkId, null, "out")
}

def setCommandStatus(String status) {
    sendEvent(name: "lastCommandStatus", value: status)
}

def setCommandAction(String action) {
    sendEvent(name: "lastCommandAction", value: action)
}

def updateLockRule(Map rule) {
    sendEvent(name: "lockRuleStatus", value: "available")
    sendEvent(name: "lockRule", value: rule.type?.trim() ?: "normal")
    if (rule.ended_time instanceof Number) {
        sendEvent(name: "lockRuleEndsAt", value: rule.ended_time)
    }
}

def setLockRuleStatus(String status) {
    sendEvent(name: "lockRuleStatus", value: status)
    if (status != "available") {
        sendEvent(name: "lockRule", value: "unknown")
    }
}

def updateDoor(Map door) {
    def bound = door.isBindHub == true
    def relay = ["lock", "unlock"].contains(door.lockRelay) ? door.lockRelay : "unknown"
    def position = door.position instanceof String && door.position ? door.position : "unknown"
    sendEvent(name: "hubBound", value: bound ? "yes" : "no")
    sendEvent(name: "lastSuccessfulPoll", value: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"))
    setHardwareHealth(door.hardwareHealth ?: "unverified", relay, position)
}

def markStale() {
    setHardwareHealth("stale", "unknown", "unknown")
}

def markOffline() {
    setHardwareHealth("offline", "unknown", "unknown")
}

def setHardwareHealth(String status, String relay, String position) {
    def relayValue = ["lock", "unlock"].contains(relay) ? relay : "unknown"
    def positionValue = position ?: "unknown"
    sendEvent(name: "healthStatus", value: status)
    sendEvent(name: "lockRelay", value: status == "online" ? relayValue : "unknown")
    sendEvent(name: "lock", value: status == "online" && relayValue != "unknown" ?
        relayValue == "lock" ? "locked" : "unlocked" : "unknown")
    sendEvent(name: "doorPosition", value: status == "online" ? positionValue : "unknown")
    if (status == "online" && ["open", "close"].contains(positionValue)) {
        sendEvent(name: "contact", value: positionValue == "open" ? "open" : "closed")
    }
}

def applyDpsPosition(String position) {
    if (position in ["open", "close"]) {
        sendEvent(name: "doorPosition", value: position)
        sendEvent(name: "contact", value: position == "open" ? "open" : "closed")
    }
}
