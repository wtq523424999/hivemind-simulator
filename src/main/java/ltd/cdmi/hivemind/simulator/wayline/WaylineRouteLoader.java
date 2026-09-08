// Copyright (C) 2026 CDMI
package ltd.cdmi.hivemind.simulator.wayline;

import ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.Segment;
import ltd.cdmi.hivemind.simulator.wayline.WaylinePlan.Waypoint;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads and parses DJI KMZ/WPML route files. */
public class WaylineRouteLoader {

    static final int MAX_KMZ_BYTES = 20 * 1024 * 1024;
    static final int MAX_WPML_BYTES = 5 * 1024 * 1024;

    private final HttpClient httpClient;

    public WaylineRouteLoader() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    WaylineRouteLoader(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public WaylinePlan load(String source, double homeLatitude, double homeLongitude,
                            double homeElevation) throws IOException, InterruptedException {
        if (source == null || source.isBlank()) {
            throw new IOException("Wayline URL is empty");
        }
        byte[] kmz = readSource(source.trim());
        return parse(kmz, source, homeLatitude, homeLongitude, homeElevation);
    }

    public WaylinePlan parse(byte[] kmz, String source, double homeLatitude,
                             double homeLongitude, double homeElevation) throws IOException {
        byte[] wpml = extractWpml(kmz);
        ParsedWpml parsed = parseWpml(wpml);
        if (parsed.waypoints.isEmpty()) {
            throw new IOException("Wayline contains no waypoints");
        }

        List<Waypoint> waypoints = parsed.waypoints.stream()
                .map(point -> Double.isNaN(point.relativeHeight())
                        ? new Waypoint(point.latitude(), point.longitude(),
                                point.elevation() - homeElevation, point.elevation(),
                                point.speed(), point.index())
                        : new Waypoint(point.latitude(), point.longitude(),
                                point.relativeHeight(), homeElevation + point.relativeHeight(),
                                point.speed(), point.index()))
                .sorted(Comparator.comparingInt(Waypoint::index))
                .toList();
        Waypoint home = new Waypoint(homeLatitude, homeLongitude, 0.0,
                homeElevation, Math.max(parsed.transitionalSpeed, 0.1), -1);
        List<Waypoint> routePoints = new ArrayList<>();
        routePoints.add(home);
        routePoints.addAll(waypoints);
        if ("gohome".equalsIgnoreCase(parsed.finishAction)) {
            routePoints.add(home);
        }

        List<Segment> segments = new ArrayList<>();
        double totalTime = 0.0;
        double totalDistance = 0.0;
        for (int i = 0; i + 1 < routePoints.size(); i++) {
            Waypoint start = routePoints.get(i);
            Waypoint end = routePoints.get(i + 1);
            double horizontal = haversine(start.latitude(), start.longitude(),
                    end.latitude(), end.longitude());
            double distance = Math.hypot(horizontal, end.elevation() - start.elevation());
            double speed = start.index() < 0 || end.index() < 0
                    ? Math.max(parsed.transitionalSpeed, 0.1)
                    : Math.max(end.speed(), 0.1);
            double duration = Math.max(distance / speed, 0.001);
            segments.add(new Segment(start, end, speed, horizontal, distance, duration,
                    totalTime, totalDistance));
            totalTime += duration;
            totalDistance += distance;
        }
        return new WaylinePlan(source, waypoints, segments, totalDistance, totalTime,
                parsed.transitionalSpeed, parsed.finishAction, parsed.takeoffSecurityHeight);
    }

    private byte[] readSource(String source) throws IOException, InterruptedException {
        if (source.matches("^[A-Za-z]:[\\\\/].*") || source.startsWith("\\\\")) {
            return readLimited(Files.newInputStream(Path.of(source)), MAX_KMZ_BYTES);
        }
        URI uri;
        try {
            uri = URI.create(source);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid wayline URL", e);
        }
        if (uri.getScheme() == null || uri.getScheme().isBlank()) {
            return readLimited(Files.newInputStream(Path.of(source)), MAX_KMZ_BYTES);
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return readLimited(Files.newInputStream(Path.of(uri)), MAX_KMZ_BYTES);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Unsupported wayline URL scheme: " + uri.getScheme());
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "hivemind-simulator/1.0")
                .GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("Wayline download failed with HTTP " + response.statusCode());
        }
        try (InputStream body = response.body()) {
            return readLimited(body, MAX_KMZ_BYTES);
        }
    }

