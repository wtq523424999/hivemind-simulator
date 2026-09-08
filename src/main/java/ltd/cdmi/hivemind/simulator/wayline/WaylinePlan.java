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
        double takeoffSecurityHeight
) {
    public WaylinePlan {
        waypoints = List.copyOf(waypoints);
        segments = List.copyOf(segments);
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
}
