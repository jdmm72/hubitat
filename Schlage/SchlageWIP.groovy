/**
 *   This is a modification of work originally copyrighted by "SmartThings."	 All modifications to their work
 * 	is released under the following terms:
 *
 * 	The original licensing applies, with the following exceptions:
 * 		1.	These modifications may NOT be used without freely distributing all these modifications freely
 * 			and without limitation, in source form.	 The distribution may be met with a link to source code
 * 			with these modifications.
 * 		2.	These modifications may NOT be used, directly or indirectly, for the purpose of any type of
 * 			monetary gain.	These modifications may not be used in a larger entity which is being sold,
 * 			leased, or anything other than freely given.
 * 		3.	To clarify 1 and 2 above, if you use these modifications, it must be a free project, and
 * 			available to anyone with "no strings attached."	 (You may require a free registration on
 * 			a free website or portal in order to distribute the modifications.)
 * 		4.	The above listed exceptions to the original licensing do not apply to the holder of the
 * 			copyright of the original work.	 The original copyright holder can use the modifications
 * 			to hopefully improve their original work.  In that event, this author transfers all claim
 * 			and ownership of the modifications to "SmartThings."
 *
 * 	Original Copyright information:
 *
 * 	Copyright 2014 SmartThings
 *
 * 	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * 	in compliance with the License. You may obtain a copy of the License at:
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * 	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * 	for the specific language governing permissions and limitations under the License.
 *
 * This is further modification of the work done by Eric Dalquist (edalquist) at
 * https://github.com/edalquist/hubitat/blob/master/driver/schlage-lock.groovy
 */

metadata {
    definition (name: "Schlage BE469NX", namespace: "org.mynhier", author: "Jeremy Mynhier") {
        capability "Lock"
        capability "Configuration"
    }

    preferences{
        input name: "optEncrypt", type: "bool", title: "Enable lockCode encryption", defaultValue: false, description: ""
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false, description: ""
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true, description: ""
    }
}

import hubitat.zwave.commands.doorlockv1.*
import hubitat.zwave.commands.usercodev1.*

/**
 * Returns the list of commands to be executed when the device is being configured/paired
 *
 */
def configure() {
    state.configured = true
    def cmds = []
    cmds << secure(zwave.doorLockV1.doorLockOperationGet())
    cmds << secure(zwave.batteryV1.batteryGet())
    cmds = delayBetween(cmds, 30 * 1000)
    state.lastLockDetailsQuery = now()
    String descriptionText = "${device.displayName} was configured"
    if (txtEnable) log.info "${descriptionText}"
    cmds
}

def installed() {
    sendEvent(name: "checkInterval", value: 1 * 60 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])
    scheduleInstalledCheck()
}

/**
 * Verify that we have actually received the lock's initial states.
 * If not, verify that we have at least requested them or request them,
 * and check again.
 */
def scheduleInstalledCheck() {
    runIn(120, installedCheck)
}

def updated() {
    log.info "updated..."
    log.warn "description logging is: ${txtEnable == true}"
    log.warn "encryption is: ${optEncrypt == true}"
    //check crnt lockCodes for encryption status
    updateEncryption()
    //turn off debug logs after 30 minutes
    if (logEnable) runIn(1800,logsOff)

    sendEvent(name: "checkInterval", value: 1 * 60 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])

    def hubAction = null
    try {
        def cmds = []
        if (!device.currentState("lock") || !device.currentState("battery") || !state.configured) {
            log.debug "Returning commands for lock operation get and battery get"
            if (!state.configured) {
                cmds << doConfigure()
            }
            cmds << refresh()
            cmds << getLockCodes()
            if (!state.MSR) {
                cmds << zwave.manufacturerSpecificV1.manufacturerSpecificGet().format()
            }
            if (!state.fw) {
                cmds << zwave.versionV1.versionGet().format()
            }
            hubAction = response(delayBetween(cmds, 30*1000))
        }
    } catch (e) {
        log.warn "updated() threw $e"
    }
    hubAction
}

/**
 * Responsible for parsing incoming device messages to generate events
 *
 * @param description : The incoming description from the device
 *
 * @return result: The list of events to be sent out
 *
 */
def parse(String description) {
    def result = null
    if (description.startsWith("Err")) {
            result = createEvent(descriptionText: description, isStateChange: true, displayed: false)
    } else {
        def cmd = zwave.parse(description, [0x98: 1, 0x62: 1, 0x63: 1, 0x71: 2, 0x72: 2, 0x80: 1, 0x85: 2, 0x86: 1])
        if (cmd) {
            result = zwaveEvent(cmd)
        }
    }
    result
}

/**
 * Called when the user taps on the refresh button
 */
