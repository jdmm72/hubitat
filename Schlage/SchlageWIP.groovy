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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import hubitat.zwave.commands.doorlockv1.*
import hubitat.zwave.commands.usercodev1.*
import groovy.transform.Field

metadata {
    definition (name: "Schlage BE469NX", namespace: "org.mynhier", author: "Jeremy Mynhier") {
        capability "Lock"
        capability "Configuration"
        capability "Refresh"
        capability "Lock Codes"

        attribute "alarmMode", "string"        // "unknown", "Off", "Alert", "Tamper", "Kick"
        attribute "alarmSensitivity", "number"    // 0 is unknown, otherwise 1-5 scaled to 1-99
        attribute "beeperMode", "string"

        command "setAlarmMode", [[name:"Alarm Mode", type: "ENUM", description: "", constraints: ["Off", "Alert", "Tamper", "Kick"]]]
        command "setAlarmSensitivity", [[name:"Alarm Sensitivity", type: "ENUM", description: "", constraints: [1,2,3,4,5]]]
        command "setBeeperMode"
    }

    preferences{
        input name: "optEncrypt", type: "bool", title: "Enable lockCode encryption", defaultValue: false, description: ""
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false, description: ""
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true, description: ""
    }
}

@Field static final Integer TWELVE_HOURS_IN_MILLIS = 43200000
@Field static final Integer THIRTY_SECONDS_IN_MILLIS = 30000
@Field static final Integer ONE_HOUR_IN_SECONDS = 3600
@Field static final Integer ONE_HALF_HOUR_IN_SECONDS = 1800
@Field static final Integer ASSOCIATION_QUERY_CHARACTER_LIMIT = 9000

/**
 * Returns the list of commands to be executed when the device is being configured/paired
 *
 */
def configure() {
    state.configured = true
    def cmds = []
    cmds << secure(zwave.doorLockV1.doorLockOperationGet())
    cmds << secure(zwave.batteryV1.batteryGet())
    cmds = delayBetween(cmds, THIRTY_SECONDS_IN_MILLIS)
    String descriptionText = "${device.displayName} was configured"
    if (txtEnable) log.info "${descriptionText}"
    cmds
}

def installed() {
    sendEvent(name: "checkInterval", value: ONE_HOUR_IN_SECONDS, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])
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

void updated() {
    log.info "updated..."
    log.warn "description logging is: ${txtEnable == true}"
    log.warn "encryption is: ${optEncrypt == true}"
    //check crnt lockCodes for encryption status
    updateEncryption()
    //turn off debug logs after 30 minutes
    if (logEnable) runIn(ONE_HALF_HOUR_IN_SECONDS,logsOff)
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
    } else if (now() - state.associationQuery.toLong() > ASSOCIATION_QUERY_CHARACTER_LIMIT) {
        cmds << "delay 6000"
        cmds << zwave.associationV1.associationSet(groupingIdentifier: 2, nodeId: zwaveHubNodeId).format()
        cmds << secure(zwave.associationV1.associationSet(groupingIdentifier: 1, nodeId: zwaveHubNodeId))
        cmds << zwave.associationV1.associationGet(groupingIdentifier: 2).format()
        cmds << secure(zwave.associationV1.associationGet(groupingIdentifier: 1))
        state.associationQuery = now()
    }
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

def zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
    def result = []
    def map = null        // use this for config reports that are handled

    // use desc/val for generic handling of config reports (it will just send a descriptionText for the acitivty stream)
    def desc = null
    def val = ""

    switch (cmd.parameterNumber) {
        case 0x3:
            map = parseBinaryConfigRpt('beeperMode', cmd.configurationValue[0], 'Beeper Mode')
            break

            // done:  vacation mode toggle
        case 0x4:
            map = parseBinaryConfigRpt('vacationMode', cmd.configurationValue[0], 'Vacation Mode')
            break

            // done: lock and leave mode
        case 0x5:
            map = parseBinaryConfigRpt('lockLeave', cmd.configurationValue[0], 'Lock & Leave')
            break

            // these don't seem to be useful.  It's just a bitmap of the code slots used.
        case 0x6:
            desc = "User Slot Bit Fields"
            val = "${cmd.configurationValue[3]} ${cmd.configurationValue[2]} ${cmd.configurationValue[1]} ${cmd.configurationValue[0]}"
            break

            // done:  the alarm mode of the lock.
        case 0x7:
            map = [name: "alarmMode", displayed: true]
            // when getting the alarm mode, also query the sensitivity for that current alarm mode
            switch (cmd.configurationValue[0]) {
                case 0x00:
                    map.value = "Off_alarmMode"
                    break
                case 0x01:
                    map.value = "Alert_alarmMode"
                    result << response(secure(zwave.configurationV2.configurationGet(parameterNumber: 0x08)))
                    break
                case 0x02:
                    map.value = "Tamper_alarmMode"
                    result << response(secure(zwave.configurationV2.configurationGet(parameterNumber: 0x09)))
                    break
                case 0x03:
                    map.value = "Kick_alarmMode"
                    result << response(secure(zwave.configurationV2.configurationGet(parameterNumber: 0x0A)))
                    break
                default:
                    map.value = "unknown_alarmMode"
            }
            map.descriptionText = "$device.displayName Alarm Mode set to \"$map.value\""
            break

            // done: alarm sensitivities - one for each mode
        case 0x8:
        case 0x9:
        case 0xA:
            def whichMode = null
            switch (cmd.parameterNumber) {
                case 0x8:
                    whichMode = "Alert"
                    break;
                case 0x9:
                    whichMode = "Tamper"
                    break;
                case 0xA:
                    whichMode = "Kick"
                    break;
            }
            def curAlarmMode = device.currentValue("alarmMode")
            val = "${cmd.configurationValue[0]}"

            // the lock has sensitivity values between 1 and 5. We set the slider's range ("1".."5") in the Tile's Definition
            def modifiedValue = cmd.configurationValue[0]

            map = [descriptionText: "$device.displayName Alarm $whichMode Sensitivity set to $val", displayed: true]

            if (curAlarmMode == "${whichMode}_alarmMode") {
                map.name = "alarmSensitivity"
                map.value = modifiedValue
            } else {
                log.debug "got sensitivity for $whichMode while in $curAlarmMode"
                map.isStateChange = true
            }

            break

        case 0xB:
            map = parseBinaryConfigRpt('localControl', cmd.configurationValue[0], 'Local Alarm Control')
            break

            // how many times has the electric motor locked or unlock the device?
        case 0xC:
            desc = "Electronic Transition Count"
            def ttl = cmd.configurationValue[3] + (cmd.configurationValue[2] * 0x100) + (cmd.configurationValue[1] * 0x10000) + (cmd.configurationValue[0] * 0x1000000)
            val = "$ttl"
            break

            // how many times has the device been locked or unlocked manually?
        case 0xD:
            desc = "Mechanical Transition Count"
            def ttl = cmd.configurationValue[3] + (cmd.configurationValue[2] * 0x100) + (cmd.configurationValue[1] * 0x10000) + (cmd.configurationValue[0] * 0x1000000)
            val = "$ttl"
            break

            // how many times has there been a failure by the electric motor?  (due to jamming??)
        case 0xE:
            desc = "Electronic Failed Count"
            def ttl = cmd.configurationValue[3] + (cmd.configurationValue[2] * 0x100) + (cmd.configurationValue[1] * 0x10000) + (cmd.configurationValue[0] * 0x1000000)
            val = "$ttl"
            break

            // done: auto lock mode
        case 0xF:
            map = parseBinaryConfigRpt('autoLock', cmd.configurationValue[0], 'Auto Lock')
            break

            // this will be useful as an attribute/command usable by a smartapp
        case 0x10:
            map = [name: 'pinLength', value: cmd.configurationValue[0], displayed: true, descriptionText: "$device.displayName PIN length configured to ${cmd.configurationValue[0]} digits"]
            break

            // not sure what this one stores
        case 0x11:
            desc = "Electronic High Preload Transition Count"
            def ttl = cmd.configurationValue[3] + (cmd.configurationValue[2] * 0x100) + (cmd.configurationValue[1] * 0x10000) + (cmd.configurationValue[0] * 0x1000000)
            val = "$ttl"
            break

            // ???
        case 0x12:
            desc = "Bootloader Version"
            val = "${cmd.configurationValue[0]}"
            break
        default:
            desc = "Unknown parameter ${cmd.parameterNumber}"
            val = "${cmd.configurationValue[0]}"
            break
    }
    if (map) {
        result << createEvent(map)
    } else if (desc != null) {
        // generic description text
        result << createEvent([descriptionText: "$device.displayName reports \"$desc\" configured as \"$val\"", displayed: true, isStateChange: true])
    }
    result
}

