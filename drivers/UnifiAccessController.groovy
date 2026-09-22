metadata {
    definition(name: "UniFi Access Controller", namespace: "georgeb", author: "George B") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        command "startLockdown"
        command "startEvacuation"
        command "clearEmergency"
        attribute "healthStatus", "string"
        attribute "lockdown", "string"
        attribute "evacuation", "string"
        attribute "lastSuccessfulPoll", "string"
        attribute "lastCommandStatus", "string"
        attribute "lastCommandAction", "string"
    }
}

def refresh() {
    parent.refreshController(device.deviceNetworkId)
}

def startLockdown() {
    parent.setEmergency("lockdown")
}

def startEvacuation() {
    parent.setEmergency("evacuation")
}

def clearEmergency() {
    parent.setEmergency("clear")
}

def updateEmergency(Map emergency) {
    sendEvent(name: "healthStatus", value: "online")
    sendEvent(name: "lockdown", value: emergency.lockdown == true ? "active" : "inactive")
    sendEvent(name: "evacuation", value: emergency.evacuation == true ? "active" : "inactive")
    sendEvent(name: "lastSuccessfulPoll", value: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"))
}

def setEmergencyStatus(String status) {
    sendEvent(name: "healthStatus", value: status)
}

def setCommandStatus(String status) {
    sendEvent(name: "lastCommandStatus", value: status)
}

def setCommandAction(String action) {
    sendEvent(name: "lastCommandAction", value: action)
}

def markStale() {
    sendEvent(name: "healthStatus", value: "stale")
}

def markOffline() {
    sendEvent(name: "healthStatus", value: "offline")
}
