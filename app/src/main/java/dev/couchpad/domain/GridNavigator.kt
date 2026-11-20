package dev.couchpad.domain

/**
 * Moves a selection around a grid of [columns] columns filled row by row,
 * the way a console menu does: edges stop the selection, and moving down
 * into a shorter last row lands on its last item.
 */
class GridNavigator(private val columns: Int) {
    init {
        require(columns > 0) { "columns must be positive" }
    }

    fun move(index: Int, count: Int, direction: ControllerButton): Int {
        if (count <= 0) return 0
        val i = index.coerceIn(0, count - 1)
        val column = i % columns
        val row = i / columns
        val lastRow = (count - 1) / columns
        return when (direction) {
            ControllerButton.LEFT -> if (column > 0) i - 1 else i
            ControllerButton.RIGHT -> if (column < columns - 1 && i + 1 < count) i + 1 else i
            ControllerButton.UP -> if (row > 0) i - columns else i
            ControllerButton.DOWN -> when {
                i + columns < count -> i + columns
                row < lastRow -> count - 1
                else -> i
            }
            else -> i
        }
    }
}
