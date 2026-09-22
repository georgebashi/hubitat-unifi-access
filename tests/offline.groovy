@groovy.transform.Field GroovyClassLoader productionLoader = new GroovyClassLoader(Thread.currentThread().contextClassLoader)
@groovy.transform.Field Map<String, Class> productionClasses = [:]

class RecordingLog {
    List entries = []

    def debug(Object message) { entries << ['debug', message] }
    def info(Object message) { entries << ['info', message] }
    def warn(Object message) { entries << ['warn', message] }
    def error(Object message) { entries << ['error', message] }
}

class FakeResponse {
    Integer status
    Object data
    String content
    boolean error = false

    boolean hasError() { error }
    Object getJson() {
        if (error) throw new IllegalStateException('invalid response')
        data
    }
}

class FakeChild {
    String deviceNetworkId
    Map values = [:]
    List events = []

    Object currentValue(String name) { values[name] }
    def markStale() { events << 'stale'; values.healthStatus = 'stale' }
    def markOffline() { events << 'offline'; values.healthStatus = 'offline' }
    def applyDpsPosition(String position) { events << ['dps', position]; values.doorPosition = position; values.contact = position == 'open' ? 'open' : 'closed' }
    def configureAccessEvents() { events << 'events-configured' }
    def closeAccessEvents() { events << 'events-closed' }
    def setCommandStatus(String status) { events << ['command', status]; values.lastCommandStatus = status }
    def setHardwareHealth(String status, String relay, String position) {
        events << ['hardware', status, relay, position]
        values.healthStatus = status
        values.lockRelay = status == 'online' ? relay : 'unknown'
        values.doorPosition = status == 'online' ? position : 'unknown'
    }
    def setCommandAction(String action) { events << ['action', action]; values.lastCommandAction = action }
    def setLockRuleStatus(String status) { values.lockRuleStatus = status }
    def updateLockRule(Map rule) { events << ['lockRule', rule]; values.lockRuleStatus = 'available'; values.lockRule = rule.type }
    def updateAccessMethods(Map methods) { events << ['methods', methods]; values.accessMethodsStatus = 'available' }
    def setAccessMethodsStatus(String status) { values.accessMethodsStatus = status }
    def setDoorbellSupported(boolean supported) { values.doorbellSupported = supported ? 'yes' : 'no' }
    def setDoorbellEventsStatus(String status) { values.doorbellEventsStatus = status }
    def setGateSupported(boolean supported) { values.gateSupported = supported ? 'yes' : 'no' }
    def recordDoorbellEvent(Map event) { events << ['doorbell', event] }
    def setDoorHealth(String status) { events << ['doorHealth', status]; values.healthStatus = status }
    def recordRenEvent() { events << 'ren-ring'; values.lastRingAt = 'synthetic-time' }
    def updateEmergency(Map emergency) { events << ['emergency', emergency]; values.healthStatus = 'online' }
    def setEmergencyStatus(String status) { values.healthStatus = status }
    def updateDoor(Map door) { events << ['update', door]; setHardwareHealth(door.hardwareHealth ?: 'unverified', door.lockRelay, door.position) }
    def updateDevice(Map accessDevice) { events << ['updateDevice', accessDevice]; values.healthStatus = accessDevice.online ? 'online' : 'offline' }
}

class FakeClock {
    long value = 100000L
    long now() { value }
}

class FakeHubitatDevice {
    String deviceNetworkId
    Map currentValues = [:]
    Object currentValue(String name) { currentValues[name] }
}

def fail(String message) {
    throw new AssertionError(message)
}

def check(boolean condition, String message) {
    if (!condition) fail(message)
}

def webhookSignature(String secret, String body, long timestamp) {
    def mac = javax.crypto.Mac.getInstance('HmacSHA256')
    mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes('UTF-8'), 'HmacSHA256'))
    def digest = mac.doFinal("${timestamp}.${body}".getBytes('UTF-8')).encodeHex().toString()
    "t=${timestamp},v1=${digest}"
}

def fixture(String relativePath, Map settings = [:], Map initialState = [:], Map options = [:]) {
    def calls = [get: [], put: [], post: [], patch: [], events: [], notifications: [], responses: [], schedules: [], websocket: []]
    def children = options.children ?: []
    def clock = options.clock ?: new FakeClock()
    def appRef = options.app ?: [id: 'test-app', label: 'Test Access']
    def parent = options.parent ?: [refreshDoor: { String dni -> calls << ['refresh', dni] }, unlockDoor: { String dni -> calls << ['unlock', dni] }, notifyAccessEvent: { Map event -> calls.notifications << event }, accessEventsConnectionDetails: { -> [:] }]
    def binding = new Binding([
        settings: settings,
        state: initialState,
        request: options.request ?: [body: '', headers: [:]],
        log: new RecordingLog(),
        app: appRef,
        device: new FakeHubitatDevice(deviceNetworkId: options.dni ?: 'unifi-access:test-app:door:door-1'),
        parent: parent,
        interfaces: [webSocket: [
            connect: { String url, Map wsOptions -> calls.websocket << [url: url, options: wsOptions] },
            close: { -> null }
        ]],
        now: { -> clock.now() },
        getChildDevices: { -> children },
        getChildDevice: { String dni -> children.find { it.deviceNetworkId == dni } },
        addChildDevice: { String namespace, String name, String dni, Map params ->
            def child = new FakeChild(deviceNetworkId: dni)
            children << child
            child
        },
        deleteChildDevice: { String dni -> children.removeAll { it.deviceNetworkId == dni } },
        asynchttpGet: { String callback, Map params, Object context = null ->
            calls.get << [callback: callback, params: params, context: context]
        },
        asynchttpPut: { String callback, Map params, Object context = null ->
            calls.put << [callback: callback, params: params, context: context]
        },
        asynchttpPost: { String callback, Map params, Object context = null ->
            calls.post << [callback: callback, params: params, context: context]
        },
        asynchttpPatch: { String callback, Map params, Object context = null ->
            calls.patch << [callback: callback, params: params, context: context]
        },
        sendEvent: { Map event -> calls.events << event },
        render: { Map response -> calls.responses << response; response },
        runIn: { Integer seconds, String method, Map runOptions = [:] ->
            calls.schedules << [seconds: seconds, method: method, options: runOptions]
        },
        unschedule: { String method = null -> null },
        unsubscribe: { -> null },
        initialize: { -> null },
        definition: { Closure body -> body.delegate = new Expando(name: { Object ignored -> }, namespace: { Map ignored -> }); body() },
        metadata: { Closure body -> body() },
        preferences: { Closure body -> body() },
        section: { Object... ignored -> null },
        input: { Object... ignored -> null },
        paragraph: { Object... ignored -> null }
    ])
    def scriptClass = productionClasses[relativePath]
    if (!scriptClass) {
        scriptClass = productionLoader.parseClass(new File(relativePath))
        productionClasses[relativePath] = scriptClass
    }
    def script = scriptClass.getDeclaredConstructor().newInstance()
    script.binding = binding
    [script: script, binding: binding, calls: calls, children: children, clock: clock]
}