def parseBinaryConfigRpt(paramName, paramValue, paramDesc) {
    def map = [name: paramName, displayed: true]

    def newVal = "on"
    if (paramValue == 0) {
        newVal = "off"
    }
    map.value = "${newVal}_${paramName}"
    map.descriptionText = "$device.displayName $paramDesc has been turned $newVal"
    return map
}

def zwaveEvent(hubitat.zwave.commands.alarmv2.AlarmReport cmd) {
    def result = []
    def map = null
    if (cmd.zwaveAlarmType == 6) // ZWAVE_ALARM_TYPE_ACCESS_CONTROL
    {
        if (1 <= cmd.zwaveAlarmEvent && cmd.zwaveAlarmEvent < 10) {
            map = [name: "lock", value: (cmd.zwaveAlarmEvent & 1) ? "locked" : "unlocked"]
        }
        switch (cmd.zwaveAlarmEvent) {
            case 1:
                map.descriptionText = "$device.displayName was manually locked"
                map.data = [usedCode: "manual"]
                break
            case 2:
                map.descriptionText = "$device.displayName was manually unlocked"
                map.data = [usedCode: "manual"]
                break
            case 5:
                if (cmd.eventParameter) {
                    map.descriptionText = "$device.displayName was locked with code ${cmd.eventParameter.first()}"
                    map.data = [usedCode: cmd.eventParameter[0]]
                } else {
                    map.descriptionText = "$device.displayName was locked with keypad"
                    map.data = [usedCode: -1]
                }
                break
            case 6:
                if (cmd.eventParameter) {
                    map.descriptionText = "$device.displayName was unlocked with code ${cmd.eventParameter.first()}"
                    map.data = [usedCode: cmd.eventParameter[0]]
                } else {
                    map.descriptionText = "$device.displayName was unlocked with keypad"
                    map.data = [usedCode: -1]
                }
                break
            case 9:
                map.descriptionText = "$device.displayName was autolocked"
                map.data = [usedCode: "auto"]
                break
            case 7:
            case 8:
            case 0xA:
                map = [name: "lock", value: "unknown", descriptionText: "$device.displayName was not locked fully"]
                break
            case 0xB:
                map = [name: "lock", value: "unknown", descriptionText: "$device.displayName is jammed", eventType: "ALERT", displayed: true]
                break
            case 0xC:
                map = [name: "codeChanged", value: "all", descriptionText: "$device.displayName: all user codes deleted", displayed: true, isStateChange: true]
                allCodesDeleted()
                break
            case 0xD:
                if (cmd.eventParameter) {
                    map = [name: "codeReport", value: cmd.eventParameter[0], data: [code: ""], isStateChange: true]
                    map.descriptionText = "$device.displayName code ${map.value} was deleted"
                    map.isStateChange = (state["code$map.value"] != "")
                    state["code$map.value"] = ""
                } else {
                    map = [name: "codeChanged", descriptionText: "$device.displayName: user code deleted", isStateChange: true]
                }
                break
            case 0xE:
                map = [name: "codeChanged", value: cmd.alarmLevel, descriptionText: "$device.displayName: user code added", isStateChange: true]
                if (cmd.eventParameter) {
                    map.value = cmd.eventParameter[0]
                    result << response(requestCode(cmd.eventParameter[0]))
                }
                break
            case 0xF:
                map = [name: "tamper", value: "detected", descriptionText: "$device.displayName: Too many user code failures.", eventType: "ALERT", displayed: true, isStateChange: true]
                break
                // map = [ name: "codeChanged", descriptionText: "$device.displayName: user code not added, duplicate", isStateChange: true ]
                // break
            case 0x10:
                map = [name: "tamper", value: "detected", descriptionText: "$device.displayName: keypad temporarily disabled", displayed: true]
                break
            case 0x11:
                map = [descriptionText: "$device.displayName: keypad is busy"]
                break
            case 0x12:
                map = [name: "codeChanged", descriptionText: "$device.displayName: program code changed", isStateChange: true]
                break
            case 0x13:
                map = [name: "tamper", value: "detected", descriptionText: "$device.displayName: code entry attempt limit exceeded", displayed: true]
                break
            default:
                map = map ?: [descriptionText: "$device.displayName: alarm event $cmd.zwaveAlarmEvent", displayed: false]
                break
        }
    } else if (cmd.zwaveAlarmType == 7) // ZWAVE_ALARM_TYPE_BURGLAR
    {
        map = [name: "tamper", value: "detected", displayed: true, isStateChange: true]
        switch (cmd.zwaveAlarmEvent) {
            case 0:
                map.value = "clear"
                map.descriptionText = "$device.displayName: tamper alert cleared"
                break
            case 1:
            case 2:
                map.descriptionText = "$device.displayName: intrusion attempt detected"
                break
            case 3:
                map.descriptionText = "$device.displayName: covering removed"
                break
            case 4:
                map.descriptionText = "$device.displayName: invalid code"
                break
            default:
                map.descriptionText = "$device.displayName: tamper alarm $cmd.zwaveAlarmEvent"
                break
        }
    } else switch (cmd.alarmType) {
            case 9:
            case 17:
            case 23:
            case 26:
                map = [name: "lock", value: "unknown", descriptionText: "$device.displayName bolt is jammed"]
                break
            case 13:
                map = [name: "codeChanged", value: cmd.alarmLevel, descriptionText: "$device.displayName code $cmd.alarmLevel was added", isStateChange: true]
                result << response(requestCode(cmd.alarmLevel))
                break
            case 32:
                map = [name: "codeChanged", value: "all", descriptionText: "$device.displayName: all user codes deleted", isStateChange: true]
                allCodesDeleted()
            case 33:
                map = [name: "codeReport", value: cmd.alarmLevel, data: [code: ""], isStateChange: true]
                map.descriptionText = "$device.displayName code $cmd.alarmLevel was deleted"
                map.isStateChange = (state["code$cmd.alarmLevel"] != "")
                state["code$cmd.alarmLevel"] = ""
                break
            case 112:
                map = [name: "codeChanged", value: cmd.alarmLevel, descriptionText: "$device.displayName code $cmd.alarmLevel changed", isStateChange: true]
                result << response(requestCode(cmd.alarmLevel))
                break
            case 130:  // Yale YRD batteries replaced
                map = [descriptionText: "$device.displayName batteries replaced", isStateChange: true]
                break
            case 131:
                map = [ /*name: "codeChanged", value: cmd.alarmLevel,*/ descriptionText: "$device.displayName code $cmd.alarmLevel is duplicate", isStateChange: false]
                break
            case 161:
                if (cmd.alarmLevel == 2) {
                    map = [descriptionText: "$device.displayName front escutcheon removed", isStateChange: true]
                } else {
                    map = [descriptionText: "$device.displayName detected failed user code attempt", isStateChange: true]
                }
                break
            case 167:
                if (!state.lastbatt || (new Date().time) - state.lastbatt > TWELVE_HOURS_IN_MILLIS) {
                    map = [descriptionText: "$device.displayName: battery low", isStateChange: true]
                    result << response(secure(zwave.batteryV1.batteryGet()))
                } else {
                    map = [name: "battery", value: device.currentValue("battery"), descriptionText: "$device.displayName: battery low", displayed: true]
                }
                break
            case 168:
                map = [name: "battery", value: 1, descriptionText: "$device.displayName: battery level critical", displayed: true]
                break
            case 169:
                map = [name: "battery", value: 0, descriptionText: "$device.displayName: battery too low to operate lock", isStateChange: true]
                break
            default:
                map = [displayed: false, descriptionText: "$device.displayName: alarm event $cmd.alarmType level $cmd.alarmLevel"]
                break
        }
    result ? [createEvent(map), *result] : createEvent(map)
}

