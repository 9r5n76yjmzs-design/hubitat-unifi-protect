/**
 * UniFi Protect Doorbell
 *
 * Child driver for a UniFi Protect doorbell camera. A ring is reported as button 1 pushed and a
 * package detected by the camera as button 2 pushed (the standard PushableButton capability), so
 * Rule Machine and other apps can react to them. What happens next is up to those automations:
 * this driver only reports. Created and updated by the UniFi Protect Controller driver; rings and
 * package detections arrive through the UniFi Protect Event Stream device.
 *
 * @see https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect
 */
import groovy.transform.Field

@Field static final String DRIVER_NAME = 'UniFi Protect Doorbell'
@Field static final String DRIVER_VERSION = '0.2.0'
@Field static final Integer RING_BUTTON = 1
@Field static final Integer PACKAGE_BUTTON = 2
@Field static final Integer NUMBER_OF_BUTTONS = 2
// How many Protect package events are remembered, so an event that is updated several times is reported once.
@Field static final Integer PACKAGE_EVENT_MEMORY = 20
@Field static final String DEFAULT_PACKAGE_QUIET_MINUTES = '0'
@Field static final Map<String, String> PACKAGE_QUIET_OPTIONS = ['0':'Report every detection', '1':'1 minute', '5':'5 minutes', '15':'15 minutes', '60':'1 hour']

metadata {
    definition(name:DRIVER_NAME, namespace:'dailous', author:'Michael Dailous', importUrl:'https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-doorbell.groovy') {
        capability 'Actuator'
        capability 'PushableButton'
        capability 'Refresh'

        attribute 'lastRing', 'string'
        attribute 'lastPackage', 'string'
        attribute 'connection', 'string'
        attribute 'apiStatus', 'enum', ['unconfigured', 'ok', 'unauthorized', 'unreachable', 'error']
    }

    preferences {
        input name:'packageQuietMinutes', type:'enum', title:'After a package is reported, ignore further package detections for', description:'Off by default. Only needed if Protect reports the same package more than once. Rings are never held back.', options:PACKAGE_QUIET_OPTIONS, defaultValue:DEFAULT_PACKAGE_QUIET_MINUTES, required:true
        input name:'logEnable', type:'bool', title:'Enable debug logging (turns off after 30 minutes)', defaultValue:false
        input name:'txtEnable', type:'bool', title:'Enable description text logging', defaultValue:true
    }
}

void installed() {
    log.info "${device.displayName}: Installed ${DRIVER_NAME} ${DRIVER_VERSION}"
    sendNumberOfButtons()
}

void updated() {
    log.info "${device.displayName}: Preferences saved"
    unschedule('logsOff')
    if (logEnable) runIn 1800, 'logsOff'
    sendNumberOfButtons()
}

// Debug logging switches itself off after 30 minutes, so it isn't left on by accident.
void logsOff() {
    device.updateSetting 'logEnable', [value:'false', type:'bool']
    log.info "${device.displayName}: Debug logging disabled"
}

void refresh() {
    parent?.componentRefresh(device)
}

// PushableButton command: simulates a ring (button 1) or a package detection (button 2) from Hubitat,
// so automations can be tested without going to the door. Test presses are never held back.
void push(button = RING_BUTTON) {
    String pressed = "${button}"
    if (pressed == "${RING_BUTTON}") {
        sendPushed(RING_BUTTON, 'pressed in Hubitat (test)', false)
    } else if (pressed == "${PACKAGE_BUTTON}") {
        sendPushed(PACKAGE_BUTTON, 'package detection simulated in Hubitat (test)', false)
    } else {
        log.warn "${device.displayName}: only buttons ${RING_BUTTON} (ring) and ${PACKAGE_BUTTON} (package) exist"
    }
}

// Called by the controller when Protect reports a ring on this doorbell.
void parseRing(Map ring) {
    if (logEnable) log.debug "${device.displayName}: parseRing ${ring}"
    if (ring.id && ring.id == state.lastRingId) return
    state.lastRingId = ring.id
    String time = formatTime(ring.start)
    sendEvent name:'lastRing', value:time, descriptionText:"${device.displayName} lastRing is ${time}"
    sendPushed(RING_BUTTON, 'rang', true)
}