def requireSources() {
    ['apps/UnifiAccess.groovy', 'drivers/UnifiAccessDoor.groovy', 'drivers/UnifiAccessDevice.groovy', 'drivers/UnifiAccessEvents.groovy', 'drivers/UnifiAccessController.groovy'].each { path ->
        check(new File(path).isFile(), "Missing production source: $path")
    }
}

requireSources()

def appSettings = [consoleHost: 'console.local', apiPort: 12445, apiToken: 'test-token', pollInterval: '30']
def dni = 'unifi-access:test-app:door:door-1'
def deviceDni = 'unifi-access:test-app:device:hub-1'
def newApp = { Map settings = appSettings, List children = [] ->
    fixture('apps/UnifiAccess.groovy', settings, [:], [children: children])
}
def physicalApp = { String deviceType, Map enabledSettings = [:] ->
    def door = new FakeChild(deviceNetworkId: dni, values: [healthStatus: 'online', lockRelay: 'lock', lockRuleStatus: 'available', lockRule: 'schedule'])
    def accessDevice = new FakeChild(deviceNetworkId: deviceDni, values: [healthStatus: 'online', accessMethodsStatus: 'available'])
    def controller = new FakeChild(deviceNetworkId: 'unifi-access:test-app:controller', values: [healthStatus: 'online', lockdown: 'inactive', evacuation: 'inactive'])
    def result = newApp(appSettings + enabledSettings, [door, accessDevice, controller])
    result.script.state.generation = 1L
    result.script.state.initialized = true
    result.script.state.doorsFresh = true
    result.script.state.devicesFresh = true
    result.script.state.lastDoorsSuccess = result.clock.value
    result.script.state.lastDevicesSuccess = result.clock.value
    result.script.state.doors = [[id: 'door-1', name: 'Front Door', isBindHub: true, lockRelay: 'lock', position: 'close']]
    result.script.state.devices = [[id: 'hub-1', name: 'Access Device', type: deviceType, online: true, locationId: 'door-1', isHub: true]]
    result.script.state.deviceMethods = ['hub-1': [nfc: [enabled: 'yes'], pin_code: [enabled: 'yes'], face: [enabled: 'yes']]]
    result
}

def testDriverUnknownPosition = {
    def result = fixture('drivers/UnifiAccessDoor.groovy')
    result.script.updateDoor([isBindHub: true, hardwareHealth: 'online', lockRelay: 'lock', position: null])
    check(result.calls.events.find { it.name == 'doorPosition' }?.value == 'unknown', 'Missing door position must be reported as unknown.')
    check(!result.calls.events.any { it.name == 'contact' }, 'Missing door position must not publish a guessed contact state.')
    result.calls.events.clear()
    result.script.updateDoor([isBindHub: true, hardwareHealth: 'online', lockRelay: null, position: 'none'])
    check(result.calls.events.find { it.name == 'doorPosition' }?.value == 'none', 'Unknown non-empty position strings must be preserved for diagnosis.')
    check(result.calls.events.find { it.name == 'lockRelay' }?.value == 'unknown', 'Malformed relay values must be normalized to unknown.')
    check(!result.calls.events.any { it.name == 'contact' }, 'Unrecognized position values must not alter ContactSensor state.')
    result.calls.events.clear()
    result.script.setHardwareHealth('offline', 'unlock', 'open')
    check(result.calls.events.find { it.name == 'lockRelay' }?.value == 'unknown', 'Non-online health must clear the relay state.')
    check(result.calls.events.find { it.name == 'doorPosition' }?.value == 'unknown', 'Non-online health must clear the reported position.')
    check(!result.calls.events.any { it.name == 'contact' }, 'Offline updates must preserve the last known ContactSensor value.')
}

def testNativeLockCapability = {
    def doorSource = new File('drivers/UnifiAccessDoor.groovy').text
    check(doorSource.contains('capability "Lock"'), 'Door driver must declare the native Hubitat Lock capability.')
    def deviceSource = new File('drivers/UnifiAccessDevice.groovy').text
    check(deviceSource.contains('capability "Actuator"'), 'Generic UniFi Access device driver must declare native Actuator capability.')

    def delegated = []
    def parent = [
        unlockDoor: { String doorDni -> delegated << ['unlock', doorDni] },
        setDoorRule: { String doorDni, String rule, Object duration -> delegated << ['rule', doorDni, rule, duration] }
    ]
    def result = fixture('drivers/UnifiAccessDoor.groovy', [:], [:], [parent: parent, dni: dni])

    result.script.updateDoor([hardwareHealth: 'online', lockRelay: 'lock', position: 'unknown'])
    check(result.calls.events.find { it.name == 'lock' }?.value == 'locked', 'Observed locked relay must publish native locked state.')
    check(!result.calls.events.any { it.name == 'contact' }, 'Relay state must not fabricate physical contact position.')
    result.calls.events.clear()

    result.script.updateDoor([hardwareHealth: 'online', lockRelay: 'unlock', position: 'unknown'])
    check(result.calls.events.find { it.name == 'lock' }?.value == 'unlocked', 'Observed unlocked relay must publish native unlocked state.')
    result.calls.events.clear()

    result.script.markStale()
    check(result.calls.events.find { it.name == 'lock' }?.value == 'unknown', 'Stale relay status must set native lock state to unknown.')
    result.calls.events.clear()
    result.script.markOffline()
    check(result.calls.events.find { it.name == 'lock' }?.value == 'unknown', 'Offline relay status must set native lock state to unknown.')
    result.calls.events.clear()

    result.script.unlock()
    result.script.lock()
    check(delegated == [['unlock', dni], ['rule', dni, 'lock_now', null]], 'Native unlock and lock must delegate to existing momentary unlock and lock-now operations.')
    check(result.calls.events.isEmpty(), 'Lock commands must not optimistically publish native lock state.')
}

def testUnverifiedHardwareDevice = {
    def result = fixture('drivers/UnifiAccessDevice.groovy')
    result.script.updateDevice([online: null, type: 'UAH', adopted: null, connected: false])
    check(result.calls.events.find { it.name == 'healthStatus' }?.value == 'unverified', 'Missing hardware online status must remain unverified.')
    check(result.calls.events.find { it.name == 'adopted' }?.value == 'unknown', 'Missing adoption status must remain unknown.')
    check(result.calls.events.find { it.name == 'connected' }?.value == 'no', 'Explicit disconnected status must be reported as no.')
}

