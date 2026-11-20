package dev.couchpad.domain

import dev.couchpad.domain.ControllerButton.DOWN
import dev.couchpad.domain.ControllerButton.LEFT
import dev.couchpad.domain.ControllerButton.RIGHT
import dev.couchpad.domain.ControllerButton.UP
import org.junit.Assert.assertEquals
import org.junit.Test

/** Grid of 3 columns with 7 items:  0 1 2 / 3 4 5 / 6 */
class GridNavigatorTest {
    private val grid = GridNavigator(columns = 3)
    private val count = 7

    @Test
    fun `moves within rows and columns`() {
        assertEquals(1, grid.move(0, count, RIGHT))
        assertEquals(3, grid.move(0, count, DOWN))
        assertEquals(1, grid.move(4, count, UP))
        assertEquals(3, grid.move(4, count, LEFT))
    }

    @Test
    fun `edges stop the selection`() {
        assertEquals(0, grid.move(0, count, LEFT))
        assertEquals(0, grid.move(0, count, UP))
        assertEquals(2, grid.move(2, count, RIGHT))
        assertEquals(6, grid.move(6, count, DOWN))
        assertEquals(6, grid.move(6, count, RIGHT))
    }

    @Test
    fun `moving down into a shorter last row lands on its last item`() {
        assertEquals(6, grid.move(5, count, DOWN))
        assertEquals(6, grid.move(4, count, DOWN))
    }

    @Test
    fun `other buttons and empty grids do not move`() {
        assertEquals(4, grid.move(4, count, ControllerButton.A))
        assertEquals(0, grid.move(3, 0, RIGHT))
        assertEquals(6, grid.move(42, count, ControllerButton.B))
    }
}
