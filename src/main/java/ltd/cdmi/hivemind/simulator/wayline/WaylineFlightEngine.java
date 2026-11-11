// Copyright (C) 2026 CDMI
package ltd.cdmi.hivemind.simulator.wayline;

import ltd.cdmi.hivemind.simulator.device.DeviceState;
import ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.Segment;
import ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.WaypointAction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/** Advances a parsed wayline against monotonic time and writes telemetry to DeviceState. */
public class WaylineFlightEngine {
    public enum Status { IDLE, PREPARED, RUNNING, PAUSED, COMPLETED, STOPPED }

    private final DeviceState state;
    private final double homeLatitude;
    private final double homeLongitude;
    private final double homeElevation;
    private final LongSupplier nanoClock;
    private WaylinePlan plan;
    private Status status = Status.IDLE;
    private long startedAtNanos;
    private long pausedAtNanos;
    private long pausedNanos;
    private double elapsedSeconds;
    private double lastElapsedSeconds;
    private double batteryPercent;
    private int waypointIndex = -1;
    /** Actions reached but not yet drained by the caller, in route order. */
    private final Deque<WaypointAction> pendingActions = new ArrayDeque<>();
    /** Cursor into {@code plan.actions()} (sorted by trigger time); actions before it fired. */
    private int nextActionIndex;

    public WaylineFlightEngine(DeviceState state, double homeLatitude,
                               double homeLongitude, double homeElevation) {
        this(state, homeLatitude, homeLongitude, homeElevation, System::nanoTime);
    }

    WaylineFlightEngine(DeviceState state, double homeLatitude,
                        double homeLongitude, double homeElevation, LongSupplier nanoClock) {
        this.state = state;
        this.homeLatitude = homeLatitude;
        this.homeLongitude = homeLongitude;
        this.homeElevation = homeElevation;
        this.nanoClock = nanoClock;
    }

    public synchronized void prepare(WaylinePlan plan) {
        this.plan = plan;
        status = Status.PREPARED;
        elapsedSeconds = 0.0;
        lastElapsedSeconds = 0.0;
        waypointIndex = -1;
        pendingActions.clear();
        nextActionIndex = 0;
    }

    public synchronized boolean hasPlan() {
        return plan != null && !plan.segments().isEmpty();
    }

    public synchronized void start(String trackId) {
        if (!hasPlan()) throw new IllegalStateException("No prepared wayline");
        status = Status.RUNNING;
        startedAtNanos = nanoClock.getAsLong();
        pausedAtNanos = 0L;
        pausedNanos = 0L;
        elapsedSeconds = 0.0;
        lastElapsedSeconds = 0.0;
        batteryPercent = state.getBatteryPercent();
        waypointIndex = -1;
        pendingActions.clear();
        nextActionIndex = 0;
        state.setCurrentTrackId(trackId == null ? "" : trackId);
        state.setRouteTelemetryActive(true);
        apply(0.0);
    }

    /**
     * Return and clear the actions whose waypoint has been reached since the last drain.
     * <p>The scheduler drains after every {@link #tick()}; a caller that renders the returned
     * actions is responsible for executing them (e.g. taking a photo).
     */
    public synchronized List<WaypointAction> drainReachedActions() {
        if (pendingActions.isEmpty()) {
            return List.of();
        }
        List<WaypointAction> actions = new ArrayList<>(pendingActions);
        pendingActions.clear();
        return actions;
    }

    /** Actions reached but not yet drained (test/observability helper). */
    public synchronized int pendingActionCount() {
        return pendingActions.size();
    }

    public synchronized void pause() {
        if (status == Status.RUNNING) {
            tick();
            status = Status.PAUSED;
            pausedAtNanos = nanoClock.getAsLong();
            state.setHorizontalSpeed(0.0);
            state.setVerticalSpeed(0.0);
        }
    }

    public synchronized void resume() {
        if (status == Status.PAUSED) {
            pausedNanos += nanoClock.getAsLong() - pausedAtNanos;
            pausedAtNanos = 0L;
            status = Status.RUNNING;
        }
    }

    public synchronized void stop() {
        if (status != Status.IDLE) status = Status.STOPPED;
        state.setHorizontalSpeed(0.0);
        state.setVerticalSpeed(0.0);
    }

