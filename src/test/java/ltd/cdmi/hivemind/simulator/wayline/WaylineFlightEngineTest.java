// Copyright (C) 2026 CDMI
package ltd.cdmi.hivemind.simulator.wayline;

import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.Segment;
import ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.Waypoint;
import ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.WaypointAction;
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

    @Test
    void firesWaypointActionOnceWhenWaypointIsReached() {
        DeviceState state = new DeviceState();
        state.setBatteryPercent(100);
        AtomicLong clock = new AtomicLong();
        Waypoint home = new Waypoint(30.0, 104.0, 0, 500, 10, -1);
        Waypoint first = new Waypoint(30.001, 104.0, 30, 530, 10, 0);
        Waypoint second = new Waypoint(30.002, 104.0, 30, 530, 10, 1);
        double leg = WaylineRouteLoader.haversine(30.0, 104.0, 30.001, 104.0);
        double distance = Math.hypot(leg, 30);
        double duration = distance / 10;
        WaylinePlan plan = new WaylinePlan("test", List.of(first, second),
                List.of(new Segment(home, first, 10, leg, distance, duration, 0, 0),
                        new Segment(first, second, 10, leg, 0, duration, duration, distance)),
                distance * 2, duration * 2, 10, "noAction", 20,
                List.of(new WaypointAction(0, 1, "takePhoto", "p1", "0", duration),
                        new WaypointAction(1, 2, "takePhoto", "p2", "0", duration * 2)));
        WaylineFlightEngine engine = new WaylineFlightEngine(state, 30.0, 104.0, 500, clock::get);

        engine.prepare(plan);
        engine.start("track-1");
        assertTrue(engine.drainReachedActions().isEmpty(), "起点处不应触发动作");

        // 到达第一个航点：只触发第一个动作（+1ms 容忍纳秒时钟截断）
        clock.set((long) ((duration + 0.001) * 1_000_000_000L));
        engine.tick();
        List<WaypointAction> firstBatch = engine.drainReachedActions();
        assertEquals(1, firstBatch.size());
        assertEquals("p1", firstBatch.get(0).fileSuffix());

        // 同一时刻重复 tick 不应重复触发（去重）
        engine.tick();
        assertTrue(engine.drainReachedActions().isEmpty());

        // 走完航线：第二个动作触发一次
        clock.set((long) ((duration * 2 + 1) * 1_000_000_000L));
        engine.tick();
        List<WaypointAction> secondBatch = engine.drainReachedActions();
        assertEquals(1, secondBatch.size());
        assertEquals("p2", secondBatch.get(0).fileSuffix());
        assertEquals(WaylineFlightEngine.Status.COMPLETED, engine.status());
    }

    @Test
    void pausedTimeIsExcludedFromActionTriggering() {
        DeviceState state = new DeviceState();
        state.setBatteryPercent(100);
        AtomicLong clock = new AtomicLong();
        Waypoint home = new Waypoint(30.0, 104.0, 0, 500, 10, -1);
        Waypoint point = new Waypoint(30.001, 104.0, 30, 530, 10, 0);
        double leg = WaylineRouteLoader.haversine(30.0, 104.0, 30.001, 104.0);
        double distance = Math.hypot(leg, 30);
        double duration = distance / 10;
        WaylinePlan plan = new WaylinePlan("test", List.of(point),
                List.of(new Segment(home, point, 10, leg, distance, duration, 0, 0)),
                distance, duration, 10, "noAction", 20,
                List.of(new WaypointAction(0, 0, "takePhoto", "", "0", duration)));
        WaylineFlightEngine engine = new WaylineFlightEngine(state, 30.0, 104.0, 500, clock::get);

        engine.prepare(plan);
        engine.start("track-1");
        clock.set((long) (duration / 2 * 1_000_000_000L));
        engine.tick();
        engine.pause();
        // 暂停 10 秒（不应推进航线时间，因此也不触发动作）
        clock.set((long) ((duration / 2 + 10) * 1_000_000_000L));
        engine.tick();
        assertTrue(engine.drainReachedActions().isEmpty(), "暂停期间不应触发航点动作");
        engine.resume();
        // 恢复后剩余 duration/2 到达航点
        clock.set((long) ((duration / 2 + 10 + duration / 2 + 0.01) * 1_000_000_000L));
        engine.tick();
        assertEquals(1, engine.drainReachedActions().size(), "恢复后到达航点应触发动作");
    }
}