def testSanitizedDoorbellEventFlow = {
    def result = fixture('drivers/UnifiAccessEvents.groovy', [:], [connectionWanted: true, connected: true])
    def ring = [event: 'access.remote_view', data: [device_id: 'synthetic-reader-1', device_type: 'UA-G2-PRO', door_name: 'Front Door', request_id: 'synthetic-request-1', in_or_out: 'in', create_time: 1760000000, channel: 'private-channel', token: 'private-token', door_guard_ids: ['private-guard']]]
    result.script.parse(groovy.json.JsonOutput.toJson(ring))
    check(result.calls.notifications.size() == 1, 'Valid ring event must be forwarded to the parent.')
    def forwarded = result.calls.notifications[0]
    check(forwarded.deviceId == 'synthetic-reader-1' && forwarded.requestId == 'synthetic-request-1', 'Forwarded event must include device and request IDs.')
    check(!forwarded.containsKey('token') && !forwarded.containsKey('channel') && !forwarded.containsKey('door_guard_ids'), 'Forwarded event must exclude sensitive or unneeded raw payload fields.')
    result.script.parse(groovy.json.JsonOutput.toJson(ring))
    result.script.parse('{not-json')
    result.script.parse(groovy.json.JsonOutput.toJson([event: 'access.door.unlock', data: [id: 'synthetic-reader-1']]))
    check(result.calls.notifications.size() == 1, 'Duplicate, malformed, and unrelated events must be ignored.')
    result.script.parse(groovy.json.JsonOutput.toJson([event: 'access.remote_view.change', data: [remote_call_request_id: 'synthetic-request-1', reason_code: 108]]))
    check(result.calls.notifications.size() == 2, 'Completion must be forwarded only after a matching ring.')
    check(result.calls.notifications[1].deviceId == 'synthetic-reader-1' && result.calls.notifications[1].reasonCode == 108, 'Completion must resolve its device and preserve the allowed reason code.')
    result.script.parse(groovy.json.JsonOutput.toJson([event: 'access.remote_view.change', data: [remote_call_request_id: 'unknown-request', reason_code: 400]]))
    check(result.calls.notifications.size() == 2, 'Completion without a matching in-memory ring must be ignored.')
}

def testSignedWebhookValidationAndDpsDispatch = {
    def secret = 'synthetic-webhook-secret'
    def payload = [event: 'access.device.dps_status', event_object_id: 'synthetic-event-1', data: [
        location: [id: 'door-1', location_type: 'door'],
        device: [id: 'hub-1'],
        object: [event_type: 'dps_change', status: 'open', private_field: 'must-not-forward']
    ]]
    def raw = groovy.json.JsonOutput.toJson(payload)
    def child = new FakeChild(deviceNetworkId: dni, values: [healthStatus: 'online'])
    def valid = newApp(appSettings + [enableSignedWebhooks: true], [child])
    valid.script.state.webhookSecret = secret
    valid.script.state.doors = [[id: 'door-1', name: 'Front Door', isBindHub: true, position: 'close']]
    def validHeader = webhookSignature(secret, raw, 100L)
    def probe = newApp(appSettings + [enableSignedWebhooks: true])
    probe.script.state.webhookSecret = secret
    check(probe.script.validWebhookSignature(raw, validHeader), 'Harness HMAC helper must match production verification.')
    valid.binding.setVariable('request', [body: raw, headers: [Signature: validHeader]])
    def validResponse = valid.script.receiveAccessWebhook()
    check(validResponse.status == 200 && validResponse.data == '{"ok":true}', 'Valid signed webhook must return the actual success response.')
    check(valid.calls.responses.last().status == 200, 'Valid signed DPS webhook must receive HTTP 200.')
    check(child.events.any { it instanceof List && it[0] == 'dps' && it[1] == 'open' }, 'Valid DPS webhook must update the matching production door child.')
    check(valid.script.state.doors.first().position == 'open', 'Valid DPS webhook must update cached observed DPS position.')
    def dpsEvents = child.events.count { it instanceof List && it[0] == 'dps' }
    def replayResponse = valid.script.receiveAccessWebhook()
    check(replayResponse.status == 401 && replayResponse.data == '{"ok":false}', 'Replay must return the actual unauthorized HTTP response.')
    check(valid.calls.responses.last().status == 401, 'Replayed webhook signature must be rejected.')
    check(child.events.count { it instanceof List && it[0] == 'dps' } == dpsEvents, 'Replayed signature must not dispatch another physical event.')
    def duplicateBody = raw.replace('synthetic-event-1', 'synthetic-event-1')
    valid.binding.setVariable('request', [body: duplicateBody, headers: [Signature: webhookSignature(secret, duplicateBody, 101L)]])
    valid.script.receiveAccessWebhook()
    check(valid.calls.responses.last().status == 200, 'A fresh valid signature for a duplicate event should be acknowledged.')
    check(child.events.count { it instanceof List && it[0] == 'dps' } == dpsEvents, 'Duplicate event ID must not be applied twice.')

    def invalid = newApp(appSettings + [enableSignedWebhooks: true])
    invalid.script.state.webhookSecret = secret
    def validDigest = webhookSignature(secret, raw, 100L)
    def lastHex = validDigest[-1] == '0' ? '1' : '0'
    invalid.binding.setVariable('request', [body: raw, headers: [Signature: validDigest[0..-2] + lastHex]])
    def invalidResponse = invalid.script.receiveAccessWebhook()
    check(invalidResponse.status == 401 && invalidResponse.data == '{"ok":false}', 'Invalid signature must return the actual unauthorized HTTP response.')
    check(invalid.calls.responses.last().status == 401, 'Invalid webhook signature must be rejected.')

    def expired = newApp(appSettings + [enableSignedWebhooks: true])
    expired.script.state.webhookSecret = secret
    expired.clock.value = 1000000L
    expired.binding.setVariable('request', [body: raw, headers: [Signature: webhookSignature(secret, raw, 1L)]])
    def expiredResponse = expired.script.receiveAccessWebhook()
    check(expiredResponse.status == 401 && expiredResponse.data == '{"ok":false}', 'Expired signature must return the actual unauthorized HTTP response.')
    check(expired.calls.responses.last().status == 401, 'Expired webhook timestamp must be rejected.')

    def unknown = newApp(appSettings + [enableSignedWebhooks: true])
    unknown.script.state.webhookSecret = secret
    def unknownBody = groovy.json.JsonOutput.toJson([event: 'access.user.created', event_object_id: 'synthetic-unknown-1', data: [:]])
    unknown.binding.setVariable('request', [body: unknownBody, headers: [Signature: webhookSignature(secret, unknownBody, 100L)]])
    unknown.script.receiveAccessWebhook()
    check(unknown.calls.responses.last().status == 200, 'Valid webhook with an unknown event must be acknowledged without processing it.')
    check(unknown.script.state.doors == null && unknown.calls.get.isEmpty(), 'Unknown webhook event must not alter state or request a status refresh.')
}

