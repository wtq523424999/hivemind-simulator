// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package ltd.cdmi.hivemind.simulator.media;

/**
 * Telemetry captured at the instant a waypoint photo action fires.
 * <p>These values are written verbatim into {@code file_upload_callback.data.file.metadata},
 * mirroring how a real dock reports the shooting position, altitude and gimbal yaw of the
 * frame it captured rather than the aircraft's landing position.
 *
 * @param latitude          aircraft latitude at capture (degrees)
 * @param longitude         aircraft longitude at capture (degrees)
 * @param absoluteAltitude  ellipsoid (WGS84) altitude at capture, metres
 * @param relativeAltitude  altitude above takeoff point at capture, metres
 * @param gimbalYawDegree   gimbal yaw at capture, degrees
 * @param captureTimeIso    capture time, ISO 8601 (e.g. {@code 2021-05-10T16:04:20})
 * @param fileSuffix        WPML {@code fileSuffix} of the action, empty when absent
 * @param payloadPositionIndex WPML {@code payloadPositionIndex}, e.g. {@code 0}
 */
public record CaptureSnapshot(
        double latitude,
        double longitude,
        double absoluteAltitude,
        double relativeAltitude,
        double gimbalYawDegree,
        String captureTimeIso,
        String fileSuffix,
        String payloadPositionIndex
) {}
