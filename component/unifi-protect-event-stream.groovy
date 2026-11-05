/**
 * UniFi Protect Event Stream
 *
 * Child driver that keeps the one live connection to Protect's events feed
 * (GET /v1/subscribe/events) and hands each event to the UniFi Protect Controller,
 * which routes it to the child device it belongs to (for example, a ring to a Doorbell).
 * Created, started and stopped by the controller; it has no settings of its own.
 * The API key stays in the controller and is fetched for each connection attempt.
 *
 * @see https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect
 */
import groovy.transform.Field

@Field static final String DRIVER_NAME = 'UniFi Protect Event Stream'
@Field static final String DRIVER_VERSION = '0.1.0'
@Field static final Integer RECONNECT_MIN_SECONDS = 5
@Field static final Integer RECONNECT_MAX_SECONDS = 300

metadata {
    definition(name:DRIVER_NAME, namespace:'dailous', author:'Michael Dailous', importUrl:'https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-event-stream.groovy') {
        attribute 'stream', 'enum', ['connected', 'connecting', 'disconnected', 'off']
    }

    preferences {
        input name:'logEnable', type:'bool', title:'Enable debug logging', defaultValue:false
    }
}

void installed() {
    log.info "${device.displayName}: Installed ${DRIVER_NAME} ${DRIVER_VERSION}"
    setStream('off')
}

void updated() {
    log.info "${device.displayName}: Preferences saved"
}

void uninstalled() {
    stopStream()
}

// Called by the controller to open (or reopen) the events connection.
void startStream() {
    stopStream()
    state.reconnectSeconds = RECONNECT_MIN_SECONDS
    // Give the old connection time to report that it closed before opening the new one.
    runIn 2, 'connectStream'
}

// Called by the controller to close the events connection and stop reconnecting.
void stopStream() {
    state.streamWanted = false
    unschedule('connectStream')
    unschedule('reconnectStream')
    try { interfaces.webSocket.close() } catch (ignored) { }
    setStream('off')
}

void connectStream() {
    Map target = parent?.componentEventStreamTarget()
    if (!target?.url) {
        log.warn "${device.displayName}: the controller has no console address or API key; not connecting"
        return
    }
    state.streamWanted = true
    setStream('connecting')
    if (logEnable) log.debug "${device.displayName}: connecting to the events feed ..."
    try {
        interfaces.webSocket.connect(target.url as String, headers: target.headers as Map, ignoreSSLIssues: true, pingInterval: 30)
    } catch (e) {
        log.error "${device.displayName}: could not open the events connection: ${e.message}"
        streamLost()
    }
}

void reconnectStream() {
    if (!state.streamWanted || device.currentValue('stream', true) == 'connected') return
    connectStream()
}

// Called by the hub with connection status messages, e.g. "status: open", "status: closing", "failure: ...".
void webSocketStatus(String message) {
    if (logEnable) log.debug "${device.displayName}: events connection status: ${message}"
    if (!state.streamWanted) return
    if (message?.startsWith('status: open')) {
        state.reconnectSeconds = RECONNECT_MIN_SECONDS
        setStream('connected')
        log.info "${device.displayName}: events feed connected"
    } else if (message?.startsWith('status: closing') || message?.startsWith('failure:')) {
        if (message.startsWith('failure:')) log.warn "${device.displayName}: events connection failed: ${message.substring(8).trim()}"
        streamLost()
    }
}

// Called by the hub with each WebSocket message.
void parse(String message) {
    Map msg
    try {
        msg = parseJson(message) as Map
    } catch (e) {
        if (logEnable) log.debug "${device.displayName}: ignoring a message that is not JSON"
        return
    }
    if (msg?.item instanceof Map) parent?.componentEvent(msg)
}

private void streamLost() {
    setStream('disconnected')
    Integer delay = (state.reconnectSeconds ?: RECONNECT_MIN_SECONDS) as Integer
    state.reconnectSeconds = Math.min(delay * 2, RECONNECT_MAX_SECONDS)
    log.info "${device.displayName}: reconnecting the events feed in ${delay} seconds"
    runIn delay, 'reconnectStream'
}

// Records the connection state here and on the controller.
private void setStream(String value) {
    sendEvent name:'stream', value:value, descriptionText:"${device.displayName} stream is ${value}"
    parent?.componentEventStreamStatus(value)
}