def zwaveEvent(UserCodeReport cmd) {
    def result = []
    def name = "code$cmd.userIdentifier"
    def code = cmd.userCode
    def map = [:]
    if (cmd.userIdStatus == UserCodeReport.USER_ID_STATUS_OCCUPIED ||
            (cmd.userIdStatus == UserCodeReport.USER_ID_STATUS_STATUS_NOT_AVAILABLE)) {
        if (code == "**********") {  // Schlage locks send us this instead of the real code
            state.blankcodes = true
            code = state["set$name"] ?: decrypt(state[name]) ?: code
            state.remove("set$name".toString())
        }
        if (!code && cmd.userIdStatus == 1) {  // Schlage touchscreen sends blank code to notify of a changed code
            map = [name: "codeChanged", value: cmd.userIdentifier, displayed: true, isStateChange: true]
            map.descriptionText = "$device.displayName code $cmd.userIdentifier " + (state[name] ? "changed" : "was added")
            code = state["set$name"] ?: decrypt(state[name]) ?: "****"
            state.remove("set$name".toString())
        } else {
            map = [name: "codeReport", value: cmd.userIdentifier, data: [code: code]]
            map.descriptionText = "$device.displayName code $cmd.userIdentifier is set"
            map.displayed = (cmd.userIdentifier != state.requestCode && cmd.userIdentifier != state.pollCode)
            map.isStateChange = true
            updateLockCodes(getLockCodes())
        }
        result << createEvent(map)
    } else {
        map = [name: "codeReport", value: cmd.userIdentifier, data: [code: ""]]
        if (state.blankcodes && state["reset$name"]) {
            // we deleted this code so we can tell that our new code gets set
            map.descriptionText = "$device.displayName code $cmd.userIdentifier was reset"
            map.displayed = map.isStateChange = false
            result << createEvent(map)
            state["set$name"] = state["reset$name"]
            result << response(setCode(cmd.userIdentifier, state["reset$name"]))
            state.remove("reset$name".toString())
        } else {
            if (state[name]) {
                updateLockCodes(state.requestedChange)
                state.requestedChange = null
                Map data = ["${codeNumber}": codeMap]
                map.descriptionText = "$device.displayName code $cmd.userIdentifier was deleted"

            } else {
                map.descriptionText = "$device.displayName code $cmd.userIdentifier is not set"
            }
            map.displayed = (cmd.userIdentifier != state.requestCode && cmd.userIdentifier != state.pollCode)
            map.isStateChange = true
            result << createEvent(map)
        }
        code = ""
    }
    state[name] = code ? encrypt(code) : code

    if (cmd.userIdentifier == state.requestCode) {  // reloadCodes() was called, keep requesting the codes in order
        if (state.requestCode + 1 > state.codes || state.requestCode >= 30) {
            state.remove("requestCode")  // done
        } else {
            state.requestCode = state.requestCode + 1  // get next
            result << response(requestCode(state.requestCode))
        }
    }
    if (cmd.userIdentifier == state.pollCode) {
        if (state.pollCode + 1 > state.codes || state.pollCode >= 30) {
            state.remove("pollCode")  // done
        } else {
            state.pollCode = state.pollCode + 1
        }
    }
    log.debug "code report parsed to ${result.inspect()}"
    state.remove("blankcodes")
    state.remove("requestedChange")
    result
}


