definition(
        name: "Humidity Controller",
        namespace: "org.mynhier",
        author: "Jeremy Mynhier",
        description: "Controls humidity with respect to an outside temperature",
        category: "My Apps",
        iconUrl: "",
        iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Humidity Controller", uninstall: true, install: true) {
        section {
            input "appName", "text", title: "Name this instance of Humidity Controller", submitOnChange: true
            if (appName) app.updateLabel(appName)
            input "temperatureSensor", "capability.temperatureMeasurement", title: "Outdoor temperature Sensor to be used", multiple: false
            input "humiditySensors", "capability.relativeHumidityMeasurement", title: "Indoor Humidity Sensor(s) to be used", multiple: true
            input "thermostats", "capability.thermostat", title: "Thermostats to be monitored for mode", multiple: true
            input "humidifierSwitches", "capability.switch", title: "Humidifier Switches", multiple: true
            input "waterHeaterSwitches", "capability.switch", title: "Water Heater Switches", multiple: true
            input "maxHumidity", "number", title: "Maximum humidity to shut off humidifiers", default: 60, submitOnChange: true
            input "waterHeaterOffDelay", "number", title: "Number of Hours for Water heater to continue to heat after off", default: 24, submitOnChange: true
            input "loggingEnabled", "bool", title: "Enable Logging", required: false, multiple: false, defaultValue: true, submitOnChange: true
            input "debugLoggingEnabled", "bool", title: "Enable Debug Logging", required: false, multiple: false, defaultValue: false, submitOnChange: true
        }
    }
}

void installed() {
    init()
}

void init() {
    unsubscribe()
    state.temperatureMap = ['-20':15, '-10':20, 0:25, 10:30, 20:35, 30:40, 40:45]
    subscribe(temperatureSensor, "temperature", temperatureHandler)
    subscribe(humiditySensors, "humidity", humidityHandler)
    subscribe(thermostats, "thermostatOperatingState", thermostatModeHandler)
}

void temperatureHandler(evt) {
    setHumiditySetPoint()
    humidityHandler(evt)
}

void humidityHandler(evt) {
    evaluateHumidity()
}

void thermostatModeHandler(evt){
    logger("In thermostat handler", "debug")
    evaluateThermostats()
}

void evaluateThermostats() {
    state.anyCooling = false;
    thermostats.each {
        if(it.currentthermostatOperatingState == 'cooling') {
            state.anyCooling = true;
        }
    }
    humidityHandler(evt)
}

void updated() {
    init()
}

void uninstalled() {
    state = null
    unsubscribe()
}

void allOff() {
    boolean switchesOff = false
    humidifierSwitches.each {
        if(it.currentSwitch == "on"){
            it.off()
            switchesOff = true
        }
    }
    if(switchesOff && waterHeaterSwitches.any{waterHeaterSwitch -> waterHeaterSwitch.currentSwitch == 'on'}){
        unschedule("delayWaterHeaterOffHandler")
        runIn(waterHeaterOffDelay*3600, "delayWaterHeaterOffHandler")
    }
}

void delayWaterHeaterOffHandler(){
    waterHeaterSwitches.each{
        if(it.currentSwitch == "on"){
            it.off()
        }
    }
}

void allOn() {
    unschedule("delayWaterHeaterOffHandler")
    humidifierSwitches.each{
        if(it.currentSwitch == "off"){
            it.on()
        }
    }
    hotWaterHeaterSwitches.each{
        if(it.currentSwitch == "off"){
            it.on()
        }
    }
}

void evaluateHumidity() {
    if(humiditySensors.any { humiditySensor -> humiditySensor.currentHumidity >= maxHumidity } ||
            humiditySensors.every { humiditySensor -> humiditySensor.currentHumidity >= state.humiditySetPoint } ||
            state.anyCooling
    ){
        logger("Turning all off", "info")
        allOff()
    } else {
        logger("Turning all on", "info")
        allOn()
    }
}

void setHumiditySetPoint() {
    Boolean setPointChanged = false
    state.temperature = temperatureSensor.currentTemperature.intValue()
    state.temperatureMap.toSorted().each { key, value ->
        if (state.temperature >= Integer.valueOf(key)) {
            state.humiditySetPoint = value
            setPointChanged = true
        }
    }
    if(!setPointChanged){
        state.humiditySetPoint = 10
    }
    logger("Setting humidity set point to $state.humiditySetPoint%RH", "info")
}

void logger(msg, level){
    if(debugLoggingEnabled){
        log."${level}" "${app.name} ${msg}"
    } else if(loggingEnabled){
        if(level == "info"){
            log."${level}" "${app.name} ${msg}"
        }
    }
}