// Called by the controller when a Protect smart detection on this doorbell includes a package.
// Protect updates the same event several times, so each event ID is reported once. Should Protect also
// raise new events for a package that is still at the door, further detections can be ignored for
// a time chosen in Preferences (off by default).
void parsePackage(Map detection) {
    String id = detection.id ? "${detection.id}" : null
    List<String> seen = (state.packageEventIds ?: []) as List<String>
    if (id && seen.contains(id)) return
    if (id) {
        seen << id
        state.packageEventIds = seen.takeRight(PACKAGE_EVENT_MEMORY)
    }
    Long quietMs = packageQuietMinutesValue() * 60000L
    Long sinceLast = now() - ((state.lastPackageAt ?: 0L) as Long)
    if (quietMs > 0 && sinceLast >= 0 && sinceLast < quietMs) {
        if (logEnable) log.debug "${device.displayName}: package detected again ${sinceLast.intdiv(1000L)} seconds after the last report; ignored"
        return
    }
    state.lastPackageAt = now()
    String time = formatTime(detection.start)
    sendEvent name:'lastPackage', value:time, descriptionText:"${device.displayName} lastPackage is ${time}"
    sendPushed(PACKAGE_BUTTON, 'package detected', true)
}

// Called by the controller with the latest camera values.
void parseCamera(Map values) {
    if (logEnable) log.debug "${device.displayName}: parseCamera ${values}"
    values.each { String name, value ->
        if (value == null) return
        String text = "${device.displayName} ${name} is ${value}"
        boolean changed = "${device.currentValue(name)}" != "${value}"
        sendEvent name:name, value:value, descriptionText:text
        if (changed && txtEnable && name == 'connection') log.info text
    }
    // Devices created by an earlier version report one button; correct that on the controller's next refresh.
    if ("${device.currentValue('numberOfButtons')}" != "${NUMBER_OF_BUTTONS}") sendNumberOfButtons()
}

// Called by the controller when its API status changes. While Protect can't be reached, the
// connection state is unknown. lastRing and lastPackage are kept: they record when something happened,
// not a current state.
void parseApiStatus(String value) {
    boolean changed = "${device.currentValue('apiStatus')}" != value
    String text = "${device.displayName} apiStatus is ${value}"
    sendEvent name:'apiStatus', value:value, descriptionText:text
    if (value != 'ok') clearCamera()
    if (!changed) return
    if (value != 'ok') log.warn "${text}; rings and package detections can't be received until Protect can be reached"
    else if (txtEnable) log.info text
}

// Called when the camera's state is not known: Protect can't be reached or no longer lists the camera.
void clearCamera() {
    parseCamera([connection:'unknown'])
}

private void sendNumberOfButtons() {
    sendEvent name:'numberOfButtons', value:NUMBER_OF_BUTTONS, descriptionText:"${device.displayName} numberOfButtons is ${NUMBER_OF_BUTTONS}"
}

// physical: reported by the doorbell itself; digital: a test press from Hubitat.
private void sendPushed(Integer button, String how, boolean physical) {
    String text = "${device.displayName} button ${button} was pushed (${how})"
    sendEvent name:'pushed', value:button, isStateChange:true, type:(physical ? 'physical' : 'digital'), descriptionText:text
    if (txtEnable) log.info text
}

private Integer packageQuietMinutesValue() {
    String chosen = "${packageQuietMinutes ?: DEFAULT_PACKAGE_QUIET_MINUTES}"
    return PACKAGE_QUIET_OPTIONS.containsKey(chosen) ? (chosen as Integer) : (DEFAULT_PACKAGE_QUIET_MINUTES as Integer)
}

// Protect gives event times in milliseconds; without one, the hub's current time is used.
private String formatTime(Object millis) {
    Long time = millis instanceof Number ? (millis as Number).longValue() : now()
    return new Date(time).format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone)
}