def zwaveEvent(UsersNumberReport cmd) {
    def result = []
    state.codes = cmd.supportedUsers
    if (state.requestCode && state.requestCode <= cmd.supportedUsers) {
        result << response(requestCode(state.requestCode))
    }
    result
}

// all the on/off parameters work the same way, so make a common method
// to deal with them
//
def setOnOffParameter(paramName, paramNumber) {
    def cmds = null
    def cs = device.currentValue(paramName)

    // change parameter to the 'unknown' value - it will get refreshed after it is done changing
    sendEvent(name: paramName, value: "unknown_${paramName}", displayed: false)

    if (cs == "on_${paramName}") {
        // turn it off
        cmds = secureSequence([zwave.configurationV2.configurationSet(parameterNumber: paramNumber, size: 1, configurationValue: [0])], 5000)
    } else if (cs == "off_${paramName}") {
        // turn it on
        cmds = secureSequence([zwave.configurationV2.configurationSet(parameterNumber: paramNumber, size: 1, configurationValue: [0xFF])], 5000)
    } else {
        // it's in an unknown state, so just query it
        cmds = secureSequence([zwave.configurationV2.configurationGet(parameterNumber: paramNumber)], 5000)
    }

    log.debug "set $paramName sending ${cmds.inspect()}"

    cmds
}

def setBeeperMode() {
    setOnOffParameter("beeperMode", 0x3)
}