def refresh() {
    def cmds = secureSequence([zwave.doorLockV1.doorLockOperationGet(), zwave.batteryV1.batteryGet()])
    if (!state.associationQuery) {
        cmds << "delay 4200"
        cmds << zwave.associationV1.associationGet(groupingIdentifier: 2).format()
        // old Schlage locks use group 2 and don't secure the Association CC
        cmds << secure(zwave.associationV1.associationGet(groupingIdentifier: 1))
        state.associationQuery = now()
    } else if (now() - state.associationQuery.toLong() > 9000) {
        cmds << "delay 6000"
        cmds << zwave.associationV1.associationSet(groupingIdentifier: 2, nodeId: zwaveHubNodeId).format()
        cmds << secure(zwave.associationV1.associationSet(groupingIdentifier: 1, nodeId: zwaveHubNodeId))
        cmds << zwave.associationV1.associationGet(groupingIdentifier: 2).format()
        cmds << secure(zwave.associationV1.associationGet(groupingIdentifier: 1))
        state.associationQuery = now()
    }
    state.lastLockDetailsQuery = now()
    cmds
}

def lock(){
    String descriptionText = "${device.displayName} was locked"
    if (txtEnable) log.info "${descriptionText}"
    lockAndCheck(DoorLockOperationSet.DOOR_LOCK_MODE_DOOR_SECURED)
}

/**
 * Executes unlock command on a lock
 */
def unlock() {
    String descriptionText = "${device.displayName} was unlocked"
    if (txtEnable) log.info "${descriptionText}"
    lockAndCheck(DoorLockOperationSet.DOOR_LOCK_MODE_DOOR_UNSECURED)
}

/**
 * Executes lock and then check command with a delay on a lock
 */
def lockAndCheck(doorLockMode) {
    secureSequence([
            zwave.doorLockV1.doorLockOperationSet(doorLockMode: doorLockMode),
            zwave.doorLockV1.doorLockOperationGet()
    ], 4200)
}

/**
 * Encapsulates a command
 *
 * @param cmd : The command to be encapsulated
 *
 * @returns ret: The encapsulated command
 */
private secure(hubitat.zwave.Command cmd) {
    zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
}

/**
 * Encapsulates list of command and adds a delay
 *
 * @param commands : The list of command to be encapsulated
 *
 * @param delay : The delay between commands
 *
 * @returns The encapsulated commands
 */
private secureSequence(commands, delay = 4200) {
    delayBetween(commands.collect { secure(it) }, delay)
}

/**
 * Responsible for parsing SecurityMessageEncapsulation command
 *
 * @param cmd : The SecurityMessageEncapsulation command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
    def encapsulatedCommand = cmd.encapsulatedCommand([0x62: 1, 0x71: 2, 0x80: 1, 0x85: 2, 0x63: 1, 0x98: 1, 0x86: 1])
    if (encapsulatedCommand) {
        zwaveEvent(encapsulatedCommand)
    }
}

/**
 * Responsible for parsing DoorLockOperationReport command
 *
 * @param cmd : The DoorLockOperationReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(DoorLockOperationReport cmd) {
    def result = []

    unschedule("followupStateCheck")
    unschedule("stateCheck")

    def map = [name: "lock"]
    map.data = [lockName: device.displayName]
    if (cmd.doorLockMode == 0xFF) {
        map.value = "locked"
        map.descriptionText = "Locked"
    } else if (cmd.doorLockMode >= 0x40) {
        map.value = "unknown"
        map.descriptionText = "Unknown state"
    } else if (cmd.doorLockMode == 0x01) {
        map.value = "unlocked with timeout"
        map.descriptionText = "Unlocked with timeout"
    } else {
        map.value = "unlocked"
        map.descriptionText = "Unlocked"
        if (state.assoc != zwaveHubNodeId) {
            result << response(secure(zwave.associationV1.associationSet(groupingIdentifier: 1, nodeId: zwaveHubNodeId)))
            result << response(zwave.associationV1.associationSet(groupingIdentifier: 2, nodeId: zwaveHubNodeId))
            result << response(secure(zwave.associationV1.associationGet(groupingIdentifier: 1)))
        }
    }
    return result ? [createEvent(map), *result] : createEvent(map)
}

/**
 * Responsible for parsing BatteryReport command
 *
 * @param cmd : The BatteryReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
    def map = [name: "battery", unit: "%"]
    if (cmd.batteryLevel == 0xFF) {
        map.value = 1
        map.descriptionText = "Has a low battery"
    } else {
        map.value = cmd.batteryLevel
        map.descriptionText = "Battery is at ${cmd.batteryLevel}%"
    }
    state.lastbatt = now()
    createEvent(map)
}

/**
 * Responsible for parsing AssociationReport command
 *
 * @param cmd : The AssociationReport command to be parsed
 *
 * @return The event(s) to be sent out
 *
 */
def zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
    def result = []
    if (cmd.nodeId.any { it == zwaveHubNodeId }) {
        state.remove("associationQuery")
        state["associationQuery"] = null
        result << createEvent(descriptionText: "Is associated")
        state.assoc = zwaveHubNodeId
        if (cmd.groupingIdentifier == 2) {
            result << response(zwave.associationV1.associationRemove(groupingIdentifier: 1, nodeId: zwaveHubNodeId))
        }
    } else if (cmd.groupingIdentifier == 1) {
        result << response(secure(zwave.associationV1.associationSet(groupingIdentifier: 1, nodeId: zwaveHubNodeId)))
    } else if (cmd.groupingIdentifier == 2) {
        result << response(zwave.associationV1.associationSet(groupingIdentifier: 2, nodeId: zwaveHubNodeId))
    }
    result
}
