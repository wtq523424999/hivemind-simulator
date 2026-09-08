// Copyright (C) 2026 CDMI
package ltd.cdmi.hivemind.simulator.wayline;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaylineRouteLoaderTest {

    @Test
    void parsesWpmlIntoTimeParameterizedSegments() throws Exception {
        String wpml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:wpml="http://www.dji.com/wpmz/1.0.6">
                  <Document>
                    <wpml:missionConfig>
                      <wpml:finishAction>goHome</wpml:finishAction>
                      <wpml:globalTransitionalSpeed>5</wpml:globalTransitionalSpeed>
                      <wpml:takeOffSecurityHeight>25</wpml:takeOffSecurityHeight>
                    </wpml:missionConfig>
                    <Folder>
                      <wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>
                      <wpml:autoFlightSpeed>10</wpml:autoFlightSpeed>
                      <Placemark><Point><coordinates>104.001,30.0</coordinates></Point>
                        <wpml:index>0</wpml:index><wpml:executeHeight>30</wpml:executeHeight>
                        <wpml:waypointSpeed>8</wpml:waypointSpeed></Placemark>
                      <Placemark><Point><coordinates>104.002,30.0</coordinates></Point>
                        <wpml:index>1</wpml:index><wpml:executeHeight>40</wpml:executeHeight>
                        <wpml:waypointSpeed>10</wpml:waypointSpeed></Placemark>
                    </Folder>
                  </Document>
                </kml>
                """;

        WaylinePlan plan = new WaylineRouteLoader().parse(kmz(wpml), "memory.kmz",
                30.0, 104.0, 500.0);

        assertEquals(2, plan.waypoints().size());
        assertEquals(3, plan.segments().size(), "home -> points -> home");
        assertEquals(530.0, plan.waypoints().get(0).elevation(), 1e-9);
        assertEquals(40.0, plan.waypoints().get(1).relativeHeight(), 1e-9);
        assertEquals(25.0, plan.takeoffSecurityHeight(), 1e-9);
        assertTrue(plan.totalDistance() > 300.0);
        assertTrue(plan.totalDuration() > 0.0);
    }

    @Test
    void rejectsArchivesWithoutRouteData() throws Exception {
        assertThrows(Exception.class, () -> new WaylineRouteLoader().parse(
                zipEntry("readme.txt", "not a route"), "bad.kmz", 0, 0, 0));
    }

    @Test
    void acceptsWindowsAbsolutePathSyntax() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return;
        Path temp = Files.createTempFile("wayline-", ".kmz");
        try {
            String wpml = "<kml><Document><Placemark><Point><coordinates>104,30</coordinates>"
                    + "</Point><index>0</index><executeHeight>20</executeHeight></Placemark></Document></kml>";
            Files.write(temp, kmz(wpml));
            WaylinePlan plan = new WaylineRouteLoader().load(temp.toString(), 30, 104, 500);
            assertEquals(1, plan.waypoints().size());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static byte[] kmz(String wpml) throws Exception {
        return zipEntry("wpmz/waylines.wpml", wpml);
    }

    private static byte[] zipEntry(String name, String content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
