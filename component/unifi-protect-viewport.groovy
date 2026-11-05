/**
 * UniFi Protect Viewport
 *
 * Child driver for a UniFi Protect viewer, such as a UniFi ViewPort.
 * Created and updated by the UniFi Protect Controller driver.
 *
 * @see https://github.com/9r5n76yjmzs-design/hubitat-unifi-protect
 */
import groovy.transform.Field

@Field static final String DRIVER_NAME = 'UniFi Protect Viewport'
@Field static final String DRIVER_VERSION = '0.1.0'

metadata {
    definition(name:DRIVER_NAME, namespace:'dailous', author:'Michael Dailous', importUrl:'https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-viewport.groovy') {
        capability 'Actuator'
        capability 'Refresh'

        attribute 'liveview', 'string'
        attribute 'liveviewId', 'string'
        attribute 'liveviews', 'string'
        attribute 'defaultLiveview', 'string'
        attribute 'connection', 'string'
        attribute 'apiStatus', 'enum', ['unconfigured', 'ok', 'unauthorized', 'unreachable', 'error']
    }

    command 'setLiveview', [[name:'Liveview*', type:'STRING', description:'Liveview name (case-insensitive) or ID. See the liveviews attribute for names.']]

    preferences {
        input name:'logEnable', type:'bool', title:'Enable debug logging', defaultValue:false
        input name:'txtEnable', type:'bool', title:'Enable description text logging', defaultValue:true
    }
}

void installed() {
    log.info "${device.displayName}: Installed ${DRIVER_NAME} ${DRIVER_VERSION}"
}

void updated() {
    log.info "${device.displayName}: Preferences saved"
}

void refresh() {
    parent?.componentRefresh(device)
}

void setLiveview(String liveview) {
    if (!liveview?.trim()) {
        log.warn "${device.displayName}: setLiveview needs a liveview name or ID"
        return
    }
    parent?.componentSetLiveview(device, liveview.trim())
}

// Called by the controller with the latest viewer values.
void parseViewport(Map values) {
    if (logEnable) log.debug "${device.displayName}: parseViewport ${values}"
    values.each { String name, value ->
        if (value == null) return
        String text = "${device.displayName} ${name} is ${value}"
        boolean changed = "${device.currentValue(name)}" != "${value}"
        sendEvent name:name, value:value, descriptionText:text
        if (changed && txtEnable && name in ['liveview', 'connection']) log.info text
    }
}

// Called by the controller when its API status changes (for example, the API key was revoked).
// While Protect can't be reached, the viewer's connection state is unknown, so it is not left
// showing its last value. The controller's next successful viewer read restores the real state.
void parseApiStatus(String value) {
    boolean changed = "${device.currentValue('apiStatus')}" != value
    String text = "${device.displayName} apiStatus is ${value}"
    sendEvent name:'apiStatus', value:value, descriptionText:text
    if (value != 'ok') parseViewport([connection:'unknown'])
    if (!changed) return
    if (value != 'ok') log.warn "${text}; liveview values may be out of date"
    else if (txtEnable) log.info text
}
