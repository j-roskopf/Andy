package app.andy.model

enum class BatteryHealth(val dumpsysValue: String, val label: String) {
    Good("good", "Good"),
    Overheat("overheat", "Overheat"),
    Dead("dead", "Dead"),
    OverVoltage("overvoltage", "Over voltage"),
    Failure("failure", "Failure"),
    Cold("cold", "Cold"),
    Unknown("unknown", "Unknown"),
}

/** Thermal status codes for `cmd thermalservice override-status` (API 29+). */
enum class ThermalStatus(val code: Int, val label: String) {
    None(0, "None"),
    Light(1, "Light"),
    Moderate(2, "Moderate"),
    Severe(3, "Severe"),
    Critical(4, "Critical"),
    Emergency(5, "Emergency"),
    Shutdown(6, "Shutdown"),
}

enum class EmulatorSensor(val emuName: String, val axes: Int) {
    Accelerometer("acceleration", 3),
    Gyroscope("gyroscope", 3),
    Magnetometer("magnetic-field", 3),
    Orientation("orientation", 3),
    Proximity("proximity", 1),
    Light("light", 1),
    Pressure("pressure", 1),
    Humidity("humidity", 1),
    Temperature("temperature", 1),
}

enum class GsmRegistration(val emuValue: String, val label: String) {
    Unregistered("unregistered", "Unregistered"),
    Home("home", "Home"),
    Roaming("roaming", "Roaming"),
    Searching("searching", "Searching"),
    Denied("denied", "Denied"),
}

enum class GsmDataType(val emuValue: String, val label: String) {
    Gprs("gprs", "GPRS"),
    Edge("edge", "EDGE"),
    Umts("umts", "UMTS"),
    Lte("lte", "LTE"),
    Nr("nr", "NR (5G)"),
}
