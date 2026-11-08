package dev.couchpad.domain

/** Buttons of a SNES-style controller. [wire] is the code the phone sends. */
enum class ControllerButton(val wire: String) {
    UP("U"),
    DOWN("D"),
    LEFT("L"),
    RIGHT("R"),
    A("A"),
    B("B"),
    X("X"),
    Y("Y"),
    SHOULDER_L("SL"),
    SHOULDER_R("SR"),
    START("ST"),
    SELECT("SE");

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(code: String): ControllerButton? = byWire[code]
    }
}