def testDpsEventWinsAgainstOlderPoll = {
    def secret = 'synthetic-webhook-secret'
    def child = new FakeChild(deviceNetworkId: dni, values: [healthStatus: 'online', contact: 'closed'])
    def result = newApp(appSettings + [enableSignedWebhooks: true], [child])
    result.script.state.webhookSecret = secret
    result.script.installed()
    result.script.state.doors = [[id: 'door-1', name: 'Front Door', isBindHub: true, lockRelay: 'lock', position: 'close']]
    result.script.state.devices = [[id: 'hub-1', locationId: 'door-1', isHub: true, online: true]]
    def oldContext = result.calls.get.findAll { it.callback == 'doorsCallback' }.last().context
    oldContext.startedAt = result.clock.value - 1000L
    def eventBody = groovy.json.JsonOutput.toJson([event: 'access.device.dps_status', event_object_id: 'synthetic-order-event', data: [
        location: [id: 'door-1', location_type: 'door'],
        object: [event_type: 'dps_change', status: 'open']
    ]])
    result.binding.setVariable('request', [body: eventBody, headers: [Signature: webhookSignature(secret, eventBody, 100L)]])
    result.script.receiveAccessWebhook()
    result.script.doorsCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: [
        [id: 'door-1', name: 'Front Door', is_bind_hub: true, door_lock_relay_status: 'lock', door_position_status: 'close']
    ]]), oldContext)
    check(result.script.state.doors.first().position == 'open', "Poll started before DPS event must not overwrite the newer position; state=${result.script.state.doors}, dps=${result.script.state.lastDpsEvents}, response=${result.calls.responses.last()}")
    check(child.values.contact == 'open', 'Older poll reconciliation must preserve the newer ContactSensor event.')
}

def testExternalUnlockRelayReconciliation = {
    def secret = 'synthetic-webhook-secret'
    def child = new FakeChild(deviceNetworkId: dni, values: [healthStatus: 'online', lockRelay: 'lock'])
    def result = newApp(appSettings + [enableSignedWebhooks: true], [child])
    result.script.state.webhookSecret = secret
    result.script.state.generation = 1L
    result.script.state.initialized = true
    result.script.state.doors = [[id: 'door-1', name: 'Front Door', isBindHub: true, lockRelay: 'lock', position: 'close']]
    result.script.state.doorsFresh = true
    result.script.state.devicesFresh = true
    result.script.state.lastDoorsSuccess = result.clock.value
    result.script.state.lastDevicesSuccess = result.clock.value
    result.script.state.devices = [[id: 'hub-1', locationId: 'door-1', isHub: true, online: true]]

    def sendUnlock = { String eventId ->
        def body = groovy.json.JsonOutput.toJson([event: 'access.door.unlock', event_object_id: eventId, data: [
            location: [id: 'door-1', location_type: 'door']
        ]])
        result.binding.setVariable('request', [body: body, headers: [Signature: webhookSignature(secret, body, 100L)]])
        result.script.receiveAccessWebhook()
    }
    def completeDoorRead = { Object relay ->
        def context = result.calls.get.findAll { it.callback == 'doorsCallback' }.last().context
        result.script.doorsCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: [
            [id: 'door-1', name: 'Front Door', is_bind_hub: true, door_lock_relay_status: relay, door_position_status: 'close']
        ]]), context)
    }

    sendUnlock('synthetic-unlock-event-1')
    check(result.calls.get.count { it.callback == 'doorsCallback' } == 1, 'External unlock webhook must request an immediate observed door read.')
    completeDoorRead('lock')
    check(result.script.state.relayRechecks['door-1'] != null, 'An early locked read must not end reconciliation before the relay has had time to change.')

    result.clock.value += 2000L
    result.script.reconcileRelayState()
    check(result.calls.get.count { it.callback == 'doorsCallback' } == 2, 'A delayed follow-up read must run after an early locked response.')
    completeDoorRead('unlock')
    check(child.values.lockRelay == 'unlock', 'Follow-up GET must publish the externally observed unlocked relay state.')
    check(result.script.state.relayRechecks['door-1']?.observedUnlock == true, 'Observed unlock must keep a bounded recheck active until relock is observed.')

    result.clock.value += 2000L
    result.script.reconcileRelayState()
    check(result.calls.get.count { it.callback == 'doorsCallback' } == 3, 'Reconciliation must verify the relay again after observing unlock.')
    completeDoorRead('lock')
    check(child.values.lockRelay == 'lock', 'Auto-lock GET must publish the observed locked relay state.')
    check(!result.script.state.relayRechecks.containsKey('door-1'), 'Observed relock after the unlock must stop follow-up reads.')

    def pendingChild = new FakeChild(deviceNetworkId: dni, values: [healthStatus: 'online', lockRelay: 'lock'])
    def pending = newApp(appSettings + [enableSignedWebhooks: true], [pendingChild])
    pending.script.state.webhookSecret = secret
    pending.script.state.generation = 1L
    pending.script.state.initialized = true
    pending.script.state.doors = [[id: 'door-1', name: 'Front Door', isBindHub: true, lockRelay: 'lock', position: 'close']]
    pending.script.state.doorsFresh = true
    pending.script.state.devicesFresh = true
    pending.script.state.lastDoorsSuccess = pending.clock.value
    pending.script.state.lastDevicesSuccess = pending.clock.value
    pending.script.state.devices = [[id: 'hub-1', locationId: 'door-1', isHub: true, online: true]]
    pending.script.requestDoors()
    def oldContext = pending.calls.get.last().context
    def priorStartedAt = pending.script.state.doorsInFlight.startedAt
    def pendingBody = groovy.json.JsonOutput.toJson([event: 'access.door.unlock', event_object_id: 'synthetic-unlock-event-2', data: [
        location: [id: 'door-1', location_type: 'door']
    ]])
    pending.binding.setVariable('request', [body: pendingBody, headers: [Signature: webhookSignature(secret, pendingBody, 100L)]])
    pending.script.receiveAccessWebhook()
    def armedAt = pending.script.state.relayRechecks['door-1'].startedAt
    check(armedAt == priorStartedAt, 'An unlock event during an in-flight read must arm a follow-up without restarting on duplicate triggers.')
    check(pending.calls.get.count { it.callback == 'doorsCallback' } == 1, 'An in-flight door GET must be coalesced rather than duplicated immediately.')

    pending.clock.value += 2000L
    pending.script.reconcileRelayState()
    pending.script.doorsCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: [
        [id: 'door-1', name: 'Front Door', is_bind_hub: true, door_lock_relay_status: 'lock', door_position_status: 'close']
    ]]), oldContext)
    check(pending.script.state.relayRechecks['door-1'] != null, 'A pre-event in-flight locked response must not cancel a pending post-event follow-up.')
    pending.clock.value = pending.script.state.relayRechecks['door-1'].nextAt as Long
    pending.script.reconcileRelayState()
    check(pending.calls.get.count { it.callback == 'doorsCallback' } == 2, 'A follow-up GET must run after the older in-flight read completes.')
    def followupContext = pending.calls.get.last().context
    pending.script.doorsCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: [
        [id: 'door-1', name: 'Front Door', is_bind_hub: true, door_lock_relay_status: 'unlock', door_position_status: 'close']
    ]]), followupContext)
    check(pendingChild.values.lockRelay == 'unlock', 'Follow-up reads after a coalesced request must publish observed external unlock state.')

    def persistentChild = new FakeChild(deviceNetworkId: dni, values: [healthStatus: 'online', lockRelay: 'lock'])
    def persistent = newApp(appSettings + [enableSignedWebhooks: true], [persistentChild])
    persistent.script.state.webhookSecret = secret
    persistent.script.state.generation = 1L
    persistent.script.state.initialized = true
    persistent.script.state.doors = [[id: 'door-1', name: 'Front Door', isBindHub: true, lockRelay: 'lock', position: 'close']]
    persistent.script.state.doorsFresh = true
    persistent.script.state.devicesFresh = true
    persistent.script.state.lastDoorsSuccess = persistent.clock.value
    persistent.script.state.lastDevicesSuccess = persistent.clock.value
    persistent.script.state.devices = [[id: 'hub-1', locationId: 'door-1', isHub: true, online: true]]
    def persistentBody = groovy.json.JsonOutput.toJson([event: 'access.door.unlock', event_object_id: 'synthetic-unlock-event-3', data: [
        location: [id: 'door-1', location_type: 'door']
    ]])
    persistent.binding.setVariable('request', [body: persistentBody, headers: [Signature: webhookSignature(secret, persistentBody, 100L)]])
    persistent.script.receiveAccessWebhook()
    def initialPersistentContext = persistent.calls.get.last().context
    persistent.script.doorsCallback(new FakeResponse(error: true), initialPersistentContext)
    check(persistent.script.state.relayRechecks['door-1'] != null, 'A failed immediate status read must preserve scheduled reconciliation.')
    check(persistentChild.values.lockRelay == 'lock', 'A failed status read must not guess that the relay unlocked.')
    def persistentTimes = [2000L, 4000L, 6000L, 8000L, 10000L, 15000L, 20000L, 30000L, 45000L, 60000L]
    persistentTimes.each { elapsed ->
        persistent.clock.value = 100000L + elapsed
        persistent.script.reconcileRelayState()
        def context = persistent.calls.get.findAll { it.callback == 'doorsCallback' }.last().context
        persistent.script.doorsCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: [
            [id: 'door-1', name: 'Front Door', is_bind_hub: true, door_lock_relay_status: 'unlock', door_position_status: 'close']
        ]]), context)
    }
    def readCount = persistent.calls.get.count { it.callback == 'doorsCallback' }
    persistent.clock.value = 165000L
    persistent.script.reconcileRelayState()
    check(readCount == 11, 'Persistent unlocked status must receive only the immediate read and the finite scheduled attempts.')
    check(!persistent.script.state.relayRechecks.containsKey('door-1'), 'Persistent unlock reconciliation must expire instead of rearming an endless fast loop.')

    def observedOnly = newApp(appSettings)
    observedOnly.script.state.generation = 1L
    observedOnly.script.state.initialized = true
    observedOnly.script.state.doors = [[id: 'door-1', name: 'Front Door', isBindHub: true, lockRelay: 'lock', position: 'close']]
    observedOnly.script.requestDoors()
    def observedContext = observedOnly.calls.get.last().context
    observedOnly.script.doorsCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: [
        [id: 'door-1', name: 'Front Door', is_bind_hub: true, door_lock_relay_status: 'unlock', door_position_status: 'close']
    ]]), observedContext)
    check(observedOnly.script.state.relayRechecks['door-1']?.observedUnlock == true, 'A newly observed relay unlock must arm fallback reconciliation without a webhook.')
    def fallbackTimes = [2000L, 4000L, 6000L, 8000L, 10000L, 15000L, 20000L, 30000L, 45000L, 60000L]
    fallbackTimes.each { elapsed ->
        observedOnly.clock.value = 100000L + elapsed
        observedOnly.script.reconcileRelayState()
        def context = observedOnly.calls.get.findAll { it.callback == 'doorsCallback' }.last().context
        observedOnly.script.doorsCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: [
            [id: 'door-1', name: 'Front Door', is_bind_hub: true, door_lock_relay_status: 'unlock', door_position_status: 'close']
        ]]), context)
    }
    def fallbackReadCount = observedOnly.calls.get.count { it.callback == 'doorsCallback' }
    observedOnly.clock.value = 165000L
    observedOnly.script.reconcileRelayState()
    check(fallbackReadCount == 11, 'Observation-only unlock fallback must issue a finite set of follow-up reads.')
    check(!observedOnly.script.state.relayRechecks.containsKey('door-1'), 'An unchanged observed unlock must not re-arm reconciliation after its deadline.')

    def obsolete = newApp(appSettings + [enableSignedWebhooks: true])
    obsolete.script.state.webhookSecret = secret
    obsolete.script.state.generation = 1L
    obsolete.script.state.initialized = true
    obsolete.script.state.doors = [[id: 'door-1', name: 'Front Door', isBindHub: true, lockRelay: 'lock', position: 'close']]
    def obsoleteBody = groovy.json.JsonOutput.toJson([event: 'access.door.unlock', event_object_id: 'synthetic-unlock-event-4', data: [
        location: [id: 'door-1', location_type: 'door']
    ]])
    obsolete.binding.setVariable('request', [body: obsoleteBody, headers: [Signature: webhookSignature(secret, obsoleteBody, 100L)]])
    obsolete.script.receiveAccessWebhook()
    obsolete.script.updated()
    def readsAfterUpdate = obsolete.calls.get.count { it.callback == 'doorsCallback' }
    obsolete.script.reconcileRelayState()
    check(obsolete.calls.get.count { it.callback == 'doorsCallback' } == readsAfterUpdate, 'A scheduled recheck from an earlier app generation must not start another request.')
    check(obsolete.script.state.relayRechecks.isEmpty(), 'Reinitialization must clear pending relay reconciliation state.')
}