    public synchronized void clear() {
        stop();
        plan = null;
        status = Status.IDLE;
        state.setRouteTelemetryActive(false);
        elapsedSeconds = 0.0;
        lastElapsedSeconds = 0.0;
        waypointIndex = -1;
        pendingActions.clear();
        nextActionIndex = 0;
    }

    /** Returns true when the route reached its final point during this tick. */
    public synchronized boolean tick() {
        if (status != Status.RUNNING || plan == null) return false;
        double elapsed = Math.max(0.0,
                (nanoClock.getAsLong() - startedAtNanos - pausedNanos) / 1_000_000_000.0);
        if (elapsed >= plan.totalDuration()) {
            elapsedSeconds = plan.totalDuration();
            apply(elapsedSeconds);
            status = Status.COMPLETED;
            state.setHorizontalSpeed(0.0);
            state.setVerticalSpeed(0.0);
            return true;
        }
        elapsedSeconds = elapsed;
        apply(elapsed);
        return false;
    }

    private void apply(double elapsed) {
        Segment segment = plan.segments().get(plan.segments().size() - 1);
        for (Segment candidate : plan.segments()) {
            if (elapsed < candidate.startTime() + candidate.duration()) {
                segment = candidate;
                break;
            }
        }
        double segmentElapsed = Math.max(0.0, elapsed - segment.startTime());
        double ratio = Math.min(1.0, segmentElapsed / segment.duration());
        double latitude = lerp(segment.start().latitude(), segment.end().latitude(), ratio);
        double longitude = lerp(segment.start().longitude(), segment.end().longitude(), ratio);
        double elevation = lerp(segment.start().elevation(), segment.end().elevation(), ratio);

        state.setDroneLatitude(latitude);
        state.setDroneLongitude(longitude);
        state.setDroneElevation(elevation);
        state.setDroneHeight(elevation - homeElevation);
        state.setAttitudeYaw(bearing(segment.start().latitude(), segment.start().longitude(),
                segment.end().latitude(), segment.end().longitude()));
        state.setHorizontalSpeed(segment.horizontalDistance() / segment.duration());
        state.setVerticalSpeed((segment.end().elevation() - segment.start().elevation()) / segment.duration());
        state.setHomeDistance(WaylineRouteLoader.haversine(homeLatitude, homeLongitude, latitude, longitude));
        state.setTotalFlightDistance(segment.startDistance() + segment.distance() * ratio);
        state.setRemainingFlightTimeSeconds(Math.max(0L, Math.round(plan.totalDuration() - elapsed)));
        state.setFlightTimeSeconds(Math.max(0L, Math.round(elapsed)));
        waypointIndex = ratio <= 0.000001 ? segment.start().index() : segment.end().index();
        collectReachedActions(elapsed);

        double delta = Math.max(0.0, elapsed - lastElapsedSeconds);
        if (delta > 0.0) {
            batteryPercent = Math.max(5.0, batteryPercent - delta * 0.015);
            state.setBatteryPercent((int) Math.round(batteryPercent));
        }
        lastElapsedSeconds = elapsed;
    }

    /**
     * Queue actions whose trigger time has been reached.
     * <p>Trigger time equals the route time at which the aircraft arrives at the waypoint,
     * so a flight that overshoots (e.g. one 0.5 s tick spanning several waypoints) still
     * fires every intermediate action exactly once, in route order.
     */
    private void collectReachedActions(double elapsed) {
        List<WaypointAction> actions = plan.actions();
        while (nextActionIndex < actions.size()
                && actions.get(nextActionIndex).triggerTime() <= elapsed) {
            pendingActions.addLast(actions.get(nextActionIndex));
            nextActionIndex++;
        }
    }

    public synchronized Status status() { return status; }
    public synchronized double progress() {
        return plan == null || plan.totalDuration() <= 0.0
                ? 0.0 : Math.min(1.0, elapsedSeconds / plan.totalDuration());
    }
    public synchronized int waypointIndex() { return Math.max(0, waypointIndex); }
    public synchronized WaylinePlan plan() { return plan; }

    private static double lerp(double start, double end, double ratio) {
        return start + (end - start) * ratio;
    }

    private static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }
}
