definition(
    name: "UniFi Access",
    namespace: "georgeb",
    author: "George B",
    description: "Monitor and control UniFi Access hardware",
    category: "Convenience",
    menu: "Integrations",
    singleInstance: false,
    singleThreaded: true,
    oauth: true
)

preferences {
    page(name: "mainPage")
    page(name: "discoverPage")
}

mappings {
    path("/events") {
        action: [POST: "receiveAccessWebhook"]
    }
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "UniFi Access", install: true, uninstall: true) {
        section("Connection") {
            input "consoleHost", "text", title: "Console IP address or hostname", required: true
            input "apiPort", "number", title: "Access API port", required: true, defaultValue: 12445
            input "apiToken", "password", title: "Access API token", required: true
            input "allowSelfSigned", "bool", title: "Allow an untrusted console certificate", required: true, defaultValue: false
        }
        section("Doors") {
            href "discoverPage", title: "Discover hardware", description: "Tap to refresh doors and devices"
            input "excludedDoorIds", "enum", title: "Doors to exclude", multiple: true, required: false, options: doorOptions()
            paragraph "All discovered hardware is imported by default. Door contact stays at its last known value when status is stale."
            paragraph "Door API: ${state.doorReadStatus ?: 'not checked'}; hardware API: ${state.deviceReadStatus ?: 'not checked'}"
        }
        section("Operation") {
            input "pollInterval", "enum", title: "Polling interval", options: ["30": "30 seconds", "60": "1 minute", "120": "2 minutes", "300": "5 minutes"], required: true, defaultValue: "60"
        }
        section("Events") {
            paragraph "WebSocket: ${getChildDevice(eventsDni())?.currentValue('connectionStatus') ?: 'not started'}; webhook: ${state.webhookStatus ?: 'not started'}"
        }
    }
}