def testRequestToEnterWebhook = {
    def secret = 'synthetic-webhook-secret'
    def eventBody = groovy.json.JsonOutput.toJson([event: 'access.doorbell.incoming.REN', event_object_id: 'synthetic-ren-event', data: [
        location: [id: 'door-1', location_type: 'door'],
        device: null,
        object: [request_id: 'synthetic-ren-request']
    ]])
    def settings = appSettings + [enableSignedWebhooks: true]
    def result = newApp(settings)
    result.script.state.webhookSecret = secret
    result.script.state.doors = [[id: 'door-1', name: 'Front Door', isBindHub: true, lockRelay: 'lock', position: 'close']]
    result.script.state.doorsFresh = true
    result.script.state.devicesFresh = true
    result.script.state.lastDoorsSuccess = result.clock.value
    result.script.state.lastDevicesSuccess = result.clock.value
    result.script.state.devices = [[id: 'hub-1', locationId: 'door-1', isHub: true, online: true]]

    result.binding.setVariable('request', [body: eventBody, headers: [Signature: webhookSignature(secret, eventBody, 100L)]])
    result.script.receiveAccessWebhook()
    def renDni = 'unifi-access:test-app:ren:door-1'
    def renChild = result.children.find { it.deviceNetworkId == renDni }
    check(renChild != null, 'A valid REN event for a cached door must lazily create a stable request-to-enter child.')
    check(renChild.events.any { it == 'ren-ring' }, 'A valid REN event must emit one push event.')
    check(!result.children.any { it.deviceNetworkId == deviceDni && it.events.any { event -> event instanceof List && event[0] == 'doorbell' } },
        'REN with null device must not be treated as a generic reader doorbell event.')

    result.binding.setVariable('request', [body: eventBody, headers: [Signature: webhookSignature(secret, eventBody, 101L)]])
    result.script.receiveAccessWebhook()
    check(renChild.events.count { it == 'ren-ring' } == 1, 'Repeated REN request IDs must be deduplicated.')

    def excluded = newApp(settings + [excludedDoorIds: ['door-1']])
    excluded.script.state.webhookSecret = secret
    excluded.script.state.doors = [[id: 'door-1', name: 'Front Door', isBindHub: true]]
    excluded.binding.setVariable('request', [body: eventBody, headers: [Signature: webhookSignature(secret, eventBody, 100L)]])
    excluded.script.receiveAccessWebhook()
    check(excluded.children.isEmpty(), 'REN events for excluded doors must not create children.')
}

