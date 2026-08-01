package dev.d4nilpzz.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MavenUtilsTest {

    @Test
    void comparesNumericSegmentsNumerically() {
        // The old string comparison put 1.9 above 1.10, which made badges and <latest>
        // report the wrong version once a project reached its tenth patch.
        assertTrue(MavenUtils.compareVersions("1.10.0", "1.9.0") > 0);
        assertTrue(MavenUtils.compareVersions("2.0.0", "10.0.0") < 0);
        assertEquals(0, MavenUtils.compareVersions("1.2.3", "1.2.3"));
    }

    @Test
    void missingSegmentsCountAsZero() {
        assertEquals(0, MavenUtils.compareVersions("1.0", "1.0.0"));
        assertTrue(MavenUtils.compareVersions("1.0.1", "1.0") > 0);
    }

    @Test
    void qualifiersSortBeforeTheReleaseTheyQualify() {
        assertTrue(MavenUtils.compareVersions("1.0-alpha", "1.0") < 0);
        assertTrue(MavenUtils.compareVersions("1.0-beta", "1.0-rc1") < 0);
        assertTrue(MavenUtils.compareVersions("1.0-rc1", "1.0") < 0);
        assertTrue(MavenUtils.compareVersions("1.0-SNAPSHOT", "1.0") < 0);
        assertTrue(MavenUtils.compareVersions("1.0", "1.0-sp1") < 0);
    }

    @Test
    void snapshotOfNextVersionOutranksCurrentRelease() {
        assertTrue(MavenUtils.compareVersions("1.1-SNAPSHOT", "1.0") > 0);
    }

    @Test
    void sortsARealisticVersionList() {
        List<String> versions = new ArrayList<>(List.of(
                "1.10.0", "1.2.0", "1.0.0", "2.0.0-rc1", "1.9.0", "2.0.0", "1.0.0-SNAPSHOT"));
        versions.sort(MavenUtils.versionComparator());

        assertEquals(List.of(
                "1.0.0-SNAPSHOT", "1.0.0", "1.2.0", "1.9.0", "1.10.0", "2.0.0-rc1", "2.0.0"), versions);
    }

    @Test
    void separatesDigitAndLetterRuns() {
        assertTrue(MavenUtils.compareVersions("1.0-rc2", "1.0-rc10") < 0);
    }

    @Test
    void generatedPomCarriesTheCoordinatesAndAWarning() {
        String pom = MavenUtils.generatePom("com.example", "demo", "1.0.0");
        assertTrue(pom.contains("<groupId>com.example</groupId>"));
        assertTrue(pom.contains("<artifactId>demo</artifactId>"));
        assertTrue(pom.contains("<version>1.0.0</version>"));
        assertTrue(pom.contains("no dependencies"));
    }
}
