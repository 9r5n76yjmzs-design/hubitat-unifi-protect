/**
 * UniFi Protect Glass Break
 *
 * Child driver for a UniFi Protect camera that can recognize the sound of breaking glass. A glass break
 * heard by the camera is reported with the standard ShockSensor capability (shock detected, then clear),
 * which is how Hubitat models glass-break sensors, so Hubitat Safety Monitor can use it for intrusion
 * alerts and Rule Machine can react to it. The device also shows which sound detections the camera
 * supports and which are turned on in Protect, and the last sound the camera recognized.
 *
 * Camera audio detection is a helpful extra signal, not a certified alarm sensor. This driver only
 * reports. Created and updated by the UniFi Protect Controller driver; detections arrive through the
 * UniFi Protect Event Stream device.
 *
 * @see https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect
 */
import groovy.transform.Field

@Field static final String DRIVER_NAME = 'UniFi Protect Glass Break'
@Field static final String DRIVER_VERSION = '0.1.0'
@Field static final String GLASS_BREAK = 'alrmGlassBreak'
// Protect's names for the sounds a camera can recognize, and the labels shown on the hub.
@Field static final Map<String, String> AUDIO_LABELS = [
    alrmSmoke:'Smoke alarm', alrmCmonx:'CO alarm', alrmSiren:'Siren', alrmBabyCry:'Baby crying', alrmSpeak:'Speech',
    alrmBark:'Dog bark', alrmBurglar:'Burglar alarm', alrmCarHorn:'Car horn', alrmGlassBreak:'Glass break'
]
// How many Protect events are remembered, so an event that is updated several times is reported once.
@Field static final Integer EVENT_MEMORY = 20
// A detection that is already over when it arrives is still shown as detected for this long, so it is seen.
@Field static final Integer MIN_DETECTED_SECONDS = 5
@Field static final String DEFAULT_CLEAR_SECONDS = '60'
@Field static final Map<String, String> CLEAR_OPTIONS = ['30':'30 seconds', '60':'1 minute', '120':'2 minutes', '300':'5 minutes']

metadata {
    definition(name:DRIVER_NAME, namespace:'dailous', author:'Michael Dailous', importUrl:'https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-glass-break.groovy') {
        capability 'Sensor'
        capability 'ShockSensor'
        capability 'Refresh'

        command 'testGlassBreak'

        attribute 'lastGlassBreak', 'string'
        attribute 'glassBreakDetection', 'enum', ['on', 'off', 'unknown']
        attribute 'audioDetectionsSupported', 'string'
        attribute 'audioDetectionsOn', 'string'
        attribute 'lastAudioDetection', 'string'
        attribute 'lastAudioDetectionTime', 'string'
        attribute 'connection', 'string'
        attribute 'apiStatus', 'enum', ['unconfigured', 'ok', 'unauthorized', 'unreachable', 'error']
    }

    preferences {
        input name:'clearSeconds', type:'enum', title:'If Protect never says the glass break ended, clear it after', description:'Protect normally reports when the sound ends, and shock then returns to clear. This is a safety net, for example after a lost connection.', options:CLEAR_OPTIONS, defaultValue:DEFAULT_CLEAR_SECONDS, required:true
        input name:'logEnable', type:'bool', title:'Enable debug logging (turns off after 30 minutes)', defaultValue:false
        input name:'txtEnable', type:'bool', title:'Enable description text logging', defaultValue:true
    }
}

void installed() {
    log.info "${device.displayName}: Installed ${DRIVER_NAME} ${DRIVER_VERSION}"
    sendEvent name:'shock', value:'clear', descriptionText:"${device.displayName} shock is clear"
}

void updated() {
    log.info "${device.displayName}: Preferences saved"
    unschedule('logsOff')
    if (logEnable) runIn 1800, 'logsOff'
}

// Debug logging switches itself off after 30 minutes, so it isn't left on by accident.
void logsOff() {
    device.updateSetting 'logEnable', [value:'false', type:'bool']
    log.info "${device.displayName}: Debug logging disabled"
}

void refresh() {
    parent?.componentRefresh(device)
}

// Simulates a glass break from Hubitat, for testing automations. It is a real shock event:
// if Hubitat Safety Monitor uses this device and is armed, it will raise an intrusion alert.
void testGlassBreak() {
    startGlassBreak(null, null, false)
    runIn MIN_DETECTED_SECONDS, 'clearShock'
}

// Called by the controller with each Protect sound detection on this camera:
// [id: event ID or null, types: Protect sound names, start: time in ms or null, ended: true when Protect says it is over].
// Protect sends an event when it starts and again as it changes or ends, so each event is reported once.
void parseAudio(Map detection) {
    String id = detection.id ? "${detection.id}" : null
    List<String> types = ((detection.types ?: []) as List).collect { it as String }.findAll { AUDIO_LABELS.containsKey(it) }
    boolean ended = detection.ended == true
    if (logEnable) log.debug "${device.displayName}: sound detection ${types} ${ended ? 'ended' : 'active'}, event ${id ?: 'without an ID'}"
    reportAudio(id, types, detection.start)
    if (types.contains(GLASS_BREAK) && remember('glassBreakEventIds', id)) {
        startGlassBreak(id, detection.start, true)
        if (ended) runIn MIN_DETECTED_SECONDS, 'clearShock'
    } else if (ended && id && id == state.glassBreakEventId) {
        clearShock()
    }
}