def testUnlockDefaultEnabled = {
    def child = new FakeChild(deviceNetworkId: dni, values: [healthStatus: 'online', lockRelay: 'lock'])
    def enabled = newApp(appSettings + [allowUnlock: false], [child])
    enabled.script.state.generation = 4L
    enabled.script.state.doors = [[id: 'door-1', isBindHub: true]]
    enabled.script.state.doorsFresh = true
    enabled.script.state.devicesFresh = true
    enabled.script.state.lastDoorsSuccess = enabled.clock.value
    enabled.script.state.lastDevicesSuccess = enabled.clock.value
    enabled.script.state.devices = [[id: 'hub-1', locationId: 'door-1', online: true]]
    enabled.script.state.doors = [[id: 'door-1', isBindHub: true, lockRelay: 'lock', position: 'close']]
    enabled.script.unlockDoor(dni)
    check(enabled.calls.put.size() == 1, 'Unlock must issue a request by default for a healthy bound door, even with a legacy false setting.')
    def context = enabled.calls.put.last().context
    enabled.script.unlockCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: 'success']), context)
    check(child.values.lastCommandStatus == 'accepted', 'Successful unlock API response must be reported as accepted.')
    check(child.values.lockRelay == 'lock', 'Accepted command must not predict a physical relay transition.')

    def rejected = newApp(appSettings + [allowUnlock: false], [child])
    rejected.script.state.generation = 4L
    rejected.script.state.doorsFresh = true
    rejected.script.state.devicesFresh = true
    rejected.script.state.lastDoorsSuccess = rejected.clock.value
    rejected.script.state.lastDevicesSuccess = rejected.clock.value
    rejected.script.state.devices = [[id: 'hub-1', locationId: 'door-1', online: true]]
    rejected.script.state.doors = [[id: 'door-1', isBindHub: true, lockRelay: 'lock', position: 'close']]
    rejected.script.unlockDoor(dni)
    rejected.script.unlockCallback(new FakeResponse(status: 403, data: [code: 'ERROR']), rejected.calls.put.last().context)
    check(child.values.lastCommandStatus == 'failed' && child.values.lockRelay == 'lock', 'Definite unlock rejection must fail without predicting relay state.')

    def uncertain = newApp(appSettings + [allowUnlock: false], [child])
    uncertain.script.state.generation = 4L
    uncertain.script.state.doorsFresh = true
    uncertain.script.state.devicesFresh = true
    uncertain.script.state.lastDoorsSuccess = uncertain.clock.value
    uncertain.script.state.lastDevicesSuccess = uncertain.clock.value
    uncertain.script.state.devices = [[id: 'hub-1', locationId: 'door-1', online: true]]
    uncertain.script.state.doors = [[id: 'door-1', isBindHub: true, lockRelay: 'lock', position: 'close']]
    uncertain.script.unlockDoor(dni)
    uncertain.script.unlockCallback(new FakeResponse(status: 503, data: [code: 'ERROR']), uncertain.calls.put.last().context)
    check(child.values.lastCommandStatus == 'indeterminate' && child.values.lockRelay == 'lock', 'Server failure must remain indeterminate without predicting relay state.')
}

def testLockRuleDefaultEnabledAndEnvelope = {
    def enabled = physicalApp('UAH', [allowHardwareControl: false])
    def door = enabled.children.find { it.deviceNetworkId == dni }
    enabled.script.setDoorRule(dni, 'custom', 10)
    check(!enabled.calls.put.isEmpty(), 'Supported lock-rule request must issue a PUT by default despite a legacy false setting.')
    def request = enabled.calls.put.last().params
    check(request.uri.contains('/doors/door-1/lock_rule'), 'Lock-rule request must target the selected door.')
    check(new groovy.json.JsonSlurper().parseText(request.body) == [type: 'custom', interval: 10], 'Custom rule body must preserve the positive interval.')
    enabled.script.actionCallback(new FakeResponse(status: 200, data: [code: 'ERROR', msg: 'invalid', data: 'success']), enabled.calls.put.last().context)
    check(door.values.lastCommandStatus == 'failed' && door.values.lockRule == 'schedule', 'Explicit API rejection must be failed and must not update observed state.')
    def malformed = physicalApp('UAH', [allowHardwareControl: true])
    malformed.script.setDoorRule(dni, 'keep_lock', null)
    malformed.script.actionCallback(new FakeResponse(status: 200, error: true), malformed.calls.put.last().context)
    check(malformed.children.find { it.deviceNetworkId == dni }.values.lastCommandStatus == 'indeterminate',
        'Malformed HTTP 200 action response must be indeterminate, not accepted or definitely failed.')
    def invalid = physicalApp('UAH', [allowHardwareControl: true])
    invalid.script.setDoorRule(dni, 'custom', 0)
    invalid.script.setDoorRule(dni, 'invented', null)
    check(invalid.calls.put.isEmpty(), 'Invalid lock-rule values and custom durations must be rejected.')
}

def testGateModelAndDirectionGates = {
    def gate = physicalApp('UGT', [allowHardwareControl: false])
    def gateDoor = gate.children.find { it.deviceNetworkId == dni }
    gateDoor.values.gateControlPreference = 'yes'
    gate.script.gateCommand(dni, 'open', null)
    def request = gate.calls.put.last()
    check(request.params.uri.contains('/doors/door-1/unlock?control_cmd=open'), 'Gate open must use the gate-specific query command.')
    check(new groovy.json.JsonSlurper().parseText(request.params.body) == [:], 'Gate action must not invent a body payload.')
    gate.script.actionCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: 'success']), request.context)
    gate.script.gateCommand(dni, null, 'in')
    check(gate.calls.put.last().params.uri.contains('?entry_method=in'), 'Gate direction must map to entry_method.')
    gate.script.gateCommand(dni, 'close', 'out')
    check(gate.calls.put.size() == 2, 'Ambiguous action and direction combinations must be rejected.')
    def defaultEnabled = physicalApp('anything', [allowHardwareControl: false])
    defaultEnabled.script.gateCommand(dni, 'open', null)
    check(defaultEnabled.calls.put.size() == 1, 'Gate commands must be enabled for an active door even with an unknown model and legacy false setting.')
}

