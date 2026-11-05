/**
 * UniFi Protect Controller
 *
 * Parent driver for UniFi Protect using Ubiquiti's official local Integration API
 * (https://developer.ui.com/protect/). Holds the console address and API key,
 * discovers devices and creates one child device per supported device type.
 *
 * @see https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect
 */
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String DRIVER_NAME = 'UniFi Protect Controller'
@Field static final String DRIVER_VERSION = '0.2.0'
@Field static final String NAMESPACE = 'dailous'
@Field static final String VIEWPORT_DRIVER = 'UniFi Protect Viewport'
@Field static final String API_BASE = '/proxy/protect/integration'
@Field static final Integer HTTP_TIMEOUT_SECONDS = 10
@Field static final String DEVICES_SOCKET_PATH = '/v1/subscribe/devices'
@Field static final Integer RECONNECT_MIN_SECONDS = 5
@Field static final Integer RECONNECT_MAX_SECONDS = 300
@Field static final Map<String, String> POLL_OPTIONS = ['0':'Off', '1':'Every minute', '5':'Every 5 minutes', '15':'Every 15 minutes', '30':'Every 30 minutes']

metadata {
    definition(name:DRIVER_NAME, namespace:'dailous', author:'Michael Dailous', importUrl:'https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/unifi-protect-controller.groovy') {
        capability 'Initialize'
        capability 'Refresh'

        attribute 'apiStatus', 'enum', ['unconfigured', 'ok', 'unauthorized', 'unreachable', 'error']
        attribute 'applicationVersion', 'string'
        attribute 'realtime', 'enum', ['connected', 'connecting', 'disconnected', 'off']
    }

    preferences {
        input name:'consoleIp', type:'text', title:'Console IP address', description:'Local IP address of the UniFi console running Protect', required:true
        input name:'apiKey', type:'password', title:'API key', description:'Created in UniFi Protect &rarr; Integrations', required:true
        input name:'realtimeEnable', type:'bool', title:'Real-time updates', description:'Keep a live connection to Protect so device changes show up immediately', defaultValue:true
        input name:'pollMinutes', type:'enum', title:'Refresh interval', description:'Full refresh as a safety net, also when real-time updates are on', options:POLL_OPTIONS, defaultValue:'5', required:true
        input name:'logEnable', type:'bool', title:'Enable debug logging (turns off after 30 minutes)', defaultValue:true
    }
}

// ===================================================================================================================
// Lifecycle
// ===================================================================================================================

void installed() {
    log_info "Installed ${DRIVER_NAME} ${DRIVER_VERSION}"
    setApiStatus('unconfigured')
}

void updated() {
    log_info "Preferences saved"
    unschedule()
    if (logEnable) runIn 1800, 'logsOff'
    initialize()
}

void uninstalled() {
    closeSocket()
}

void initialize() {
    unschedule('refresh')
    closeSocket()
    removeRetiredStates()
    if (!isConfigured()) {
        log_warn 'Console IP address and API key are required'
        setApiStatus('unconfigured')
        return
    }
    if (realtimeEnable != false) {
        state.reconnectSeconds = RECONNECT_MIN_SECONDS
        // Give the old connection time to report that it closed before opening the new one.
        runIn 2, 'connectSocket'
    }
    switch ("${pollMinutes ?: '5'}") {
        case '1': runEvery1Minute 'refresh'; break
        case '5': runEvery5Minutes 'refresh'; break
        case '15': runEvery15Minutes 'refresh'; break
        case '30': runEvery30Minutes 'refresh'; break
        default: break
    }
    refresh()
}

void refresh() {
    if (!isConfigured()) {
        setApiStatus('unconfigured')
        return
    }
    log_debug 'Refreshing application info, liveviews and viewers ...'
    apiGet '/v1/meta/info', 'handleMetaInfo'
}

void logsOff() {
    device.updateSetting 'logEnable', [value:'false', type:'bool']
    log_info 'Debug logging disabled'
}

