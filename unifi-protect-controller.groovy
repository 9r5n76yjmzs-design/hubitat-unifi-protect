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
@Field static final String DRIVER_VERSION = '0.1.0'
@Field static final String NAMESPACE = 'dailous'
@Field static final String VIEWER_DRIVER = 'UniFi Protect Viewer'
@Field static final String API_BASE = '/proxy/protect/integration'
@Field static final Integer HTTP_TIMEOUT_SECONDS = 10
@Field static final Map<String, String> POLL_OPTIONS = ['0':'Off', '1':'Every minute', '5':'Every 5 minutes', '15':'Every 15 minutes', '30':'Every 30 minutes']

metadata {
    definition(name:DRIVER_NAME, namespace:'dailous', author:'Michael Dailous', importUrl:'https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/unifi-protect-controller.groovy') {
        capability 'Initialize'
        capability 'Refresh'

        attribute 'apiStatus', 'enum', ['unconfigured', 'ok', 'unauthorized', 'unreachable', 'error']
        attribute 'applicationVersion', 'string'
        attribute 'liveviews', 'string'
        attribute 'defaultLiveview', 'string'
    }

    preferences {
        input name:'consoleIp', type:'text', title:'Console IP address', description:'Local IP address of the UniFi console running Protect', required:true
        input name:'apiKey', type:'password', title:'API key', description:'Created in UniFi Protect &rarr; Integrations', required:true
        input name:'pollMinutes', type:'enum', title:'Refresh interval', options:POLL_OPTIONS, defaultValue:'5', required:true
        input name:'logEnable', type:'bool', title:'Enable debug logging (turns off after 30 minutes)', defaultValue:true
    }
}

// ===================================================================================================================
// Lifecycle
// ===================================================================================================================

void installed() {
    log_info "Installed ${DRIVER_NAME} ${DRIVER_VERSION}"
    sendEvent name:'apiStatus', value:'unconfigured'
}

void updated() {
    log_info "Preferences saved"
    unschedule()
    if (logEnable) runIn 1800, 'logsOff'
    initialize()
}

void initialize() {
    unschedule('refresh')
    if (!isConfigured()) {
        log_warn 'Console IP address and API key are required'
        sendEvent name:'apiStatus', value:'unconfigured'
        return
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
        sendEvent name:'apiStatus', value:'unconfigured'
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
// Response handlers
// ===================================================================================================================

void handleMetaInfo(response, Map data) {
    Object json = apiResult(response, 'GET /v1/meta/info')
    if (json == null) return
    sendEvent name:'apiStatus', value:'ok'
    if (json.applicationVersion) sendEvent name:'applicationVersion', value:json.applicationVersion
    apiGet '/v1/liveviews', 'handleLiveviews'
}

void handleLiveviews(response, Map data) {
    Object json = apiResult(response, 'GET /v1/liveviews')
    if (!(json instanceof List)) return
    List<Map> liveviews = json.collect { [id:it.id, name:it.name, isDefault:it.isDefault == true] }
    state.liveviews = liveviews
    sendEvent name:'liveviews', value:JsonOutput.toJson(liveviewNames())
    sendEvent name:'defaultLiveview', value:(liveviews.find { it.isDefault }?.name ?: 'none')
    log_debug "Found ${liveviews.size()} liveview(s): ${liveviewNames()}"
    apiGet '/v1/viewers', 'handleViewers'
}

void handleViewers(response, Map data) {
    Object json = apiResult(response, 'GET /v1/viewers')
    if (!(json instanceof List)) return
    log_debug "Found ${json.size()} viewer(s)"
    Set<String> seen = [] as Set
    json.each { Map viewer ->
        seen << viewerDni(viewer.id as String)
        updateViewerChild(viewer, true)
    }
    getChildDevices().findAll { it.deviceNetworkId.startsWith('unifi-protect-viewer-') && !seen.contains(it.deviceNetworkId) }.each {
        log_warn "${it.displayName} is no longer reported by Protect; leaving the device in place"
    }
}

void handleViewer(response, Map data) {
    Object json = apiResult(response, 'viewer request')
    if (!(json instanceof Map)) return
    updateViewerChild(json as Map, false)
}

// ===================================================================================================================
// Helpers
// ===================================================================================================================

private void updateViewerChild(Map viewer, boolean create) {
    String dni = viewerDni(viewer.id as String)
    def child = getChildDevice(dni)
    if (!child && create) {
        try {
            child = addChildDevice(NAMESPACE, VIEWER_DRIVER, dni, [name:viewer.name ?: 'UniFi Protect Viewer', label:viewer.name, isComponent:false])
            log_info "Created viewer device '${viewer.name}'"
        } catch (e) {
            log_error "Could not create viewer device '${viewer.name}'. Is the '${VIEWER_DRIVER}' driver installed? ${e.message}"
            return
        }
    }
    if (!child) {
        log_warn "No device for viewer ${viewer.id}; run Refresh on the controller"
        return
    }
    child.updateDataValue 'viewerId', viewer.id as String
    if (viewer.type) child.updateDataValue 'type', viewer.type as String

    Map current = findLiveview(viewer.liveview as String)
    child.parseViewer([
        liveview: current?.name ?: (viewer.liveview ? "unknown (${viewer.liveview})" : 'none'),
        liveviewId: viewer.liveview ?: 'none',
        liveviews: JsonOutput.toJson(liveviewNames()),
        defaultLiveview: liveviewsState().find { it.isDefault }?.name ?: 'none',
        connection: viewer.state ?: 'unknown'
    ])
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

private static String viewerDni(String id) {
    return "unifi-protect-viewer-${id}"
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
            sendEvent name:'apiStatus', value:'unauthorized'
            log_error "${what}: API key rejected (HTTP ${status}). Check the key in Preferences."
        } else if (status == null || status == 408) {
            sendEvent name:'apiStatus', value:'unreachable'
            log_error "${what}: console unreachable (${message ?: 'no response'})"
        } else {
            sendEvent name:'apiStatus', value:'error'
            log_error "${what}: HTTP ${status} ${message ?: ''} ${body ?: ''}"
        }
        return null
    }
    try {
        return response.getJson()
    } catch (e) {
        sendEvent name:'apiStatus', value:'error'
        log_error "${what}: response was not valid JSON (HTTP ${status})"
        return null
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
