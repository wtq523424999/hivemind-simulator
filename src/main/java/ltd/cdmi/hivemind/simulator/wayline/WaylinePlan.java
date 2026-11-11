// Copyright (C) 2026 CDMI
package ltd.cdmi.hivemind.simulator.wayline;

import java.util.List;

/** Immutable, time-parameterized representation of a WPML wayline. */
public record WaylinePlan(
        String source,
        List<Waypoint> waypoints,
        List<Segment> segments,
        double totalDistance,
        double totalDuration,
        double transitionalSpeed,
        String finishAction,
        double takeoffSecurityHeight,
        List<WaypointAction> actions
) {
    public WaylinePlan {
        waypoints = List.copyOf(waypoints);
        segments = List.copyOf(segments);
        actions = List.copyOf(actions);
    }

    /** Convenience constructor for waylines without actions. */
    public WaylinePlan(String source, List<Waypoint> waypoints, List<Segment> segments,
                       double totalDistance, double totalDuration, double transitionalSpeed,
                       String finishAction, double takeoffSecurityHeight) {
        this(source, waypoints, segments, totalDistance, totalDuration, transitionalSpeed,
                finishAction, takeoffSecurityHeight, List.of());
    }

    public record Waypoint(
            double latitude,
            double longitude,
            double relativeHeight,
            double elevation,
            double speed,
            int index
    ) {}

    public record Segment(
            Waypoint start,
            Waypoint end,
            double speed,
            double horizontalDistance,
            double distance,
            double duration,
            double startTime,
            double startDistance
    ) {}

    /**
     * A WPML waypoint action resolved against the route timeline.
     * <p>Only {@code reachPoint} actions are parsed: the action fires once when the aircraft
     * reaches the waypoint whose index equals {@code waypointIndex}.
     *
     * @param waypointIndex      WPML waypoint index the action is attached to
     * @param actionId           action id inside its action group
     * @param actionActuatorFunc WPML actuator name (e.g. {@code takePhoto})
     * @param fileSuffix         takePhoto file suffix, empty when absent
     * @param payloadPositionIndex takePhoto payload position index, 0 when absent
     * @param triggerTime        route time (seconds) at which the waypoint is reached
     */
    public record WaypointAction(
            int waypointIndex,
            int actionId,
            String actionActuatorFunc,
            String fileSuffix,
            String payloadPositionIndex,
            double triggerTime
    ) {}

    /** Actions attached to a waypoint index, ordered by action id. */
    public List<WaypointAction> actionsAt(int waypointIndex) {
        return actions.stream()
                .filter(action -> action.waypointIndex() == waypointIndex)
                .sorted((a, b) -> Integer.compare(a.actionId(), b.actionId()))
                .toList();
    }

    /** Whether the wayline contains at least one takePhoto action. */
    public boolean hasTakePhotoActions() {
        return actions.stream()
                .anyMatch(action -> "takePhoto".equalsIgnoreCase(action.actionActuatorFunc()));
    }

    /** Number of takePhoto actions in the wayline (one photo per action). */
    public int takePhotoCount() {
        return (int) actions.stream()
                .filter(action -> "takePhoto".equalsIgnoreCase(action.actionActuatorFunc()))
                .count();
    }
}