def discoverPage() {
    def fingerprint = connectionFingerprint()
    if (connectionReady() && (state.discoveryFingerprint != fingerprint || !state.discoveryRequestedAt || now() - state.discoveryRequestedAt > 15000L)) {
        state.discoveryRequestedAt = now()
        state.discoveryFingerprint = fingerprint
        requestDoors()
        requestDevices()
    }
    dynamicPage(name: "discoverPage", title: "Discover doors", refreshInterval: 5) {
        section("Discovery") {
            paragraph state.discoveryMessage ?: "Discovery requested. Return to this page shortly."
            (state.doors ?: []).each { door ->
                paragraph "${door.name} (${door.id})"
            }
            (state.devices ?: []).each { accessDevice ->
                paragraph "${accessDevice.name} (${accessDevice.type})"
            }
            if (!connectionReady()) {
                paragraph "Enter and save the console host and API token first."
            }
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def uninstalled() {
    unschedule()
    getChildDevice(eventsDni())?.closeAccessEvents()
    removeManagedWebhook()
}

private void initialize() {
    unschedule()
    state.generation = ((state.generation ?: 0) as Long) + 1L
    state.latestDoorsSequence = 0L
    state.doorsInFlight = null
    state.relayRechecks = [:]
    state.latestDevicesSequence = 0L
    state.devicesInFlight = null
    state.unlockInFlight = [:]
    state.actionInFlight = [:]
    state.detailQueue = []
    state.detailInFlight = null
    state.detailSequence = 0L
    state.detailLastAttempt = [:]
    state.deviceMethods = [:]
    state.observedDoorbells = [:]
    state.lastDpsEvents = [:]
    state.doors = []
    state.devices = []
    state.doorsFresh = false
    state.devicesFresh = false
    state.lastDoorsSuccess = 0L
    state.lastDevicesSuccess = 0L
    state.discoveryRequestedAt = null
    state.discoveryFingerprint = null
    state.discoveryMessage = null
    state.doorReadStatus = "pending"
    state.deviceReadStatus = "pending"
    state.initialized = true
    getChildDevices().each { child ->
        if (child.currentValue("lastCommandStatus") == "pending") {
            child.setCommandStatus("indeterminate")
        }
        child.markStale()
    }
    ensureControllerChild()
    ensureEventsChild()
    configureEventsChild()
    configureManagedWebhook()
    poll()
    runIn(pollSeconds() + 20, "checkFreshness")
}

def poll() {
    unschedule("poll")
    requestDoors()
    requestDevices()
    queueDetail("emergency", null)
    runIn(pollSeconds(), "poll")
}

def refreshDoor(String deviceNetworkId) {
    if (getChildDevice(deviceNetworkId) && deviceNetworkId?.startsWith("unifi-access:${app.id}:door:")) {
        requestDoors()
        queueDetail("rule", doorIdFromDni(deviceNetworkId), true)
    }
}

def refreshDevice(String deviceNetworkId) {
    if (getChildDevice(deviceNetworkId) && deviceNetworkId?.startsWith("unifi-access:${app.id}:device:")) {
        requestDevices()
        queueDetail("methods", doorIdFromDni(deviceNetworkId), true)
    }
}

def refreshDoorbell(String deviceNetworkId) {
    if (deviceNetworkId?.startsWith("unifi-access:${app.id}:doorbell:") && getChildDevice(deviceNetworkId)) {
        requestDevices()
    }
}

def refreshRequestToEnter(String deviceNetworkId) {
    if (deviceNetworkId?.startsWith("unifi-access:${app.id}:ren:") && getChildDevice(deviceNetworkId)) {
        requestDoors()
    }
}

def unlockDoor(String deviceNetworkId) {
    def child = deviceNetworkId?.startsWith("unifi-access:${app.id}:door:") ? getChildDevice(deviceNetworkId) : null
    def doorId = doorIdFromDni(deviceNetworkId)
    def door = (state.doors ?: []).find { it.id == doorId }
    if (!child || !door || (settings.excludedDoorIds ?: []).contains(doorId) || !connectionReady() || !doorActionAvailable(door)) {
        child?.setCommandStatus("failed")
        log.warn "UniFi Access unlock is unavailable for this door."
        return
    }
    def pending = state.unlockInFlight ?: [:]
    if (pending[doorId] && now() - (pending[doorId] as Long) < 20000L) {
        log.warn "UniFi Access unlock is already pending for this door."
        return
    }
    pending[doorId] = now()
    state.unlockInFlight = pending
    child.setCommandStatus("pending")
    def context = [generation: state.generation ?: 0L, fingerprint: connectionFingerprint(), doorId: doorId, startedAt: pending[doorId]]
    runIn(15, "checkUnlockTimeout")
    try {
        asynchttpPut("unlockCallback", requestParameters("/api/v1/developer/doors/${java.net.URLEncoder.encode(doorId, 'UTF-8')}/unlock") + [contentType: "application/json", body: "{}"], context)
    } catch (Exception ignored) {
        clearUnlock(context)
        child.setCommandStatus("indeterminate")
        log.warn "UniFi Access unlock request could not be sent."
    }
}

def unlockCallback(response, Map context) {
    if (context.generation != (state.generation ?: 0L)) {
        return
    }
    if (context.fingerprint != connectionFingerprint()) {
        clearUnlock(context)
        getChildDevice(dniFor(context.doorId))?.setCommandStatus("indeterminate")
        return
    }
    if (!clearUnlock(context)) {
        return
    }
    if (now() - (context.startedAt as Long) > 15000L) {
        getChildDevice(dniFor(context.doorId))?.setCommandStatus("indeterminate")
        return
    }
    def child = getChildDevice(dniFor(context.doorId))
    def outcome = actionResponseOutcome(response)
    if (outcome != "accepted") {
        child?.setCommandStatus(outcome)
        log.warn "UniFi Access unlock failed. Check app connection and API permissions."
        return
    }
    child?.setCommandStatus("accepted")
    armRelayRecheck(context.doorId)
    requestDoors()
}

def checkUnlockTimeout() {
    def pending = state.unlockInFlight ?: [:]
    def expired = pending.findAll { doorId, startedAt -> now() - (startedAt as Long) >= 15000L }
    expired.each { doorId, startedAt ->
        getChildDevice(dniFor(doorId))?.setCommandStatus("indeterminate")
        pending.remove(doorId)
    }
    state.unlockInFlight = pending
    if (pending) {
        runIn(15, "checkUnlockTimeout")
    }
}

def checkFreshness() {
    if (state.detailInFlight && now() - (state.detailInFlight.startedAt as Long) >= 30000L) {
        def timedOut = state.detailInFlight
        state.detailInFlight = null
        failDetail(timedOut, "timeout", true)
    }
    if (state.devicesFresh == true && now() - ((state.lastDevicesSuccess ?: 0L) as Long) >= maxStatusAge()) {
        markDevicesStale()
    }
    if (state.doorsFresh == true && now() - ((state.lastDoorsSuccess ?: 0L) as Long) >= maxStatusAge()) {
        markDoorsStale()
    }
    updateDoorHardwareHealth()
    runIn(pollSeconds() + 20, "checkFreshness")
}

private boolean clearUnlock(Map context) {
    def pending = state.unlockInFlight ?: [:]
    if (pending[context.doorId] != context.startedAt) {
        return false
    }
    pending.remove(context.doorId)
    state.unlockInFlight = pending
    return true
}

private void requestDoors() {
    if (!connectionReady()) {
        state.discoveryMessage = "Enter a console host and API token."
        markDoorsStale()
        return
    }
    def pending = state.doorsInFlight
    if (pending && now() - (pending.startedAt as Long) < 30000L) {
        return
    }
    if (pending) {
        markDoorsStale()
    }
    def sequence = ((state.latestDoorsSequence ?: 0) as Long) + 1L
    state.latestDoorsSequence = sequence
    def context = [generation: state.generation ?: 0L, fingerprint: connectionFingerprint(), sequence: sequence, startedAt: now()]
    state.doorsInFlight = context
    state.doorReadStatus = "pending"
    try {
        asynchttpGet("doorsCallback", requestParameters("/api/v1/developer/doors"), context)
    } catch (Exception ignored) {
        state.doorsInFlight = null
        state.discoveryMessage = "Door discovery failed. Check connection settings."
        state.doorReadStatus = "transport error"
        markDoorsStale()
        log.warn "UniFi Access door request could not be sent."
    }
}

def doorsCallback(response, Map context) {
    if (context.generation != (state.generation ?: 0L) || context.sequence != state.latestDoorsSequence) {
        return
    }
    state.doorsInFlight = null
    if (context.fingerprint != connectionFingerprint()) {
        return
    }
    if (now() - (context.startedAt as Long) > 30000L) {
        state.doorReadStatus = "late response"
        markDoorsStale()
        return
    }
    def payload = successfulPayload(response)
    if (!(payload?.data instanceof List)) {
        state.discoveryMessage = "Door discovery failed. Check connection and view:space permission."
        state.doorReadStatus = response?.status == 403 ? "HTTP 403" : response?.status == 401 ? "HTTP 401" : "invalid or failed response"
        markDoorsStale()
        log.warn "UniFi Access door request failed."
        return
    }
    def previousRelays = (state.doors ?: []).collectEntries { [(it.id): it.lockRelay] }
    def doors = payload.data.findAll { it instanceof Map && it.id instanceof String && it.name instanceof String }.collect { door ->
        def recentDps = (state.lastDpsEvents ?: [:])[door.id]
        def position = recentDps && (recentDps.receivedAt as Long) > (context.startedAt as Long) ?
            recentDps.position : door.door_position_status
        [id: door.id, name: door.name, fullName: door.full_name ?: door.name,
         isBindHub: door.is_bind_hub == true,
         lockRelay: door.door_lock_relay_status,
         position: position]
    }
    state.doors = doors
    state.doorReadStatus = "success"
    state.doorsFresh = true
    state.lastDoorsSuccess = now()
    state.lastSuccessfulPoll = now()
    state.discoveryMessage = "Found ${doors.size()} door(s) and ${(state.devices ?: []).size()} device(s)."
    doors.each { door ->
        if (door.lockRelay == "unlock" && previousRelays[door.id] != "unlock") {
            armRelayRecheck(door.id, true)
        }
    }
    def rechecks = state.relayRechecks ?: [:]
    doors.each { door ->
        if (door.lockRelay == "lock" && rechecks[door.id] &&
                   context.sequence >= (rechecks[door.id].minimumSequence as Long) &&
                   (context.startedAt as Long) - (rechecks[door.id].startedAt as Long) >=
                       (rechecks[door.id].observedUnlock == true ? 2000L : 6000L)) {
            rechecks.remove(door.id)
        }
    }
    state.relayRechecks = rechecks
    scheduleRelayRecheck()
    if (state.initialized) {
        syncChildren(doors)
        doors.each { door -> queueDetail("rule", door.id) }
    }
}

private void armRelayRecheck(String doorId, boolean observedUnlock = false) {
    if (!doorId || (settings.excludedDoorIds ?: []).contains(doorId)) {
        return
    }
    def rechecks = state.relayRechecks ?: [:]
    if (rechecks[doorId] && now() - (rechecks[doorId].startedAt as Long) < 65000L) {
        if (observedUnlock) {
            rechecks[doorId].observedUnlock = true
            state.relayRechecks = rechecks
        }
        return
    }
    rechecks[doorId] = [startedAt: now(), nextAt: now() + 2000L, attempts: 0, observedUnlock: observedUnlock,
                        minimumSequence: ((state.latestDoorsSequence ?: 0L) as Long) + 1L]
    state.relayRechecks = rechecks
    scheduleRelayRecheck()
}

def reconcileRelayState() {
    def currentTime = now()
    def rechecks = (state.relayRechecks ?: [:]).findAll { doorId, check ->
        currentTime - (check.startedAt as Long) < 65000L
    }
    if (!connectionReady()) {
        state.relayRechecks = [:]
        return
    }
    def due = rechecks.findAll { doorId, check ->
        (check.attempts as Integer) < 10 && currentTime >= (check.nextAt as Long)
    }
    if (due) {
        def inFlight = state.doorsInFlight
        if (inFlight && currentTime - (inFlight.startedAt as Long) < 30000L) {
            due.each { doorId, check -> check.nextAt = currentTime + 2000L }
        } else {
            due.each { doorId, check ->
                check.attempts = (check.attempts as Integer) + 1
                check.nextAt = Math.min((check.startedAt as Long) + 65000L,
                    Math.max(currentTime + 2000L, (check.startedAt as Long) +
                        [4000L, 6000L, 8000L, 10000L, 15000L, 20000L, 30000L, 45000L, 60000L, 65000L][(check.attempts as Integer) - 1]))
            }
            state.relayRechecks = rechecks
            requestDoors()
        }
    }
    state.relayRechecks = rechecks
    scheduleRelayRecheck()
}

private void scheduleRelayRecheck() {
    unschedule("reconcileRelayState")
    def rechecks = state.relayRechecks ?: [:]
    if (!rechecks) {
        return
    }
    def nextAt = rechecks.values().collect { it.nextAt as Long }.min() as Long
    def seconds = Math.max(1, ((Math.max(0L, nextAt - now()) + 999L) / 1000L) as Integer)
    runIn(seconds, "reconcileRelayState")
}

private void syncChildren(List doors) {
    def excluded = (settings.excludedDoorIds ?: []) as List
    def active = doors.findAll { !excluded.contains(it.id) }
    def activeIds = active.collect { dniFor(it.id) } as Set
    getChildDevices().findAll { it.deviceNetworkId?.startsWith("unifi-access:${app.id}:door:") }.each { child ->
        if (!activeIds.contains(child.deviceNetworkId)) {
            child.markOffline()
        }
    }
    def activeRenIds = active.collect { renDniFor(it.id) } as Set
    getChildDevices().findAll { it.deviceNetworkId?.startsWith("unifi-access:${app.id}:ren:") }.each { child ->
        if (!activeRenIds.contains(child.deviceNetworkId)) {
            child.markOffline()
        }
    }
    active.each { door ->
        def dni = dniFor(door.id)
        def child = getChildDevice(dni)
        if (!child) {
            try {
                child = addChildDevice("georgeb", "UniFi Access Door", dni, [label: door.name, isComponent: true])
            } catch (Exception ignored) {
                log.warn "UniFi Access could not create a door device."
            }
        }
        if (child) {
            child.updateDoor(door + [hardwareHealth: doorHardwareStatus(door)])
        }
    }
}

private String doorHardwareStatus(Map door) {
    if (door.isBindHub != true) {
        return "offline"
    }
    if (state.doorsFresh != true || state.devicesFresh != true ||
        now() - ((state.lastDoorsSuccess ?: 0L) as Long) > maxStatusAge() ||
        now() - ((state.lastDevicesSuccess ?: 0L) as Long) > maxStatusAge()) {
        return "stale"
    }
    def hub = (state.devices ?: []).find { it.locationId == door.id && it.isHub == true }
    return hub == null ? "unverified" : hub.online == true ? "online" : hub.online == false ? "offline" : "unverified"
}

private boolean doorActionAvailable(Map door) {
    if (door?.isBindHub != true || state.doorsFresh != true ||
        now() - ((state.lastDoorsSuccess ?: 0L) as Long) > maxStatusAge()) {
        return false
    }
    def hub = (state.devices ?: []).find { it.locationId == door.id && it.isHub == true }
    return !(state.devicesFresh == true && hub?.online == false)
}

private void updateDoorHardwareHealth() {
    def excluded = (settings.excludedDoorIds ?: []) as List
    (state.doors ?: []).each { door ->
        if (!excluded.contains(door.id)) {
            getChildDevice(dniFor(door.id))?.setHardwareHealth(doorHardwareStatus(door), door.lockRelay, door.position)
            getChildDevice(renDniFor(door.id))?.setDoorHealth(doorHardwareStatus(door))
        }
    }
}

private void markChildrenStale() {
    getChildDevices().each { child -> child.markStale() }
}

private void markDoorsStale() {
    state.doorsFresh = false
    getChildDevices().findAll { it.deviceNetworkId?.startsWith("unifi-access:${app.id}:door:") }.each { child -> child.markStale() }
    getChildDevices().findAll { it.deviceNetworkId?.startsWith("unifi-access:${app.id}:ren:") }.each { child -> child.markStale() }
}

private void requestDevices() {
    if (!connectionReady()) {
        markChildrenStale()
        return
    }
    def pending = state.devicesInFlight
    if (pending && now() - (pending.startedAt as Long) < 30000L) {
        return
    }
    if (pending) {
        markDevicesStale()
    }
    def sequence = ((state.latestDevicesSequence ?: 0) as Long) + 1L
    state.latestDevicesSequence = sequence
    def context = [generation: state.generation ?: 0L, fingerprint: connectionFingerprint(), sequence: sequence, startedAt: now()]
    state.devicesInFlight = context
    state.deviceReadStatus = "pending"
    try {
        asynchttpGet("devicesCallback", requestParameters("/api/v1/developer/devices?refresh=true"), context)
    } catch (Exception ignored) {
        state.devicesInFlight = null
        state.deviceReadStatus = "transport error"
        markDevicesStale()
        log.warn "UniFi Access device request could not be sent."
    }
}

def devicesCallback(response, Map context) {
    if (context.generation != (state.generation ?: 0L) || context.sequence != state.latestDevicesSequence) {
        return
    }
    state.devicesInFlight = null
    if (context.fingerprint != connectionFingerprint()) {
        return
    }
    if (now() - (context.startedAt as Long) > 30000L) {
        state.deviceReadStatus = "late response"
        markDevicesStale()
        return
    }
    def payload = successfulPayload(response)
    if (!(payload?.data instanceof List)) {
        state.deviceReadStatus = response?.status == 403 ? "HTTP 403" : response?.status == 401 ? "HTTP 401" : "invalid or failed response"
        markDevicesStale()
        log.warn "UniFi Access device request failed."
        return
    }
    def devices = payload.data.collectMany { group -> group instanceof List ? group : [group] }
        .findAll { it instanceof Map && it.id instanceof String && it.name instanceof String }
        .collect { accessDevice ->
            def capabilities = accessDevice.capabilities
            def reportedCapabilities = capabilities instanceof Collection ?
                capabilities.findAll { it instanceof String && it.length() <= 64 }.take(64) :
                capabilities instanceof Map ? capabilities.findAll { key, value ->
                    key instanceof String && key.length() <= 64 && value == true
                }.keySet().toList().take(64) : []
            def hubCapability = reportedCapabilities.any { it in ["is_hub", "identity_is_hub"] } || accessDevice.is_hub == true
            [id: accessDevice.id, name: accessDevice.alias ?: accessDevice.name,
             type: accessDevice.type ?: "unknown", online: accessDevice.is_online,
             connected: accessDevice.is_connected, adopted: accessDevice.is_adopted,
             locationId: accessDevice.location_id,
             isHub: hubCapability, reportedCapabilities: reportedCapabilities]
        }
    state.devices = devices
    state.deviceReadStatus = "success"
    state.devicesFresh = true
    state.lastDevicesSuccess = now()
    state.discoveryMessage = "Found ${(state.doors ?: []).size()} door(s) and ${devices.size()} device(s)."
    if (state.initialized) {
        syncDevices(devices)
        updateDoorHardwareHealth()
        devices.each { accessDevice -> queueDetail("methods", accessDevice.id) }
    }
}

private void syncDevices(List devices) {
    def activeIds = devices.collect { deviceDniFor(it.id) } as Set
    def doorbellIds = devices.collect { doorbellDniFor(it.id) } as Set
    getChildDevices().findAll { it.deviceNetworkId?.startsWith("unifi-access:${app.id}:device:") }.each { child ->
        if (!activeIds.contains(child.deviceNetworkId)) {
            child.markOffline()
        }
    }
    getChildDevices().findAll { it.deviceNetworkId?.startsWith("unifi-access:${app.id}:doorbell:") }.each { child ->
        if (!doorbellIds.contains(child.deviceNetworkId)) {
            child.markOffline()
        }
    }
    devices.each { accessDevice ->
        def dni = deviceDniFor(accessDevice.id)
        def child = getChildDevice(dni)
        if (!child) {
            try {
                child = addChildDevice("georgeb", "UniFi Access Device", dni, [label: accessDevice.name, isComponent: true])
            } catch (Exception ignored) {
                log.warn "UniFi Access could not create a hardware device."
            }
        }
        if (child) {
            child.updateDevice(accessDevice)
            child.setDoorbellEventsStatus((state.observedDoorbells ?: [:])[accessDevice.id] ? "detected" : "unknown")
        }
        getChildDevice(doorbellDniFor(accessDevice.id))?.updateDevice(accessDevice)
    }
}

private void markDevicesStale() {
    state.devicesFresh = false
    getChildDevices().findAll { it.deviceNetworkId?.startsWith("unifi-access:${app.id}:device:") ||
        it.deviceNetworkId?.startsWith("unifi-access:${app.id}:doorbell:") }.each { child -> child.markStale() }
    updateDoorHardwareHealth()
}

def refreshController(String deviceNetworkId) {
    if (deviceNetworkId == controllerDni()) {
        queueDetail("emergency", null, true)
    }
}

private void ensureControllerChild() {
    if (getChildDevice(controllerDni())) {
        return
    }
    try {
        addChildDevice("georgeb", "UniFi Access Controller", controllerDni(), [label: "UniFi Access Emergency", isComponent: true])
    } catch (Exception ignored) {
        log.warn "UniFi Access could not create the emergency controller device."
    }
}

private void ensureEventsChild() {
    if (getChildDevice(eventsDni())) {
        return
    }
    try {
        addChildDevice("georgeb", "UniFi Access Events", eventsDni(), [label: "UniFi Access Events", isComponent: true])
    } catch (Exception ignored) {
        log.warn "UniFi Access could not create the event receiver device."
    }
}

private void configureEventsChild() {
    def child = getChildDevice(eventsDni())
    if (!connectionReady()) {
        child?.closeAccessEvents()
    } else {
        child?.configureAccessEvents()
    }
}

def accessEventsConnectionDetails() {
    if (!connectionReady()) {
        return null
    }
    [host: settings.consoleHost?.trim(), port: settings.apiPort as Integer,
     token: settings.apiToken, allowSelfSigned: settings.allowSelfSigned == true,
     fingerprint: connectionFingerprint()]
}

def notifyAccessEvent(Map event) {
    if (!(event instanceof Map) || event.fingerprint != connectionFingerprint() ||
        !(event.event in ["access.remote_view", "access.remote_view.change"])) {
        return
    }
    dispatchAccessEvent(event)
}

private void dispatchAccessEvent(Map event) {
    def eventName = event.event
    def requestId = event.requestId
    def eventId = event.eventId
    def key = requestId && eventName == "access.doorbell.incoming.REN" ? "ren:${requestId}" :
        requestId && eventName in ["access.remote_view", "access.doorbell.incoming"] ?
        "ring:${requestId}" : requestId && eventName in ["access.remote_view.change", "access.doorbell.completed"] ?
        "complete:${requestId}" : eventId ? "event:${eventId}" : null
    if (key && !rememberEvent(key)) {
        return
    }
    if (eventName == "access.doorbell.incoming.REN") {
        def door = (state.doors ?: []).find { it.id == event.doorId }
        if (door && !(settings.excludedDoorIds ?: []).contains(door.id)) {
            def renDni = renDniFor(door.id)
            def child = getChildDevice(renDni)
            if (!child) {
                try {
                    child = addChildDevice("georgeb", "UniFi Access Request To Enter", renDni,
                        [label: "${door.name} Request to Enter", isComponent: true])
                } catch (Exception ignored) {
                    log.warn "UniFi Access could not create a request-to-enter device."
                }
            }
            child?.setDoorHealth(doorHardwareStatus(door))
            child?.recordRenEvent()
        }
        return
    }
    if (eventName in ["access.remote_view", "access.remote_view.change", "access.doorbell.incoming",
                      "access.doorbell.completed"]) {
        def deviceId = event.deviceId
        def accessDevice = (state.devices ?: []).find { it.id == deviceId }
        if (accessDevice && !(settings.excludedDoorIds ?: []).contains(accessDevice.locationId)) {
            def deviceChild = getChildDevice(deviceDniFor(deviceId))
            deviceChild?.recordDoorbellEvent(event)
            def bellDni = doorbellDniFor(deviceId)
            def bell = getChildDevice(bellDni)
            if (eventName in ["access.remote_view", "access.doorbell.incoming"] && deviceChild) {
                def observed = state.observedDoorbells ?: [:]
                observed[deviceId] = now()
                state.observedDoorbells = observed
                deviceChild.setDoorbellEventsStatus("detected")
                if (!bell) {
                    try {
                        bell = addChildDevice("georgeb", "UniFi Access Doorbell", bellDni,
                            [label: "${accessDevice.name} Doorbell", isComponent: true])
                    } catch (Exception ignored) {
                        log.warn "UniFi Access could not create a doorbell device."
                    }
                }
                bell?.updateDevice(accessDevice)
            }
            bell?.recordDoorbellEvent(event)
        }
        return
    }
    if (eventName == "access.device.dps_status" && event.doorId && event.position in ["open", "close"]) {
        def doors = state.doors ?: []
        def door = doors.find { it.id == event.doorId }
        def child = getChildDevice(dniFor(event.doorId))
        if (door && child && !(settings.excludedDoorIds ?: []).contains(event.doorId)) {
            def events = state.lastDpsEvents ?: [:]
            events[event.doorId] = [receivedAt: now(), position: event.position]
            state.lastDpsEvents = events
            state.doors = doors.collect { cached ->
                cached.id == event.doorId ? cached + [position: event.position] : cached
            }
            child.applyDpsPosition(event.position)
            requestDoors()
        }
    } else if (eventName == "access.device.emergency_status") {
        queueDetail("emergency", null, true)
    } else if (eventName in ["access.door.unlock", "access.unlock_schedule.activate",
                              "access.unlock_schedule.deactivate", "access.temporary_unlock.start",
                              "access.temporary_unlock.end"]) {
        if (eventName == "access.door.unlock") {
            armRelayRecheck(event.doorId)
        }
        requestDoors()
        if (event.doorId) {
            queueDetail("rule", event.doorId, true)
        }
    }
}

private boolean rememberEvent(String key) {
    def seen = state.seenEvents ?: [:]
    def recent = seen.findAll { eventKey, receivedAt -> now() - (receivedAt as Long) < 300000L }
    if (recent.containsKey(key)) {
        state.seenEvents = recent
        return false
    }
    recent[key] = now()
    while (recent.size() > 128) {
        recent.remove(recent.keySet().first())
    }
    state.seenEvents = recent
    return true
}

def receiveAccessWebhook() {
    def body = request?.body
    def signatureHeader = null
    def headers = request?.headers
    if (headers instanceof Map) {
        signatureHeader = headers.find { name, value -> name?.toString()?.equalsIgnoreCase("Signature") }?.value
    }
    if (signatureHeader instanceof Collection) {
        signatureHeader = signatureHeader.find { it instanceof CharSequence }
    }
    if (!(body instanceof String) || body.length() > 65536 ||
        !(signatureHeader instanceof CharSequence) || !validWebhookSignature(body, signatureHeader.toString())) {
        return render(status: 401, contentType: "application/json", data: '{"ok":false}')
    }
    def event
    try {
        event = new groovy.json.JsonSlurper().parseText(body)
    } catch (Exception ignored) {
        return render(status: 400, contentType: "application/json", data: '{"ok":false}')
    }
    def sanitized = sanitizeWebhookEvent(event)
    if (sanitized) {
        dispatchAccessEvent(sanitized)
    }
    return render(status: 200, contentType: "application/json", data: '{"ok":true}')
}

private boolean validWebhookSignature(String body, String header) {
    if (!(state.webhookSecret instanceof String) || !state.webhookSecret) {
        return false
    }
    def match = header =~ /^\s*t=(\d+),\s*v1=([0-9a-fA-F]{64})\s*$/
    if (!match.matches()) {
        return false
    }
    Long timestamp
    try {
        timestamp = Long.parseLong(match[0][1])
    } catch (Exception ignored) {
        return false
    }
    if (Math.abs((now() / 1000L) - timestamp) > 300L) {
        return false
    }
    try {
        def mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(new javax.crypto.spec.SecretKeySpec(state.webhookSecret.getBytes("UTF-8"), "HmacSHA256"))
        def expected = mac.doFinal("${timestamp}.${body}".getBytes("UTF-8"))
        def supplied = match[0][2].decodeHex()
        return java.security.MessageDigest.isEqual(expected, supplied) &&
            rememberEvent("signature:${match[0][2].toLowerCase()}")
    } catch (Exception ignored) {
        return false
    }
}

private Map sanitizeWebhookEvent(event) {
    if (!(event instanceof Map) || !(event.data instanceof Map) || !(event.event_object_id instanceof String)) {
        return null
    }
    def allowed = ["access.device.dps_status", "access.door.unlock", "access.device.emergency_status",
                   "access.doorbell.incoming", "access.doorbell.completed", "access.doorbell.incoming.REN",
                   "access.unlock_schedule.activate", "access.unlock_schedule.deactivate",
                   "access.temporary_unlock.start", "access.temporary_unlock.end"]
    if (!(event.event in allowed)) {
        return null
    }
    def data = event.data
    def doorId = data.location instanceof Map && data.location.location_type == "door" ? data.location.id : null
    def deviceId = data.device instanceof Map ? data.device.id : null
    def requestId = data.object instanceof Map ? data.object.request_id : null
    def position = event.event == "access.device.dps_status" && data.object instanceof Map &&
        data.object.event_type == "dps_change" ? data.object.status : null
    [event: event.event, eventId: event.event_object_id, doorId: doorId instanceof String ? doorId : null,
     deviceId: deviceId instanceof String ? deviceId : null,
     requestId: requestId instanceof String ? requestId : null,
     position: position in ["open", "close"] ? position : null]
}

private void configureManagedWebhook() {
    if (!connectionReady()) {
        state.webhookStatus = "connection unavailable"
        return
    }
    try {
        if (!state.hubitatAccessToken) {
            state.hubitatAccessToken = createAccessToken()
        }
        if (!state.hubitatAccessToken || !getFullLocalApiServerUrl()) {
            state.webhookStatus = "OAuth unavailable"
            return
        }
    } catch (Exception ignored) {
        state.webhookStatus = "OAuth unavailable"
        return
    }
    state.webhookStatus = "checking"
    def context = [generation: state.generation ?: 0L, fingerprint: connectionFingerprint()]
    try {
        asynchttpGet("webhookListCallback", requestParameters("/api/v1/developer/webhooks/endpoints"), context)
    } catch (Exception ignored) {
        state.webhookStatus = "connection error"
    }
}

def webhookListCallback(response, Map context) {
    if (context.generation != (state.generation ?: 0L) || context.fingerprint != connectionFingerprint()) {
        return
    }
    def payload = successfulPayload(response)
    if (!(payload?.data instanceof List)) {
        state.webhookStatus = response?.status == 403 ? "missing webhook scope" : "list failed"
        return
    }
    def endpointUrl = managedWebhookUrl()
    if (!endpointUrl) {
        state.webhookStatus = "OAuth unavailable"
        return
    }
    def existing = payload.data.find { it instanceof Map && it.name == managedWebhookName() && it.id instanceof String }
    if (existing && existing.endpoint == endpointUrl && existing.secret instanceof String && existing.secret) {
        state.webhookId = existing.id
        state.webhookSecret = existing.secret
        state.webhookStatus = "active"
        return
    }
    def body = [name: managedWebhookName(), endpoint: endpointUrl, events: managedWebhookEvents()]
    def path = existing ? "/api/v1/developer/webhooks/endpoints/${encodeSegment(existing.id)}" :
                          "/api/v1/developer/webhooks/endpoints"
    def parameters = requestParameters(path) + [contentType: "application/json", body: groovy.json.JsonOutput.toJson(body)]
    def updateContext = context + [existingId: existing?.id]
    state.webhookStatus = "registering"
    try {
        if (existing) {
            asynchttpPut("webhookUpsertCallback", parameters, updateContext)
        } else {
            asynchttpPost("webhookUpsertCallback", parameters, updateContext)
        }
    } catch (Exception ignored) {
        state.webhookStatus = "registration failed"
    }
}

def webhookUpsertCallback(response, Map context) {
    if (context.generation != (state.generation ?: 0L) || context.fingerprint != connectionFingerprint()) {
        return
    }
    def payload = successfulPayload(response)
    def webhook = payload?.data
    if (!(webhook instanceof Map) || !(webhook.id instanceof String) || !(webhook.secret instanceof String)) {
        state.webhookStatus = response?.status == 403 ? "missing webhook scope" : "registration failed"
        return
    }
    state.webhookId = webhook.id
    state.webhookSecret = webhook.secret
    state.webhookStatus = "active"
}

private void removeManagedWebhook() {
    def webhookId = state.webhookId
    if (!webhookId) {
        return
    }
    if (!connectionReady()) {
        state.webhookStatus = "removal pending"
        return
    }
    def context = [generation: state.generation ?: 0L, fingerprint: connectionFingerprint(), webhookId: webhookId]
    try {
        asynchttpDelete("webhookDeleteCallback",
            requestParameters("/api/v1/developer/webhooks/endpoints/${encodeSegment(webhookId)}"), context)
    } catch (Exception ignored) {
        state.webhookStatus = "removal failed"
    }
}

def webhookDeleteCallback(response, Map context) {
    if (context.generation != (state.generation ?: 0L) || context.fingerprint != connectionFingerprint()) {
        return
    }
    if (successfulPayload(response)?.data == "success") {
        if (state.webhookId == context.webhookId) {
            state.webhookId = null
            state.webhookSecret = null
        }
        state.webhookStatus = "disabled"
    } else {
        state.webhookStatus = "removal unconfirmed"
    }
}

private String managedWebhookUrl() {
    try {
        def base = getFullLocalApiServerUrl()
        return base && state.hubitatAccessToken ? "${base}/events?access_token=${state.hubitatAccessToken}" : null
    } catch (Exception ignored) {
        return null
    }
}

private String managedWebhookName() {
    "Hubitat UniFi Access ${app.id}"
}

private List managedWebhookEvents() {
    ["access.device.dps_status", "access.door.unlock", "access.device.emergency_status",
     "access.doorbell.incoming", "access.doorbell.completed", "access.doorbell.incoming.REN",
     "access.unlock_schedule.activate", "access.unlock_schedule.deactivate",
     "access.temporary_unlock.start", "access.temporary_unlock.end"]
}

private void queueDetail(String kind, String resourceId, boolean force = false) {
    if (!connectionReady()) {
        return
    }
    def key = "${kind}:${resourceId ?: 'global'}"
    def queue = state.detailQueue ?: []
    if (state.detailInFlight?.key == key || queue.any { it.key == key }) {
        return
    }
    if (!force && now() - (((state.detailLastAttempt ?: [:])[key] ?: 0L) as Long) < 300000L) {
        return
    }
    queue << [kind: kind, resourceId: resourceId, key: key, attempt: 0, readyAt: 0L]
    state.detailQueue = queue
    pumpDetails()
}

def pumpDetails() {
    if (state.detailInFlight || !connectionReady()) {
        return
    }
    def queue = state.detailQueue ?: []
    if (!queue) {
        return
    }
    def task = queue.first()
    if ((task.readyAt as Long) > now()) {
        runIn(3, "pumpDetails")
        return
    }
    queue.remove(0)
    state.detailQueue = queue
    def path = detailPath(task.kind, task.resourceId)
    if (!path) {
        pumpDetails()
        return
    }
    def sequence = ((state.detailSequence ?: 0L) as Long) + 1L
    state.detailSequence = sequence
    def context = task + [generation: state.generation ?: 0L, fingerprint: connectionFingerprint(), sequence: sequence, startedAt: now()]
    state.detailInFlight = context
    def attempts = state.detailLastAttempt ?: [:]
    attempts[task.key] = now()
    state.detailLastAttempt = attempts
    try {
        asynchttpGet("detailCallback", requestParameters(path), context)
    } catch (Exception ignored) {
        state.detailInFlight = null
        failDetail(context, "transport error", true)
    }
}

def detailCallback(response, Map context) {
    if (context.generation != (state.generation ?: 0L) || context.sequence != state.detailSequence) {
        return
    }
    state.detailInFlight = null
    if (context.fingerprint != connectionFingerprint()) {
        pumpDetails()
        return
    }
    if (now() - (context.startedAt as Long) > 30000L) {
        failDetail(context, "late response", false)
        return
    }
    def payload = successfulPayload(response)
    if (payload?.data instanceof Map) {
        if (context.kind == "rule" && payload.data.type instanceof String) {
            getChildDevice(dniFor(context.resourceId))?.updateLockRule(payload.data)
            pumpDetails()
            return
        }
        if (context.kind == "methods" && payload.data.access_methods instanceof Map) {
            def methods = state.deviceMethods ?: [:]
            methods[context.resourceId] = payload.data.access_methods
            state.deviceMethods = methods
            getChildDevice(deviceDniFor(context.resourceId))?.updateAccessMethods(payload.data.access_methods)
            pumpDetails()
            return
        }
        if (context.kind == "emergency" && payload.data.lockdown instanceof Boolean && payload.data.evacuation instanceof Boolean) {
            getChildDevice(controllerDni())?.updateEmergency(payload.data)
            pumpDetails()
            return
        }
    }
    def errorCode = null
    try {
        errorCode = response?.getJson()?.code
    } catch (Exception ignored) {
    }
    def unsupported = response?.status == 404 || errorCode == "CODE_DEVICE_API_NOT_SUPPORTED"
    def retryable = response == null || response.hasError() || response.status >= 500
    failDetail(context, unsupported ? "unsupported" : response?.status == 401 ? "unauthorized" :
        response?.status == 403 ? "forbidden" : "unavailable", retryable)
}

private void failDetail(Map context, String status, boolean retryable) {
    if (retryable && (context.attempt as Integer) < 1) {
        def queue = state.detailQueue ?: []
        queue.add(0, [kind: context.kind, resourceId: context.resourceId, key: context.key,
                      attempt: (context.attempt as Integer) + 1, readyAt: now() + 3000L])
        state.detailQueue = queue
        runIn(3, "pumpDetails")
        return
    }
    if (context.kind == "rule") {
        getChildDevice(dniFor(context.resourceId))?.setLockRuleStatus(status)
    } else if (context.kind == "methods") {
        getChildDevice(deviceDniFor(context.resourceId))?.setAccessMethodsStatus(status)
    } else if (context.kind == "emergency") {
        getChildDevice(controllerDni())?.setEmergencyStatus(status)
    }
    pumpDetails()
}

private String detailPath(String kind, String resourceId) {
    if (kind == "emergency") {
        return "/api/v1/developer/doors/settings/emergency"
    }
    if (!resourceId) {
        return null
    }
    def encodedId = java.net.URLEncoder.encode(resourceId, "UTF-8")
    return kind == "rule" ? "/api/v1/developer/doors/${encodedId}/lock_rule" :
           kind == "methods" ? "/api/v1/developer/devices/${encodedId}/settings" : null
}

def setDoorRule(String deviceNetworkId, String ruleType, minutes) {
    def child = getChildDevice(deviceNetworkId)
    def door = activeDoor(deviceNetworkId)
    if (!door || child?.currentValue("lockRuleStatus") != "available") {
        rejectAction(child, "door rule")
        return
    }
    if (!(ruleType in ["keep_lock", "keep_unlock", "custom", "reset", "lock_early", "lock_now"])) {
        rejectAction(child, "door rule")
        return
    }
    def body = [type: ruleType]
    if (ruleType == "custom") {
        Integer interval
        try {
            interval = minutes as Integer
        } catch (Exception ignored) {
            rejectAction(child, "custom unlock")
            return
        }
        if (interval == null || interval < 1) {
            rejectAction(child, "custom unlock")
            return
        }
        body.interval = interval
    }
    def path = "/api/v1/developer/doors/${encodeSegment(door.id)}/lock_rule"
    submitAction(child, "door rule ${ruleType}", "PUT", path, body, "rule", door.id)
}

def gateCommand(String deviceNetworkId, String controlCommand, String direction) {
    def child = getChildDevice(deviceNetworkId)
    def door = activeDoor(deviceNetworkId)
    if (!door) {
        rejectAction(child, "gate")
        return
    }
    if (!((controlCommand in ["open", "close", "stop"] && direction == null) ||
          (controlCommand == null && direction in ["in", "out"]))) {
        rejectAction(child, "gate")
        return
    }
    def query = controlCommand ? "control_cmd=${controlCommand}" : "entry_method=${direction}"
    def path = "/api/v1/developer/doors/${encodeSegment(door.id)}/unlock?${query}"
    submitAction(child, controlCommand ? "gate ${controlCommand}" : "gate open ${direction}", "PUT", path, [:], "door", door.id)
}

def setAccessMethod(String deviceNetworkId, String method, String enabled) {
    def child = getChildDevice(deviceNetworkId)
    def accessDevice = activeDevice(deviceNetworkId)
    def setting = (state.deviceMethods ?: [:])[accessDevice?.id]
    def enabledValue = normalizeYesNo(enabled)
    if (!accessDevice || !(setting instanceof Map) ||
        !(method in accessMethodNames()) || !(setting[method] instanceof Map) || enabledValue == null) {
        rejectAction(child, "access method")
        return
    }
    submitAccessMethods(child, accessDevice.id, "${method} enabled", [(method): [enabled: enabledValue]])
}

def setPinShuffle(String deviceNetworkId, String enabled) {
    def child = getChildDevice(deviceNetworkId)
    def accessDevice = activeDevice(deviceNetworkId)
    def pin = (state.deviceMethods ?: [:])[accessDevice?.id]?.pin_code
    def enabledValue = normalizeYesNo(enabled)
    if (!accessDevice || !(pin instanceof Map) ||
        normalizeYesNo(pin.enabled) != "yes" || enabledValue == null) {
        rejectAction(child, "PIN shuffle")
        return
    }
    submitAccessMethods(child, accessDevice.id, "PIN shuffle", [pin_code: [pin_code_shuffle: enabledValue]])
}

def setFaceSensitivity(String deviceNetworkId, String antiSpoofing, String distance) {
    def child = getChildDevice(deviceNetworkId)
    def accessDevice = activeDevice(deviceNetworkId)
    def face = (state.deviceMethods ?: [:])[accessDevice?.id]?.face
    def combination = "${antiSpoofing}/${distance}".toString()
    if (!accessDevice || !(face instanceof Map) ||
        normalizeYesNo(face.enabled) != "yes" ||
        !(combination in ["no/far", "no/medium", "medium/near", "high/near"])) {
        rejectAction(child, "face sensitivity")
        return
    }
    submitAccessMethods(child, accessDevice.id, "face sensitivity",
        [face: [anti_spoofing_level: antiSpoofing, detect_distance: distance]])
}

private void submitAccessMethods(child, String deviceId, String action, Map fields) {
    def path = "/api/v1/developer/devices/${encodeSegment(deviceId)}/settings"
    submitAction(child, action, "PUT", path, [access_methods: fields], "methods", deviceId)
}

def triggerDoorbell(String deviceNetworkId, String roomName = "") {
    def child = getChildDevice(deviceNetworkId)
    def accessDevice = activeDevice(deviceNetworkId)
    if (!accessDevice || roomName == null || roomName.length() > 128 || roomName.find(/[\r\n\u0000-\u001f]/)) {
        rejectAction(child, "doorbell")
        return
    }
    def body = roomName ? [room_name: roomName, cancel: false] : [cancel: false]
    def path = "/api/v1/developer/devices/${encodeSegment(accessDevice.id)}/doorbell"
    submitAction(child, "doorbell ring", "POST", path, body, "device", accessDevice.id)
}

def cancelDoorbell(String deviceNetworkId) {
    def child = getChildDevice(deviceNetworkId)
    def accessDevice = activeDevice(deviceNetworkId)
    if (!accessDevice) {
        rejectAction(child, "doorbell cancel")
        return
    }
    def path = "/api/v1/developer/devices/${encodeSegment(accessDevice.id)}/doorbell"
    submitAction(child, "doorbell cancel", "POST", path, [cancel: true], "device", accessDevice.id)
}

def setEmergency(String mode) {
    def child = getChildDevice(controllerDni())
    if (child?.currentValue("healthStatus") != "online" ||
        !(mode in ["lockdown", "evacuation", "clear"])) {
        rejectAction(child, "emergency")
        return
    }
    def body = [lockdown: mode == "lockdown", evacuation: mode == "evacuation"]
    submitAction(child, "emergency ${mode}", "PUT", "/api/v1/developer/doors/settings/emergency",
        body, "emergency", null)
}

private void submitAction(child, String action, String method, String path, Map body, String refreshKind, String resourceId) {
    if (!child || !connectionReady()) {
        rejectAction(child, action)
        return
    }
    def pending = state.actionInFlight ?: [:]
    if (pending[child.deviceNetworkId] && now() - (pending[child.deviceNetworkId] as Long) < 30000L) {
        log.warn "UniFi Access hardware action is already pending."
        return
    }
    pending[child.deviceNetworkId] = now()
    state.actionInFlight = pending
    child.setCommandAction(action)
    child.setCommandStatus("pending")
    def context = [generation: state.generation ?: 0L, fingerprint: connectionFingerprint(),
                   deviceNetworkId: child.deviceNetworkId, startedAt: pending[child.deviceNetworkId],
                   action: action, refreshKind: refreshKind, resourceId: resourceId]
    runIn(30, "checkActionTimeout")
    try {
        def parameters = requestParameters(path) + [contentType: "application/json", body: groovy.json.JsonOutput.toJson(body)]
        if (method == "POST") {
            asynchttpPost("actionCallback", parameters, context)
        } else {
            asynchttpPut("actionCallback", parameters, context)
        }
    } catch (Exception ignored) {
        clearAction(context)
        child.setCommandStatus("indeterminate")
        log.warn "UniFi Access hardware action could not be sent."
    }
}

def actionCallback(response, Map context) {
    if (context.generation != (state.generation ?: 0L)) {
        return
    }
    if (context.fingerprint != connectionFingerprint()) {
        clearAction(context)
        getChildDevice(context.deviceNetworkId)?.setCommandStatus("indeterminate")
        return
    }
    if (!clearAction(context)) {
        return
    }
    def child = getChildDevice(context.deviceNetworkId)
    if (now() - (context.startedAt as Long) > 30000L) {
        child?.setCommandStatus("indeterminate")
        return
    }
    def outcome = actionResponseOutcome(response)
    if (outcome != "accepted") {
        child?.setCommandStatus(outcome)
        log.warn "UniFi Access hardware action failed."
        return
    }
    child?.setCommandStatus("accepted")
    if (context.refreshKind == "door") {
        requestDoors()
    } else if (context.refreshKind == "device") {
        requestDevices()
    } else {
        queueDetail(context.refreshKind, context.resourceId, true)
    }
}

def checkActionTimeout() {
    def pending = state.actionInFlight ?: [:]
    def expired = pending.findAll { deviceNetworkId, startedAt -> now() - (startedAt as Long) >= 30000L }
    expired.each { deviceNetworkId, startedAt ->
        getChildDevice(deviceNetworkId)?.setCommandStatus("indeterminate")
        pending.remove(deviceNetworkId)
    }
    state.actionInFlight = pending
    if (pending) {
        runIn(30, "checkActionTimeout")
    }
}

private boolean clearAction(Map context) {
    def pending = state.actionInFlight ?: [:]
    if (pending[context.deviceNetworkId] != context.startedAt) {
        return false
    }
    pending.remove(context.deviceNetworkId)
    state.actionInFlight = pending
    return true
}

private void rejectAction(child, String action) {
    child?.setCommandAction(action)
    child?.setCommandStatus("failed")
    log.warn "UniFi Access hardware action is unavailable."
}

private Map activeDoor(String deviceNetworkId) {
    if (!deviceNetworkId?.startsWith("unifi-access:${app.id}:door:") || !getChildDevice(deviceNetworkId)) {
        return null
    }
    def doorId = doorIdFromDni(deviceNetworkId)
    if ((settings.excludedDoorIds ?: []).contains(doorId)) {
        return null
    }
    def door = (state.doors ?: []).find { it.id == doorId }
    return door && doorActionAvailable(door) ? door : null
}

private Map activeDevice(String deviceNetworkId) {
    if (!deviceNetworkId?.startsWith("unifi-access:${app.id}:device:") || !getChildDevice(deviceNetworkId) ||
        state.devicesFresh != true || now() - ((state.lastDevicesSuccess ?: 0L) as Long) > maxStatusAge()) {
        return null
    }
    def accessDevice = (state.devices ?: []).find { it.id == doorIdFromDni(deviceNetworkId) }
    return accessDevice?.online == true ? accessDevice : null
}

private List accessMethodNames() {
    ["nfc", "bt_tap", "bt_button", "bt_shake", "mobile_wave", "pin_code", "face", "wave", "qr_code", "touch_pass"]
}

private String normalizeYesNo(value) {
    def normalized = value?.toString()?.toLowerCase()
    return normalized in ["yes", "true", "on", "1"] ? "yes" :
           normalized in ["no", "false", "off", "0"] ? "no" : null
}

private String encodeSegment(String value) {
    java.net.URLEncoder.encode(value, "UTF-8")
}

private Map successfulPayload(response) {
    try {
        if (response?.hasError() || response?.status != 200) {
            return null
        }
        def payload = response.getJson()
        return payload instanceof Map && payload.code == "SUCCESS" ? payload : null
    } catch (Exception ignored) {
        return null
    }
}

private String actionResponseOutcome(response) {
    try {
        if (response?.hasError()) {
            return "indeterminate"
        }
        if (response?.status in [400, 401, 402, 403, 404, 429]) {
            return "failed"
        }
        if (response?.status != 200) {
            return "indeterminate"
        }
        def payload = response.getJson()
        if (!(payload instanceof Map) || !(payload.code instanceof String)) {
            return "indeterminate"
        }
        return payload.code != "SUCCESS" ? "failed" : payload.data == "success" ? "accepted" : "indeterminate"
    } catch (Exception ignored) {
        return "indeterminate"
    }
}

private Map requestParameters(String path) {
    [uri: "https://${settings.consoleHost?.trim()}:${settings.apiPort}${path}",
     headers: [Authorization: "Bearer ${settings.apiToken}"],
     timeout: 20,
     ignoreSSLIssues: settings.allowSelfSigned == true]
}

private boolean connectionReady() {
    def host = settings.consoleHost?.trim()
    def port
    try {
        port = settings.apiPort as Integer
    } catch (Exception ignored) {
        return false
    }
    return host && (host ==~ /(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:]+\])/) && port != null && port >= 1 && port <= 65535 && settings.apiToken
}

private String connectionFingerprint() {
    def connection = "${settings.consoleHost?.trim()}\u0000${settings.apiPort}\u0000${settings.apiToken}\u0000${settings.allowSelfSigned}"
    java.security.MessageDigest.getInstance("SHA-256").digest(connection.getBytes("UTF-8")).encodeHex().toString()
}

private int pollSeconds() {
    def interval = (settings.pollInterval ?: "60") as Integer
    return [30, 60, 120, 300].contains(interval) ? interval : 60
}

private long maxStatusAge() {
    (pollSeconds() + 20L) * 1000L
}

private Map doorOptions() {
    (state.doors ?: []).collectEntries { [(it.id): "${it.name} (${it.id})"] }
}

private String dniFor(String doorId) {
    "unifi-access:${app.id}:door:${doorId}"
}

private String deviceDniFor(String deviceId) {
    "unifi-access:${app.id}:device:${deviceId}"
}

private String controllerDni() {
    "unifi-access:${app.id}:controller"
}

private String doorbellDniFor(String deviceId) {
    "unifi-access:${app.id}:doorbell:${deviceId}"
}

private String renDniFor(String doorId) {
    "unifi-access:${app.id}:ren:${doorId}"
}

private String eventsDni() {
    "unifi-access:${app.id}:events"
}

private String doorIdFromDni(String deviceNetworkId) {
    deviceNetworkId?.tokenize(":")?.last()
}
