import groovy.json.JsonSlurper

metadata {
    definition(name: "UniFi Access Events", namespace: "georgeb", author: "George B") {
        capability "Sensor"
        attribute "connectionStatus", "string"
        attribute "lastEventAt", "string"
        attribute "lastEventType", "string"
    }
}

def configureAccessEvents() {
    def config = parent.accessEventsConnectionDetails()
    if (!validConfig(config)) {
        closeAccessEvents()
        setConnectionStatus("disabled")
        return
    }
    if (state.connectionWanted == true && state.configFingerprint == config.fingerprint && state.connected == true) {
        return
    }
    state.connectionWanted = true
    state.configFingerprint = config.fingerprint
    state.retryAttempt = 0
    state.reconfiguring = true
    state.connected = false
    state.pendingDoorbells = [:]
    unschedule("connectAccessEvents")
    try {
        interfaces.webSocket.close()
    } catch (Exception ignored) {
        state.connected = false
    }
    runIn(1, "connectAccessEvents")
}

def closeAccessEvents() {
    state.connectionWanted = false
    state.reconfiguring = true
    state.connected = false
    unschedule("connectAccessEvents")
    try {
        interfaces.webSocket.close()
    } catch (Exception ignored) {
        state.reconfiguring = false
    }
    state.pendingDoorbells = [:]
    setConnectionStatus("stopped")
}

def connectAccessEvents() {
    state.reconfiguring = false
    if (state.connectionWanted != true) {
        return
    }
    def config = parent.accessEventsConnectionDetails()
    if (!validConfig(config) || config.fingerprint != state.configFingerprint) {
        state.connectionWanted = false
        state.connected = false
        setConnectionStatus("disabled")
        return
    }
    def host = config.host.toString().trim()
    if (host.contains(":") && !host.startsWith("[")) {
        host = "[${host}]"
    }
    def url = "wss://${host}:${config.port}/api/v1/developer/devices/notifications"
    state.connected = false
    setConnectionStatus("connecting")
    try {
        interfaces.webSocket.connect(url,
            pingInterval: 30,
            headers: [Authorization: "Bearer ${config.token}"],
            ignoreSSLIssues: config.allowSelfSigned == true)
    } catch (Exception ignored) {
        setConnectionStatus("error")
        scheduleReconnect()
    }
}

def webSocketStatus(String status) {
    if (state.connectionWanted != true || state.reconfiguring == true) {
        return
    }
    if (status == "status: open") {
        def config = parent.accessEventsConnectionDetails()
        if (!validConfig(config) || config.fingerprint != state.configFingerprint) {
            configureAccessEvents()
            return
        }
        state.connected = true
        state.retryAttempt = 0
        setConnectionStatus("connected")
        return
    }
    state.connected = false
    setConnectionStatus(status?.startsWith("failure:") ? "error" : "disconnected")
    scheduleReconnect()
}

def parse(String rawMessage) {
    if (state.connectionWanted != true || state.reconfiguring == true || state.connected != true ||
        !rawMessage || rawMessage.length() > 65536) {
        return
    }
    def message
    try {
        message = new JsonSlurper().parseText(rawMessage)
    } catch (Exception ignored) {
        return
    }
    if (!(message instanceof Map) || !(message.event instanceof String) || !(message.data instanceof Map)) {
        return
    }
    def event
    switch (message.event) {
        case "access.remote_view":
            event = doorbellStarted(message.data)
            break
        case "access.remote_view.change":
            event = doorbellChanged(message.data)
            break
        default:
            return
    }
    if (!event) {
        return
    }
    event.fingerprint = state.configFingerprint
    sendEvent(name: "lastEventType", value: event.event)
    sendEvent(name: "lastEventAt", value: new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"))
    try {
        parent.notifyAccessEvent(event)
    } catch (Exception ignored) {
        return
    }
}

private Map doorbellStarted(Map payload) {
    def requestId = payload.request_id
    def deviceId = payload.device_id
    def deviceType = payload.device_type
    if (!(requestId instanceof String) || !requestId || !(deviceId instanceof String) || !deviceId ||
        !(deviceType instanceof String) || !deviceType) {
        return null
    }
    def pending = pendingDoorbells()
    def nowMs = now()
    pending = pending.findAll { id, record -> nowMs - (record.receivedAt as Long) < 3600000L }
    if (pending.containsKey(requestId)) {
        state.pendingDoorbells = pending
        return null
    }
    pending[requestId] = [deviceId: deviceId, receivedAt: nowMs]
    while (pending.size() > 64) {
        pending.remove(pending.keySet().first())
    }
    state.pendingDoorbells = pending
    [event: "access.remote_view",
     deviceId: deviceId,
     deviceType: deviceType,
     doorName: payload.door_name instanceof String ? payload.door_name : null,
     doorId: null,
     requestId: requestId,
     direction: payload.in_or_out instanceof String ? payload.in_or_out : null,
     eventTime: payload.create_time instanceof Number ? payload.create_time : null,
     reasonCode: null]
}

private Map doorbellChanged(Map payload) {
    def requestId = payload.remote_call_request_id
    def reasonCode = payload.reason_code
    if (!(requestId instanceof String) || !requestId || !(reasonCode instanceof Number) ||
        ![105, 106, 107, 108, 400].contains(reasonCode as Integer)) {
        return null
    }
    def pending = pendingDoorbells()
    def record = pending.remove(requestId)
    state.pendingDoorbells = pending
    if (!record) {
        return null
    }
    [event: "access.remote_view.change",
     deviceId: record.deviceId,
     deviceType: null,
     doorName: null,
     doorId: null,
     requestId: requestId,
     direction: null,
     eventTime: null,
     reasonCode: reasonCode as Integer]
}

private Map pendingDoorbells() {
    def records = state.pendingDoorbells
    records instanceof Map ? new LinkedHashMap(records) : [:]
}

private boolean validConfig(Map config) {
    if (!(config instanceof Map) || !(config.host instanceof String) || !config.host.trim() ||
        !(config.port instanceof Number) || (config.port as Integer) < 1 || (config.port as Integer) > 65535 ||
        !(config.token instanceof String) || !config.token || !(config.fingerprint instanceof String) || !config.fingerprint) {
        return false
    }
    config.host ==~ /(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:]+\])/
}

private void scheduleReconnect() {
    if (state.connectionWanted != true || state.reconfiguring == true) {
        return
    }
    def delays = [5, 15, 30, 60, 120, 300]
    def attempt = Math.min((state.retryAttempt ?: 0) as Integer, delays.size() - 1)
    state.retryAttempt = attempt + 1
    unschedule("connectAccessEvents")
    runIn(delays[attempt], "connectAccessEvents")
}

private void setConnectionStatus(String status) {
    if (device.currentValue("connectionStatus") != status) {
        sendEvent(name: "connectionStatus", value: status)
    }
}
