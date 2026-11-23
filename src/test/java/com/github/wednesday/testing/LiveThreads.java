package com.github.wednesday.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Names of every live thread, virtual ones included ({@code Thread.getAllStackTraces()} skips
 * virtual threads, the JSON thread dump does not).
 */
public final class LiveThreads {

    private LiveThreads() {
    }

    public static List<String> names() {
        try {
            Path dir = Files.createTempDirectory("threads");
            Path file = dir.resolve("dump.json");
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
            JsonNode root = new ObjectMapper().readTree(file.toFile());
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
            List<String> names = new ArrayList<>();
            for (JsonNode container : root.path("threadDump").path("threadContainers")) {
                for (JsonNode t : container.path("threads")) {
                    names.add(t.path("name").asText());
                }
            }
            return names;
        } catch (Exception e) {
            throw new IllegalStateException("cannot dump threads", e);
        }
    }

    /** Waits up to {@code millis} for no live thread to match; returns the matching names left. */
    public static List<String> awaitNone(Predicate<String> match, long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        List<String> left;
        do {
            left = names().stream().filter(match).toList();
            if (left.isEmpty()) {
                return left;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        return left;
    }
}