// ===================================================================================================================
// Child device API (called by child drivers)
// ===================================================================================================================

void componentRefresh(child) {
    String viewerId = child.getDataValue('viewerId')
    if (!viewerId) {
        log_warn "${child.displayName} has no viewerId; running a full refresh"
        refresh()
        return
    }
    apiGet "/v1/viewers/${viewerId}", 'handleViewer'
}

void componentSetLiveview(child, String liveview) {
    String viewerId = child.getDataValue('viewerId')
    if (!viewerId) {
        log_error "${child.displayName} has no viewerId; cannot change liveview"
        return
    }
    Map lv = findLiveview(liveview)
    if (!lv) {
        log_error "Liveview '${liveview}' not found. Available: ${liveviewNames()}"
        return
    }
    log_info "Switching ${child.displayName} to liveview '${lv.name}'"
    apiPatch "/v1/viewers/${viewerId}", [liveview:lv.id], 'handleViewer'
}

// ===================================================================================================================
// Real-time updates (WebSocket GET /v1/subscribe/devices)
// ===================================================================================================================

void connectSocket() {
    if (!isConfigured() || realtimeEnable == false) return
    state.socketWanted = true
    sendEvent name:'realtime', value:'connecting'
    log_debug "Connecting to ${DEVICES_SOCKET_PATH} ..."
    try {
        interfaces.webSocket.connect("wss://${consoleIp.trim()}${API_BASE}${DEVICES_SOCKET_PATH}",
            headers: ['X-API-KEY': apiKey.trim()], ignoreSSLIssues: true, pingInterval: 30)
    } catch (e) {
        log_error "Could not open the real-time connection: ${e.message}"
        socketLost()
    }
}

void reconnectSocket() {
    if (!state.socketWanted || device.currentValue('realtime', true) == 'connected') return
    connectSocket()
}

// Called by the hub with connection status messages, e.g. "status: open", "status: closing", "failure: ...".
void webSocketStatus(String message) {
    log_debug "Real-time connection status: ${message}"
    if (!state.socketWanted) return
    if (message?.startsWith('status: open')) {
        state.reconnectSeconds = RECONNECT_MIN_SECONDS
        sendEvent name:'realtime', value:'connected'
        log_info 'Real-time updates connected'
        // Catch up on anything that changed while the connection was down.
        refresh()
    } else if (message?.startsWith('status: closing') || message?.startsWith('failure:')) {
        if (message.startsWith('failure:')) log_warn "Real-time connection failed: ${message.substring(8).trim()}"
        socketLost()
    }
}

// Called by the hub with each WebSocket message.
void parse(String message) {
    Map msg
    try {
        msg = parseJson(message) as Map
    } catch (e) {
        log_debug "Ignoring a real-time message that is not JSON"
        return
    }
    Map item = msg?.item as Map
    if (item?.modelKey != 'viewer') return
    log_debug "Real-time viewer ${msg.type}: ${item}"
    List<String> ids = (item.id instanceof List ? item.id : [item.id]).collect { it as String }
    switch (msg.type) {
        case 'add':
            log_info 'Protect reported a new viewer; refreshing'
            refresh()
            break
        case 'update':
            ids.each { String id ->
                def child = getChildDevice(viewportDni(id))
                if (child) child.parseViewport(viewportValues(item, false))
            }
            if (item.containsKey('liveview') && item.liveview && !findLiveview(item.liveview as String)) {
                log_debug 'Viewer shows a liveview Hubitat does not know yet; refreshing liveviews'
                refresh()
            }
            break
        case 'remove':
            ids.each { String id ->
                def child = getChildDevice(viewportDni(id))
                if (!child) return
                log_warn "${child.displayName} was removed from Protect; leaving the device in place"
                child.clearViewport()
            }
            break
    }
}

// Removes values of attributes that earlier versions of this driver declared, so they no longer
// show under Current States. Liveview details now live only on the child devices that use them.
private void removeRetiredStates() {
    ['liveviews', 'defaultLiveview'].each { String name ->
        try { device.deleteCurrentState(name) } catch (ignored) { }
    }
}