    private byte[] extractWpml(byte[] kmz) throws IOException {
        if (kmz == null || kmz.length == 0 || kmz.length > MAX_KMZ_BYTES) {
            throw new IOException("Invalid KMZ size");
        }
        byte[] kmlFallback = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(kmz))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (name.endsWith("waylines.wpml")) {
                    return readEntryLimited(zip, MAX_WPML_BYTES);
                }
                if (kmlFallback == null && name.endsWith(".kml")) {
                    kmlFallback = readEntryLimited(zip, MAX_WPML_BYTES);
                }
            }
        }
        if (kmlFallback != null) {
            return kmlFallback;
        }
        throw new IOException("KMZ does not contain waylines.wpml or KML data");
    }

    private ParsedWpml parseWpml(byte[] xml) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);

        double transitionalSpeed = 6.0;
        double autoFlightSpeed = 12.0;
        double securityHeight = 0.0;
        String finishAction = "noAction";
        String globalHeightMode = "relativeToStartPoint";
        List<Waypoint> waypoints = new ArrayList<>();
        PlacemarkBuilder placemark = null;
        String currentElement = null;
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    currentElement = reader.getLocalName();
                    if ("Placemark".equals(currentElement)) {
                        placemark = new PlacemarkBuilder(waypoints.size());
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                        && !reader.isWhiteSpace() && currentElement != null) {
                    String value = reader.getText().trim();
                    if (value.isEmpty()) {
                        continue;
                    }
                    if (placemark != null) {
                        placemark.accept(currentElement, value);
                    } else {
                        switch (currentElement) {
                            case "globalTransitionalSpeed" -> transitionalSpeed = number(value, transitionalSpeed);
                            case "autoFlightSpeed" -> autoFlightSpeed = number(value, autoFlightSpeed);
                            case "takeOffSecurityHeight" -> securityHeight = number(value, securityHeight);
                            case "finishAction" -> finishAction = value;
                            case "executeHeightMode" -> globalHeightMode = value;
                            default -> { }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("Placemark".equals(name) && placemark != null) {
                        Waypoint waypoint = placemark.build(autoFlightSpeed, globalHeightMode);
                        if (waypoint != null) {
                            waypoints.add(waypoint);
                        }
                        placemark = null;
                    }
                    currentElement = null;
                }
            }
        } catch (XMLStreamException | IllegalArgumentException e) {
            throw new IOException("Invalid WPML data: " + e.getMessage(), e);
        }
        return new ParsedWpml(transitionalSpeed, finishAction, securityHeight, waypoints);
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (input) {
            return readEntryLimited(input, limit);
        }
    }

    private static byte[] readEntryLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) {
                throw new IOException("Wayline file exceeds " + limit + " bytes");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double radius = 6_371_000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * radius * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double number(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record ParsedWpml(double transitionalSpeed, String finishAction,
                              double takeoffSecurityHeight, List<Waypoint> waypoints) {}

    private static final class PlacemarkBuilder {
        private final int fallbackIndex;
        private String coordinates;
        private String height;
        private String speed;
        private String index;
        private String heightMode;

        private PlacemarkBuilder(int fallbackIndex) {
            this.fallbackIndex = fallbackIndex;
        }

        private void accept(String element, String value) {
            switch (element) {
                case "coordinates" -> coordinates = value;
                case "executeHeight", "height" -> height = value;
                case "waypointSpeed" -> speed = value;
                case "index" -> index = value;
                case "executeHeightMode" -> heightMode = value;
                default -> { }
            }
        }

        private Waypoint build(double defaultSpeed, String globalHeightMode) {
            if (coordinates == null) {
                return null;
            }
            String[] parts = coordinates.trim().split(",");
            if (parts.length < 2) {
                return null;
            }
            double longitude = Double.parseDouble(parts[0]);
            double latitude = Double.parseDouble(parts[1]);
            double parsedHeight = number(height, 0.0);
            double parsedSpeed = Math.max(number(speed, defaultSpeed), 0.1);
            int parsedIndex = index == null ? fallbackIndex : Integer.parseInt(index);
            String mode = heightMode == null ? globalHeightMode : heightMode;
            boolean relative = "relativetostartpoint".equalsIgnoreCase(mode);
            // Relative points are resolved against home elevation after parsing.
            return new Waypoint(latitude, longitude, relative ? parsedHeight : Double.NaN,
                    parsedHeight, parsedSpeed, parsedIndex);
        }
    }
}