def setAlarmMode(def newValue = null) {

    def cs = device.currentValue("alarmMode")
    def newMode = 0x0

    def cmds = null

    if (newValue == null) {
        switch (cs) {
            case "Off_alarmMode":
                newMode = 0x1
                break
            case "Alert_alarmMode":
                newMode = 0x2
                break
            case "Tamper_alarmMode":
                newMode = 0x3
                break
            case "Kick_alarmMode":
                newMode = 0x0
                break
            case "unknown_alarmMode":
            default:
                // don't send a mode - instead request the current state
                cmds = secureSequence([zwave.configurationV2.configurationGet(parameterNumber: 0x7)], 5000)

        }
    } else {
        switch (newValue) {
            case "Off":
                newMode = 0x0
                break
            case "Alert":
                newMode = 0x1
                break
            case "Tamper":
                newMode = 0x2
                break
            case "Kick":
                newMode = 0x3
                break
            default:
                // don't send a mode - instead request the current state
                cmds = secureSequence([zwave.configurationV2.configurationGet(parameterNumber: 0x7)], 5000)

        }
    }
    if (cmds == null) {
        // change the alarmSensitivity to the 'unknown' value - it will get refreshed after the alarm mode is done changing
        sendEvent(name: 'alarmSensitivity', value: 0, displayed: false)
        cmds = secureSequence([zwave.configurationV2.configurationSet(parameterNumber: 7, size: 1, configurationValue: [newMode])], 5000)
    }

    log.debug "setAlarmMode sending ${cmds.inspect()}"
    cmds
}

def setAlarmSensitivity(newValue) {
    def cmds = null
    if (newValue != null) {
        // newvalue will be between 1 and 5 inclusive as controlled by the slider's range definition
        newValue = newValue.toInteger();

        // there are three possible values to set.	which one depends on the current alarmMode
        def cs = device.currentValue("alarmMode")

        def paramToSet = 0

        switch (cs) {
            case "Off_alarmMode":
                // do nothing.	the slider should be disabled anyway
                break
            case "Alert_alarmMode":
                // set param 8
                paramToSet = 0x8
                break
            case "Tamper_alarmMode":
                paramToSet = 0x9
                break
            case "Kick_alarmMode":
                paramToSet = 0xA
                break
            case "unknown_alarmMode":
            default:
                sendEvent(descriptionText: "$device.displayName unable to set alarm sensitivity while alarm mode in unknown state", displayed: true, isStateChange: true)
                break
        }
        if (paramToSet != 0) {
            // first set the attribute to 0 for UI purposes
            sendEvent(name: 'alarmSensitivity', value: 0, displayed: false)
            // then add the actual attribute set call
            cmds = secureSequence([zwave.configurationV2.configurationSet(parameterNumber: paramToSet, size: 1, configurationValue: [newValue])], 5000)
            log.debug "setAlarmSensitivity sending ${cmds.inspect()}"
        }
    }
    cmds
}

def setCodeLength(newValue) {
    def cmds = null
    if ((newValue == null) || (newValue == 0)) {
        // just send a request to refresh the value
        cmds = secureSequence([zwave.configurationV2.configurationGet(parameterNumber: 0x10)], 5000)
    } else if (newValue <= 8) {
        sendEvent(descriptionText: "$device.displayName attempting to change PIN length to $newValue", displayed: true, isStateChange: true)
        cmds = secureSequence([zwave.configurationV2.configurationSet(parameterNumber: 10, size: 1, configurationValue: [newValue])], 5000)
    } else {
        sendEvent(descriptionText: "$device.displayName UNABLE to set PIN length of $newValue", displayed: true, isStateChange: true)
    }
    log.debug "setPinLength sending ${cmds}"
    cmds
}

def getCodes() {
    Map codeMap = getLockCodes()
    log.debug "codeMap: $codeMap"
    codeMap
}

def setCode(codeNumber, code, name) {
    if (codeNumber == null || codeNumber == 0 || code == null) return

    if (logEnable) log.debug "setCode- ${codeNumber}"

    if (!name) name = "code #${codeNumber}"

    lockCodes = getLockCodes()
    Map codeMap = getCodeMap(lockCodes,codeNumber)
    if (!changeIsValid(lockCodes,codeMap,codeNumber,code,name)) return

    Map data = [:]
    String value

    if (codeMap) {
        if (codeMap.name != name || codeMap.code != code) {
            codeMap = ["name":"${name}", "code":"${code}"]
            lockCodes."${codeNumber}" = codeMap
            data = ["${codeNumber}":codeMap]
            value = "changed"
        }
    } else {
        codeMap = ["name":"${name}", "code":"${code}"]
        data = ["${codeNumber}":codeMap]
        lockCodes << data
    }
    sendEvent(name:"lockCodes", value:JsonOutput.toJson(lockCodes), isStateChange:true)
    secureSequence([
            zwave.userCodeV1.userCodeSet(userIdentifier: codeNumber, userIdStatus: 1, userCode: code),
            zwave.userCodeV1.userCodeGet(userIdentifier: codeNumber)
    ], 7000)
}