private void closeSocket() {
    state.socketWanted = false
    unschedule('connectSocket')
    unschedule('reconnectSocket')
    try { interfaces.webSocket.close() } catch (ignored) { }
    sendEvent name:'realtime', value:'off'
}

private void socketLost() {
    sendEvent name:'realtime', value:'disconnected'
    // Check right away whether Protect itself is still reachable, so children are cleared if it isn't.
    refresh()
    Integer delay = (state.reconnectSeconds ?: RECONNECT_MIN_SECONDS) as Integer
    state.reconnectSeconds = Math.min(delay * 2, RECONNECT_MAX_SECONDS)
    log_info "Reconnecting real-time updates in ${delay} seconds"
    runIn delay, 'reconnectSocket'
}

// ===================================================================================================================
// Response handlers
// ===================================================================================================================

void handleMetaInfo(response, Map data) {
    Object json = apiResult(response, 'GET /v1/meta/info')
    if (json == null) return
    if (json.applicationVersion) sendEvent name:'applicationVersion', value:json.applicationVersion
    apiGet '/v1/liveviews', 'handleLiveviews'
}

void handleLiveviews(response, Map data) {
    Object json = apiResult(response, 'GET /v1/liveviews')
    if (!(json instanceof List)) return
    List<Map> liveviews = json.collect { [id:it.id, name:it.name, isDefault:it.isDefault == true] }
    state.liveviews = liveviews
    log_debug "Found ${liveviews.size()} liveview(s): ${liveviewNames()}"
    apiGet '/v1/viewers', 'handleViewers'
}

void handleViewers(response, Map data) {
    Object json = apiResult(response, 'GET /v1/viewers')
    if (!(json instanceof List)) return
    log_debug "Found ${json.size()} viewer(s)"
    Set<String> seen = [] as Set
    json.each { Map viewer ->
        seen << viewportDni(viewer.id as String)
        updateViewportChild(viewer, true)
    }
    getChildDevices().findAll { it.deviceNetworkId.startsWith('unifi-protect-viewport-') && !seen.contains(it.deviceNetworkId) }.each {
        log_warn "${it.displayName} is no longer reported by Protect; leaving the device in place"
        it.clearViewport()
    }
}

void handleViewer(response, Map data) {
    Object json = apiResult(response, 'viewer request')
    if (!(json instanceof Map)) return
    updateViewportChild(json as Map, false)
}

// ===================================================================================================================
// Helpers
// ===================================================================================================================

private void updateViewportChild(Map viewer, boolean create) {
    String dni = viewportDni(viewer.id as String)
    def child = getChildDevice(dni)
    if (!child && create) {
        try {
            child = addChildDevice(NAMESPACE, VIEWPORT_DRIVER, dni, [name:viewer.name ?: 'UniFi Protect Viewport', label:viewer.name, isComponent:false])
            log_info "Created Viewport device '${viewer.name}'"
        } catch (e) {
            log_error "Could not create Viewport device '${viewer.name}'. Is the '${VIEWPORT_DRIVER}' driver installed? ${e.message}"
            return
        }
    }
    if (!child) {
        log_warn "No Viewport device for viewer ${viewer.id}; run Refresh on the controller"
        return
    }
    child.updateDataValue 'viewerId', viewer.id as String
    if (viewer.type) child.updateDataValue 'type', viewer.type as String

    child.parseViewport(viewportValues(viewer, true))
}

