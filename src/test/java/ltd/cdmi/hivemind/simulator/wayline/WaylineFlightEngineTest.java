// Copyright (C) 2026 CDMI
package ltd.cdmi.hivemind.simulator.wayline;

import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.Segment;
import ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.Waypoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaylineFlightEngineTest {

    @Test
    void updatesTelemetryAndFreezesElapsedTimeWhilePaused() {
        DeviceState state = new DeviceState();
        state.setBatteryPercent(100);
        AtomicLong clock = new AtomicLong();
        Waypoint start = new Waypoint(30.0, 104.0, 0, 500, 10, -1);
        Waypoint end = new Waypoint(30.001, 104.0, 30, 530, 10, 0);
        double horizontal = WaylineRouteLoader.haversine(30.0, 104.0, 30.001, 104.0);
        double distance = Math.hypot(horizontal, 30);
        WaylinePlan plan = new WaylinePlan("test", List.of(end),
                List.of(new Segment(start, end, 10, horizontal, distance, 10, 0, 0)),
                distance, 10, 10, "noAction", 20);
        WaylineFlightEngine engine = new WaylineFlightEngine(state, 30.0, 104.0, 500, clock::get);

        engine.prepare(plan);
        engine.start("track-1");
        clock.set(5_000_000_000L);
        assertFalse(engine.tick());
        assertEquals(30.0005, state.getDroneLatitude(), 1e-9);
        assertEquals(15.0, state.getDroneHeight(), 1e-9);
        assertEquals(3.0, state.getVerticalSpeed(), 1e-9);
        assertTrue(state.getHomeDistance() > 50);
        assertEquals("track-1", state.getCurrentTrackId());

        engine.pause();
        double pausedLatitude = state.getDroneLatitude();
        clock.set(8_000_000_000L);
        assertFalse(engine.tick());
        assertEquals(pausedLatitude, state.getDroneLatitude(), 1e-12);
        assertEquals(0.0, state.getHorizontalSpeed(), 1e-12);

        engine.resume();
        clock.set(13_000_000_000L);
        assertTrue(engine.tick());
        assertEquals(30.001, state.getDroneLatitude(), 1e-9);
        assertEquals(WaylineFlightEngine.Status.COMPLETED, engine.status());
    }
}
