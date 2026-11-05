/**
 * UniFi Protect Doorbell
 *
 * Child driver for a UniFi Protect doorbell camera. A ring is reported as button 1 pushed
 * (the standard PushableButton capability), so Rule Machine and other apps can react to it.
 * Created and updated by the UniFi Protect Controller driver; rings arrive through the
 * UniFi Protect Event Stream device.
 *
 * @see https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect
 */
import groovy.transform.Field

@Field static final String DRIVER_NAME = 'UniFi Protect Doorbell'
@Field static final String DRIVER_VERSION = '0.1.0'

metadata {
    definition(name:DRIVER_NAME, namespace:'dailous', author:'Michael Dailous', importUrl:'https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-doorbell.groovy') {
        capability 'Actuator'
        capability 'PushableButton'
        capability 'Refresh'

        attribute 'lastRing', 'string'
        attribute 'connection', 'string'
        attribute 'apiStatus', 'enum', ['unconfigured', 'ok', 'unauthorized', 'unreachable', 'error']
    }

    preferences {
        input name:'logEnable', type:'bool', title:'Enable debug logging', defaultValue:false
        input name:'txtEnable', type:'bool', title:'Enable description text logging', defaultValue:true
    }
}

void installed() {
    log.info "${device.displayName}: Installed ${DRIVER_NAME} ${DRIVER_VERSION}"
    sendEvent name:'numberOfButtons', value:1, descriptionText:"${device.displayName} numberOfButtons is 1"
}

void updated() {
    log.info "${device.displayName}: Preferences saved"
    sendEvent name:'numberOfButtons', value:1, descriptionText:"${device.displayName} numberOfButtons is 1"
}

void refresh() {
    parent?.componentRefresh(device)
}

// PushableButton command: simulates a ring from Hubitat, so automations can be tested without the doorbell.
void push(button = 1) {
    if ("${button}" != '1') {
        log.warn "${device.displayName}: only button 1 exists"
        return
    }
    sendPushed('pressed in Hubitat (test)')
}

// Called by the controller when Protect reports a ring on this doorbell.
void parseRing(Map ring) {
    if (logEnable) log.debug "${device.displayName}: parseRing ${ring}"
    if (ring.id && ring.id == state.lastRingId) return
    state.lastRingId = ring.id
    Long start = ring.start instanceof Number ? (ring.start as Number).longValue() : now()
    String time = new Date(start).format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone)
    sendEvent name:'lastRing', value:time, descriptionText:"${device.displayName} lastRing is ${time}"
    sendPushed('rang')
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
}

// Called by the controller when its API status changes. While Protect can't be reached, the
// connection state is unknown. lastRing is kept: it records when a ring happened, not a current state.
void parseApiStatus(String value) {
    boolean changed = "${device.currentValue('apiStatus')}" != value
    String text = "${device.displayName} apiStatus is ${value}"
    sendEvent name:'apiStatus', value:value, descriptionText:text
    if (value != 'ok') clearCamera()
    if (!changed) return
    if (value != 'ok') log.warn "${text}; rings can't be received until Protect can be reached"
    else if (txtEnable) log.info text
}

// Called when the camera's state is not known: Protect can't be reached or no longer lists the camera.
void clearCamera() {
    parseCamera([connection:'unknown'])
}

private void sendPushed(String how) {
    String text = "${device.displayName} button 1 was pushed (${how})"
    sendEvent name:'pushed', value:1, isStateChange:true, type:(how == 'rang' ? 'physical' : 'digital'), descriptionText:text
    if (txtEnable) log.info text
}
