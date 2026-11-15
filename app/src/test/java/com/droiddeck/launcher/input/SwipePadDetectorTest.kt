package com.droiddeck.launcher.input

import com.droiddeck.launcher.input.SwipePadDetector.Companion.FIRST_REPEAT_MS
import com.droiddeck.launcher.input.SwipePadDetector.Companion.PRESS_MS
import com.droiddeck.launcher.input.SwipePadDetector.Companion.REPEAT_MS
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SwipePadDetectorTest {
    /** 10 px to the millimetre: a flick is 40 px, a rest under 5 px. */
    private val mm = 10f
    private val events = mutableListOf<String>()
    private var now = 1_000L
    private val pad = SwipePadDetector(SwipePadDetector.FLICK_MM * mm, SwipePadDetector.REST_MM * mm) { direction, down ->
        events += "${direction.id} ${if (down) "down" else "up"} $now"
    }
    private var x = 500f
    private var y = 500f

    /** Runs the detector's clock to [time], firing whatever is due on the way, as the view's timer would. */
    private fun runTo(time: Long) {
        while (pad.deadline <= time) { now = pad.deadline; pad.advance(now) }
        now = time
        pad.advance(time)
    }

    private fun down() { pad.down(x, y, now) }
    private fun up() { pad.up(now) }

    /** Moves the finger by (dx, dy) mm over [ms], one sample every 8 ms as a 120 Hz screen reports it. */
    private fun move(dx: Float, dy: Float, ms: Long) {
        val steps = (ms / 8).coerceAtLeast(1)
        val fromX = x
        val fromY = y
        for (i in 1..steps) {
            runTo(now + ms / steps)
            x = fromX + dx * mm * i / steps
            y = fromY + dy * mm * i / steps
            pad.move(x, y, now)
        }
    }

    private fun hold(ms: Long) {
        val end = now + ms
        while (now + 8 <= end) { runTo(now + 8); pad.move(x, y, now) }
        runTo(end)
    }

    private fun presses() = events.filter { it.contains(" down ") }.map { it.substringBefore(' ') }

    @Test fun aFlickInEachDirectionPressesThatDirectionOnceAndLetsItGo() {
        for ((direction, d) in listOf(InputControl.UP to (0f to -8f), InputControl.DOWN to (0f to 8f),
            InputControl.LEFT to (-8f to 0f), InputControl.RIGHT to (8f to 0f))) {
            events.clear()
            down(); move(d.first, d.second, 80); up()
            runTo(now + 2_000)
            assertEquals("$direction", listOf(direction.id), presses())
            assertEquals("$direction", 2, events.size)
            val (pressedAt, releasedAt) = events.map { it.substringAfterLast(' ').toLong() }
            assertEquals("$direction", PRESS_MS, releasedAt - pressedAt)
            assertEquals("$direction", "${direction.id} up", events[1].substringBeforeLast(' '))
        }
    }

    @Test fun thePressGoesOutWhenTheFlickCrossesTheThresholdNotWhenTheFingerLifts() {
        down()
        move(0f, -3.5f, 40)
        assertTrue(events.isEmpty())
        runTo(now + 8); y -= 1f * mm; pad.move(x, y, now)
        assertEquals(listOf("up down $now"), events)
        move(0f, -.3f, 16)
        up()
        assertEquals(1, presses().size)
    }

    @Test fun theMainAxisOfADiagonalFlickWins() {
        down(); move(5f, -3f, 60); up(); runTo(now + 500)
        assertEquals(listOf("right"), presses())
        events.clear()
        down(); move(-2f, 6f, 60); up(); runTo(now + 500)
        assertEquals(listOf("down"), presses())
    }

    @Test fun jitterUnderTheThresholdPressesNothing() {
        val random = Random(7)
        val homeX = x
        val homeY = y
        down()
        repeat(250) {
            runTo(now + 8)
            x = homeX + (random.nextFloat() * 2.4f - 1.2f) * mm
            y = homeY + (random.nextFloat() * 2.4f - 1.2f) * mm
            pad.move(x, y, now)
        }
        up()
        runTo(now + 1_000)
        assertTrue(events.toString(), events.isEmpty())
    }

    @Test fun aSlowDragThatTakesLongerThanTheFlickWindowPressesNothing() {
        down()
        move(0f, -6f, 600)
        move(9f, 0f, 900)
        up()
        runTo(now + 1_000)
        assertTrue(events.toString(), events.isEmpty())
    }

    @Test fun holdingAfterAFlickRepeatsAfter350MsThenEvery110MsUntilTheLift() {
        down()
        move(0f, 6f, 48)
        val first = events.single().substringAfterLast(' ').toLong()
        hold(FIRST_REPEAT_MS + 3 * REPEAT_MS + 20)
        val pressedAt = events.filter { it.contains(" down ") }.map { it.substringAfterLast(' ').toLong() - first }
        assertEquals(listOf(0L, FIRST_REPEAT_MS, FIRST_REPEAT_MS + REPEAT_MS, FIRST_REPEAT_MS + 2 * REPEAT_MS, FIRST_REPEAT_MS + 3 * REPEAT_MS), pressedAt)
        assertEquals(List(5) { "down" }, presses())
        // Every press is let go before the next, so the game sees each one.
        for ((i, e) in events.withIndex()) assertEquals(e, if (i % 2 == 0) "down down" else "down up", e.substringBeforeLast(' '))
        up()
        val count = events.size
        runTo(now + 2_000)
        assertEquals(count + 1, events.size)
        assertEquals("down up", events.last().substringBeforeLast(' '))
    }

    @Test fun liftingBeforeTheFirstRepeatStopsIt() {
        down(); move(-6f, 0f, 48); hold(200); up()
        runTo(now + 2_000)
        assertEquals(listOf("left"), presses())
        assertEquals(2, events.size)
    }

    @Test fun flicksChainWithoutLifting() {
        down()
        move(0f, -6f, 48); hold(120)
        move(-6f, 0f, 48); hold(120)
        move(0f, -6f, 48); hold(120)
        move(0f, -6f, 48); hold(120)
        up()
        runTo(now + 1_000)
        assertEquals(listOf("up", "left", "up", "up"), presses())
    }

    @Test fun aLongFastFlickPressesOnce() {
        down(); move(0f, 25f, 120); up()
        runTo(now + 1_000)
        assertEquals(listOf("down"), presses())
    }

    @Test fun aFlickRightAfterTheLastInTheSameDirectionWaitsForTheGameToSeeTheRelease() {
        down(); move(6f, 0f, 40); up()
        val pressedAt = now
        runTo(now + 10)
        down(); move(6f, 0f, 40); up()
        runTo(now + 1_000)
        assertEquals(listOf("right", "right"), presses())
        val times = events.map { it.substringAfterLast(' ').toLong() }
        assertTrue(events.toString(), times[2] - times[1] >= SwipePadDetector.GAP_MS)
        assertTrue(events.toString(), times[2] - pressedAt < 200)
    }

    @Test fun aCancelLetsGoAtOnceAndStopsTheRepeats() {
        down(); move(0f, -6f, 48)
        assertEquals(1, events.size)
        pad.cancel(now)
        assertEquals("up up $now", events.last())
        runTo(now + 2_000)
        assertEquals(2, events.size)
    }

    @Test fun aTapPressesNothing() {
        down(); hold(90); up()
        runTo(now + 1_000)
        assertTrue(events.isEmpty())
    }
}