// Maps a Protect viewer to Viewport attribute values. A full viewer (from a REST read) gives every value;
// a partial one (from a real-time update) gives only the values present in the message.
private Map viewportValues(Map viewer, boolean full) {
    Map values = [:]
    if (full || viewer.containsKey('liveview')) {
        Map current = findLiveview(viewer.liveview as String)
        values.liveview = current?.name ?: (viewer.liveview ? "unknown (${viewer.liveview})" : 'none')
        values.liveviewId = viewer.liveview ?: 'none'
    }
    if (full || viewer.containsKey('state')) values.connection = viewer.state ?: 'unknown'
    if (full) {
        values.liveviews = JsonOutput.toJson(liveviewNames())
        values.defaultLiveview = liveviewsState().find { it.isDefault }?.name ?: 'none'
        values.apiStatus = 'ok'
    }
    return values
}

private Map findLiveview(String nameOrId) {
    if (!nameOrId) return null
    List<Map> liveviews = liveviewsState()
    return liveviews.find { it.id == nameOrId } ?:
        liveviews.find { it.name == nameOrId } ?:
        liveviews.find { "${it.name}".equalsIgnoreCase(nameOrId.trim()) }
}

private List<Map> liveviewsState() {
    return (state.liveviews ?: []) as List<Map>
}

private List<String> liveviewNames() {
    return liveviewsState().collect { it.name as String }
}

private static String viewportDni(String id) {
    return "unifi-protect-viewport-${id}"
}

private boolean isConfigured() {
    return consoleIp?.trim() && apiKey?.trim()
}

private Map requestParams(String path) {
    return [
        uri: "https://${consoleIp.trim()}${API_BASE}${path}",
        headers: ['X-API-KEY': apiKey.trim(), Accept:'application/json'],
        contentType: 'application/json',
        timeout: HTTP_TIMEOUT_SECONDS,
        ignoreSSLIssues: true
    ]
}

private void apiGet(String path, String callback) {
    log_debug "GET ${path}"
    asynchttpGet callback, requestParams(path), [path:path]
}

private void apiPatch(String path, Map body, String callback) {
    log_debug "PATCH ${path} ${JsonOutput.toJson(body)}"
    Map params = requestParams(path) + [requestContentType:'application/json', body:body]
    asynchttpPatch callback, params, [path:path]
}

// Returns the parsed JSON body, or null after logging the failure and updating apiStatus.
// Never logs request headers, so the API key stays out of the logs.
private Object apiResult(response, String what) {
    Integer status = null
    try { status = response.getStatus() } catch (ignored) { }
    if (response.hasError()) {
        String message = null
        String body = null
        try { message = response.getErrorMessage() } catch (ignored) { }
        try { body = response.getErrorData() } catch (ignored) { }
        if (status == 401 || status == 403) {
            setApiStatus('unauthorized')
            log_error "${what}: API key rejected (HTTP ${status}). Check the key in Preferences."
        } else if (status == null || status == 408) {
            setApiStatus('unreachable')
            log_error "${what}: console unreachable (${message ?: 'no response'})"
        } else {
            setApiStatus('error')
            log_error "${what}: HTTP ${status} ${message ?: ''} ${body ?: ''}"
        }
        return null
    }
    Object json
    try {
        json = response.getJson()
    } catch (e) {
        setApiStatus('error')
        log_error "${what}: response was not valid JSON (HTTP ${status})"
        return null
    }
    setApiStatus('ok')
    return json
}

// Updates apiStatus and, when it changes, pushes it to every child device so children
// show when the controller can no longer reach Protect (for example, a revoked API key).
private void setApiStatus(String value) {
    boolean changed = "${device.currentValue('apiStatus', true)}" != value
    sendEvent name:'apiStatus', value:value
    if (!changed) return
    if (value == 'ok') log_info 'API status is ok'
    getChildDevices().each { child ->
        try {
            child.parseApiStatus(value)
        } catch (e) {
            log_debug "${child.displayName} does not accept apiStatus updates: ${e.message}"
        }
    }
}

private void log_debug(String message) {
    if (logEnable) log.debug "${device.displayName}: ${message}"
}

private void log_info(String message) {
    log.info "${device.displayName}: ${message}"
}

private void log_warn(String message) {
    log.warn "${device.displayName}: ${message}"
}

private void log_error(String message) {
    log.error "${device.displayName}: ${message}"
}