def testReaderMethodCapabilityAndValidation = {
    def enabled = physicalApp('UA-G2-PRO', [allowAccessMethodChanges: false])
    enabled.script.setAccessMethod(deviceDni, 'nfc', 'false')
    def request = enabled.calls.put.last()
    check(request.params.uri.contains('/devices/hub-1/settings'), 'Reader update must target its settings endpoint.')
    check(new groovy.json.JsonSlurper().parseText(request.params.body) == [access_methods: [nfc: [enabled: 'no']]], 'Boolean-like input must normalize to the documented yes/no enum.')
    enabled.script.actionCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: 'success']), request.context)
    def child = enabled.children.find { it.deviceNetworkId == deviceDni }
    check(child.values.accessMethodsStatus == 'available', 'Command acceptance must not update observed access-method settings.')
    enabled.script.setPinShuffle(deviceDni, 'true')
    check(new groovy.json.JsonSlurper().parseText(enabled.calls.put.last().params.body) == [access_methods: [pin_code: [pin_code_shuffle: 'yes']]], 'PIN shuffle must use the nested API shape.')
    enabled.script.actionCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: 'success']), enabled.calls.put.last().context)
    enabled.script.setFaceSensitivity(deviceDni, 'high', 'near')
    check(new groovy.json.JsonSlurper().parseText(enabled.calls.put.last().params.body) == [access_methods: [face: [anti_spoofing_level: 'high', detect_distance: 'near']]], 'Supported face enums must be preserved.')
    enabled.script.actionCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: 'success']), enabled.calls.put.last().context)
    def count = enabled.calls.put.size()
    enabled.script.state.deviceMethods['hub-1'].pin_code.enabled = 'no'
    enabled.script.setAccessMethod(deviceDni, 'not_returned_by_device', 'yes')
    enabled.script.setPinShuffle(deviceDni, 'yes')
    enabled.script.setFaceSensitivity(deviceDni, 'medium', 'far')
    check(enabled.calls.put.size() == count, 'Unreturned methods, disabled PIN, and unsupported face combinations must be rejected.')
}

def testCapabilityProbeFailureAndArbitraryTypes = {
    def arbitrary = physicalApp('Vendor-Model-X', [allowAccessMethodChanges: false, allowHardwareControl: false])
    arbitrary.script.state.deviceMethods = [:]
    def accessDevice = arbitrary.children.find { it.deviceNetworkId == deviceDni }
    accessDevice.values.accessMethodsStatus = 'unknown'
    arbitrary.script.queueDetail('methods', 'hub-1', true)
    def settingsRead = arbitrary.calls.get.last()
    check(settingsRead.params.uri.contains('/devices/hub-1/settings'), 'Capability discovery must GET the reader settings endpoint for arbitrary reported models.')
    arbitrary.script.detailCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: [access_methods: [nfc: [enabled: 'yes']]]]), settingsRead.context)
    check(accessDevice.values.accessMethodsStatus == 'available', 'Successful settings read must establish supported-method evidence independent of SKU.')
    arbitrary.script.setAccessMethod(deviceDni, 'nfc', 'no')
    check(arbitrary.calls.put.size() == 1, 'A method returned by the capability GET may be changed on an arbitrary model.')

    def forbidden = physicalApp('Vendor-Model-X', [allowAccessMethodChanges: false])
    forbidden.script.state.deviceMethods = [:]
    def forbiddenChild = forbidden.children.find { it.deviceNetworkId == deviceDni }
    forbiddenChild.values.accessMethodsStatus = 'unknown'
    forbidden.script.queueDetail('methods', 'hub-1', true)
    def forbiddenRead = forbidden.calls.get.last()
    forbidden.script.detailCallback(new FakeResponse(status: 403, data: [code: 'ERROR']), forbiddenRead.context)
    check(forbiddenChild.values.accessMethodsStatus == 'forbidden', 'HTTP 403 must remain a permission failure, not imply unsupported hardware.')
    forbidden.script.setAccessMethod(deviceDni, 'nfc', 'no')
    check(forbidden.calls.put.isEmpty(), 'Forbidden capability reads must never authorize a mutation.')

    def timeout = physicalApp('Vendor-Model-X', [allowAccessMethodChanges: false])
    timeout.script.state.deviceMethods = [:]
    def timeoutChild = timeout.children.find { it.deviceNetworkId == deviceDni }
    timeoutChild.values.accessMethodsStatus = 'unknown'
    timeout.script.queueDetail('methods', 'hub-1', true)
    def timeoutRead = timeout.calls.get.last()
    timeout.script.detailCallback(new FakeResponse(error: true), timeoutRead.context)
    check(timeoutChild.values.accessMethodsStatus == 'unknown', 'Transport failure must preserve unknown capability instead of claiming unsupported.')
    timeout.script.setAccessMethod(deviceDni, 'nfc', 'no')
    check(timeout.calls.put.isEmpty(), 'Timeout must not authorize writes or trigger a mutating capability probe.')
    check(timeout.calls.post.isEmpty() && timeout.calls.patch.isEmpty(), 'Capability discovery must not send POST/PATCH mutation probes.')
}

def testObservedDoorbellEvidence = {
    def result = physicalApp('Vendor-Model-X', [allowHardwareControl: false])
    def device = result.children.find { it.deviceNetworkId == deviceDni }
    result.script.triggerDoorbell(deviceDni, 'Lobby')
    check(result.calls.post.size() == 1, 'Doorbell commands must be enabled for an active device even when its model is unknown.')
    def fingerprint = result.script.connectionFingerprint()
    result.script.notifyAccessEvent([event: 'access.remote_view', fingerprint: fingerprint,
        deviceId: 'hub-1', requestId: 'synthetic-ring-event', direction: 'in'])
    check(device.values.doorbellEventsStatus == 'detected', 'A verified observed ring must establish doorbell-event evidence.')
    def doorbell = result.children.find { it.deviceNetworkId == 'unifi-access:test-app:doorbell:hub-1' }
    check(doorbell != null, 'Observed ring must lazily create a doorbell event child regardless of device SKU.')
    check(doorbell.events.any { it instanceof List && it[0] == 'doorbell' }, 'Observed ring must update the event child, not be inferred from command acceptance.')
    check(result.calls.post.size() == 1 && result.calls.put.isEmpty(), 'Doorbell capability use must send only the requested command, not a probe.')
}