void updateLockCodes(lockCodes){
    /*
	whenever a code changes we update the lockCodes event
	*/
    String strCodes = JsonOutput.toJson(lockCodes)
    if (optEncrypt) {
        strCodes = encrypt(strCodes)
    }
    sendEvent(name:"lockCodes", value:strCodes, isStateChange:true)
}

Map getLockCodes() {
    /*
	on a real lock we would fetch these from the response to a userCode report request
	*/
    String lockCodes = device.currentValue("lockCodes")
    Map result = [:]
    if (lockCodes) {
        //decrypt codes if they're encrypted
        if (lockCodes[0] == "{") result = new JsonSlurper().parseText(lockCodes)
        else result = new JsonSlurper().parseText(decrypt(lockCodes))
    }
    return result
}

Map getCodeMap(lockCodes,codeNumber){
    Map codeMap = [:]
    Map lockCode = lockCodes?."${codeNumber}"
    if (lockCode) {
        codeMap = ["name":"${lockCode.name}", "code":"${lockCode.code}"]
    }
    return codeMap
}

Boolean changeIsValid(lockCodes,codeMap,codeNumber,code,name){
    //validate proposed lockCode change
    Boolean result = true
    Integer maxCodeLength = device.currentValue("codeLength")?.toInteger() ?: 4
    Integer maxCodes = device.currentValue("maxCodes")?.toInteger() ?: 20
    Boolean isBadLength = code.size() > maxCodeLength
    Boolean isBadCodeNum = maxCodes < codeNumber
    if (lockCodes) {
        List nameSet = lockCodes.collect{ it.value.name }
        List codeSet = lockCodes.collect{ it.value.code }
        if (codeMap) {
            nameSet = nameSet.findAll{ it != codeMap.name }
            codeSet = codeSet.findAll{ it != codeMap.code }
        }
        Boolean nameInUse = name in nameSet
        Boolean codeInUse = code in codeSet
        if (nameInUse || codeInUse) {
            if (nameInUse) { log.warn "changeIsValid:false, name:${name} is in use:${ lockCodes.find{ it.value.name == "${name}" } }" }
            if (codeInUse) { log.warn "changeIsValid:false, code:${code} is in use:${ lockCodes.find{ it.value.code == "${code}" } }" }
            result = false
        }
    }
    if (isBadLength || isBadCodeNum) {
        if (isBadLength) { log.warn "changeIsValid:false, length of code ${code} does not match codeLength of ${maxCodeLength}" }
        if (isBadCodeNum) { log.warn "changeIsValid:false, codeNumber ${codeNumber} is larger than maxCodes of ${maxCodes}" }
        result = false
    }
    return result
}

def deleteCode(codeNumber) {
    log.debug "deleting code $codeNumber"
    Map codeMap = getCodeMap(lockCodes,"${codeNumber}")
    if (codeMap) {
        Map result = [:]
        //build new lockCode map, exclude deleted code
        lockCodes.each {
            if (it.key != "${codeNumber}") {
                result << it
            }
        }
        state.requestedChange=result
    }
    secureSequence([
            zwave.userCodeV1.userCodeSet(userIdentifier: codeNumber, userIdStatus: 0),
            zwave.userCodeV1.userCodeGet(userIdentifier: codeNumber)
    ], 7000)
}

void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void updateEncryption(){
    /*
	resend lockCodes map when the encryption option is changed
	*/
    String lockCodes = device.currentValue("lockCodes") //encrypted or decrypted
    if (lockCodes){
        if (optEncrypt && lockCodes[0] == "{") {	//resend encrypted
            sendEvent(name:"lockCodes",value: encrypt(lockCodes))
        } else if (!optEncrypt && lockCodes[0] != "{") {	//resend decrypted
            sendEvent(name:"lockCodes",value: decrypt(lockCodes))
        }
    }
}