// Returns shock to clear. Runs when Protect says the glass break ended, or as the safety net.
void clearShock() {
    unschedule('clearShock')
    state.remove('glassBreakEventId')
    if (device.currentValue('shock') == 'clear') return
    String text = "${device.displayName} shock is clear"
    sendEvent name:'shock', value:'clear', descriptionText:text
    if (txtEnable) log.info text
}

// Called by the controller with the latest camera values.
void parseCamera(Map values) {
    if (logEnable) log.debug "${device.displayName}: parseCamera ${values}"
    values.each { String name, value ->
        if (value == null) return
        // Lists of Protect sound names are shown as labels, for example 'Glass break, Smoke alarm'.
        if (value instanceof List) value = soundLabels(value as List)
        String text = "${device.displayName} ${name} is ${value}"
        boolean changed = "${device.currentValue(name)}" != "${value}"
        sendEvent name:name, value:value, descriptionText:text
        if (!changed || !txtEnable) return
        if (name == 'connection') log.info text
    }
    // Warn once when glass break detection is off in Protect, and again only after it was back on.
    if (values.glassBreakDetection == 'off' && !state.warnedOff) {
        state.warnedOff = true
        log.warn "${device.displayName}: glass break detection is turned off for this camera in Protect, so no glass breaks will be reported"
    } else if (values.glassBreakDetection == 'on') {
        state.remove('warnedOff')
    }
}

// Called by the controller when its API status changes. While Protect can't be reached, the connection
// state is unknown and no glass breaks can arrive. The last detection values are kept: they record when
// something happened, not a current state.
void parseApiStatus(String value) {
    boolean changed = "${device.currentValue('apiStatus')}" != value
    String text = "${device.displayName} apiStatus is ${value}"
    sendEvent name:'apiStatus', value:value, descriptionText:text
    if (value != 'ok') clearCamera()
    if (!changed) return
    if (value != 'ok') log.warn "${text}; glass breaks can't be received until Protect can be reached"
    else if (txtEnable) log.info text
}

// Called when the camera's state is not known: Protect can't be reached or no longer lists the camera.
void clearCamera() {
    parseCamera([connection:'unknown'])
}

// physical: heard by the camera; digital: a test from Hubitat. Every detection is a new event (isStateChange),
// so a second glass break raises a new alert even if the first has not cleared yet.
private void startGlassBreak(String id, Object start, boolean physical) {
    state.glassBreakEventId = id
    String time = formatTime(start)
    sendEvent name:'lastGlassBreak', value:time, descriptionText:"${device.displayName} lastGlassBreak is ${time}"
    String text = "${device.displayName} shock is detected (${physical ? 'glass break heard by the camera' : 'test from Hubitat'})"
    sendEvent name:'shock', value:'detected', isStateChange:true, type:(physical ? 'physical' : 'digital'), descriptionText:text
    if (txtEnable) log.info text
    runIn clearSecondsValue(), 'clearShock'
}

// Shows the sounds in an event the first time each one is seen in that event.
private void reportAudio(String id, List<String> types, Object start) {
    List<String> fresh = types.findAll { remember('audioEventKeys', id ? "${id}:${it}" : null) }
    if (!fresh) return
    String labels = fresh.collect { AUDIO_LABELS[it] }.join(', ')
    String time = formatTime(start)
    sendEvent name:'lastAudioDetection', value:labels, isStateChange:true, descriptionText:"${device.displayName} heard ${labels}"
    sendEvent name:'lastAudioDetectionTime', value:time, descriptionText:"${device.displayName} lastAudioDetectionTime is ${time}"
    if (txtEnable && !fresh.contains(GLASS_BREAK)) log.info "${device.displayName} heard ${labels}"
}

// Adds a key to a short list kept in state; false if it was already there. A detection without an ID
// can't be matched to its updates, so it always counts as new.
private boolean remember(String list, String key) {
    if (!key) return true
    List<String> seen = (state[list] ?: []) as List<String>
    if (seen.contains(key)) return false
    seen << key
    state[list] = seen.takeRight(EVENT_MEMORY * 2)
    return true
}

private static String soundLabels(List names) {
    List<String> labels = names.collect { AUDIO_LABELS[it as String] }.findAll { it }
    return labels ? labels.join(', ') : 'none'
}

private Integer clearSecondsValue() {
    String chosen = "${clearSeconds ?: DEFAULT_CLEAR_SECONDS}"
    return CLEAR_OPTIONS.containsKey(chosen) ? (chosen as Integer) : (DEFAULT_CLEAR_SECONDS as Integer)
}

// Protect gives event times in milliseconds; without one, the hub's current time is used.
private String formatTime(Object millis) {
    Long time = millis instanceof Number ? (millis as Number).longValue() : now()
    return new Date(time).format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone)
}