def testDoorbellModelGateAndNoOptimism = {
    def nonReader = physicalApp('UGT', [allowHardwareControl: false])
    nonReader.script.triggerDoorbell(deviceDni, 'Lobby')
    check(nonReader.calls.post.size() == 1, 'Doorbell must be enabled for an active device without capability opt-in.')
    def reader = physicalApp('unknown-reader-type', [allowHardwareControl: false])
    reader.script.triggerDoorbell(deviceDni, 'Lobby')
    def request = reader.calls.post.last()
    check(request.params.uri.contains('/devices/hub-1/doorbell'), 'Doorbell trigger must target the selected reader.')
    check(new groovy.json.JsonSlurper().parseText(request.params.body) == [room_name: 'Lobby', cancel: false], 'Trigger must send the requested room and explicit cancel false.')
    reader.script.actionCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: 'success']), request.context)
    def child = reader.children.find { it.deviceNetworkId == deviceDni }
    check(child.values.doorbellStatus == null, 'API acceptance must not claim an observed ring event.')
    reader.script.cancelDoorbell(deviceDni)
    check(new groovy.json.JsonSlurper().parseText(reader.calls.post.last().params.body) == [cancel: true], 'Doorbell cancel must explicitly set cancel true.')
    def count = reader.calls.post.size()
    reader.script.triggerDoorbell(deviceDni, 'Lobby' + ((char) 10) + 'Injected')
    check(reader.calls.post.size() == count, 'Control characters in a room name must be rejected.')
    def legacyDisabled = physicalApp('UA-G2-PRO', [allowHardwareControl: false])
    legacyDisabled.script.triggerDoorbell(deviceDni, 'Lobby')
    check(legacyDisabled.calls.post.size() == 1, 'Doorbell commands must be enabled by default without a per-device opt-in.')
}

def testEmergencyControlDefaultEnabledAndNonOptimistic = {
    def enabled = physicalApp('UAH', [allowEmergencyControl: false])
    enabled.script.setEmergency('evacuation')
    def request = enabled.calls.put.last()
    check(request.params.uri.endsWith('/doors/settings/emergency'), 'Emergency mode must use the global endpoint.')
    check(new groovy.json.JsonSlurper().parseText(request.params.body) == [lockdown: false, evacuation: true], 'Evacuation must explicitly clear lockdown.')
    def controller = enabled.children.find { it.deviceNetworkId.endsWith(':controller') }
    enabled.script.actionCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: 'success']), request.context)
    check(controller.values.evacuation == 'inactive', 'Accepted action must not become observed emergency state before a read.')
}

def testApiAndParseFailures = {
    [
        [status: 503, data: [code: 'SUCCESS', data: []]],
        [status: 200, data: [code: 'SUCCESS', data: 'not-a-list']],
        [status: 200, data: null, error: true]
    ].each { responseData ->
        def child = new FakeChild(deviceNetworkId: dni, values: [healthStatus: 'online'])
        def result = newApp(appSettings, [child])
        result.script.installed()
        def context = result.calls.get.findAll { it.callback == 'doorsCallback' }.last().context
        result.script.doorsCallback(new FakeResponse(responseData), context)
        check(result.script.state.doorsInFlight == null, 'Failed door response must clear the in-flight request.')
        check(child.events.contains('stale'), 'Failed or unparsable API response must mark existing children stale.')
        check(result.script.state.doors == [], 'Failed API response must not replace the last known door list.')
    }
}

def testStaleCallbackIgnored = {
    def child = new FakeChild(deviceNetworkId: dni, values: [healthStatus: 'online'])
    def result = newApp(appSettings, [child])
    result.script.installed()
    def oldContext = result.calls.get.findAll { it.callback == 'doorsCallback' }.last().context
    result.script.updated()
    def eventCount = child.events.size()
    result.script.doorsCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: [[id: 'door-1', name: 'Front']]]), oldContext)
    check(child.events.size() == eventCount, 'Callback from an earlier app generation must not mutate children.')
    check(result.script.state.doors == [], 'Callback from an earlier app generation must not replace current door state.')
}

def testStaleResponseDeadline = {
    def child = new FakeChild(deviceNetworkId: dni, values: [healthStatus: 'online'])
    def result = newApp(appSettings, [child])
    result.script.installed()
    def oldContext = result.calls.get.findAll { it.callback == 'doorsCallback' }.last().context
    result.clock.value += 30001L
    result.script.doorsCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: [[id: 'door-1', name: 'Front', is_bind_hub: true]]]), oldContext)
    check(result.script.state.doors == [], 'Late door response must not replace current state.')
    check(result.script.state.doorsFresh != true, 'Late door response must not mark door status fresh.')
    check(child.events.contains('stale'), 'Late door response must leave the child stale.')
}

def testNestedDevicesAndMissingOffline = {
    def hardware = new FakeChild(deviceNetworkId: deviceDni, values: [healthStatus: 'online'])
    def nested = newApp(appSettings, [hardware])
    nested.script.installed()
    def context = nested.calls.get.findAll { it.callback == 'devicesCallback' }.last().context
    nested.script.devicesCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: [[
        [id: 'hub-1', name: 'Main Hub', alias: 'Entry Hub', type: 'UAH', is_online: true, is_connected: true, is_adopted: true]
    ]]]), context)
    check(nested.script.state.devices.size() == 1, 'Nested device response must flatten to one discovered hardware device.')
    check(nested.script.state.devices[0].name == 'Entry Hub', 'Device discovery must prefer the hardware alias when present.')
    check(hardware.events.any { it instanceof List && it[0] == 'updateDevice' }, 'Discovered nested device must update its child.')

    def missing = newApp(appSettings, [hardware])
    missing.script.installed()
    def missingContext = missing.calls.get.findAll { it.callback == 'devicesCallback' }.last().context
    missing.script.devicesCallback(new FakeResponse(status: 200, data: [code: 'SUCCESS', data: []]), missingContext)
    check(hardware.events.contains('offline'), 'Previously discovered hardware absent from a successful poll must go offline.')
}

[
    'missing door position => unknown': testDriverUnknownPosition,
    'native lock capability mapping': testNativeLockCapability,
    'unverified hardware status': testUnverifiedHardwareDevice,
    'doorbell event payload sanitization and correlation': testSanitizedDoorbellEventFlow,
    'signed webhook authentication, replay, and DPS dispatch': testSignedWebhookValidationAndDpsDispatch,
    'DPS event wins against older poll': testDpsEventWinsAgainstOlderPoll,
    'external unlock publishes observed relay relock': testExternalUnlockRelayReconciliation,
    'request-to-enter webhook child lifecycle and dedupe': testRequestToEnterWebhook,
    'lock rules default enabled with validation': testLockRuleDefaultEnabledAndEnvelope,
    'gate support declaration and direction gating': testGateModelAndDirectionGates,
    'reader settings capability validation': testReaderMethodCapabilityAndValidation,
    'capability read failures and arbitrary model support': testCapabilityProbeFailureAndArbitraryTypes,
    'doorbell support follows observed evidence': testObservedDoorbellEvidence,
    'doorbell support declaration and command semantics': testDoorbellModelGateAndNoOptimism,
    'emergency control defaults enabled and remains non-optimistic': testEmergencyControlDefaultEnabledAndNonOptimistic,
    'unlock defaults enabled with freshness and non-optimism': testUnlockDefaultEnabled,
    'API and parse failures stale devices': testApiAndParseFailures,
    'nested device response and missing-device offline': testNestedDevicesAndMissingOffline,
    'stale callback ignored': testStaleCallbackIgnored,
    'late API response deadline': testStaleResponseDeadline
].each { title, test ->
    test.call()
    println "PASS: $title"
}
