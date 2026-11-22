package com.github.wednesday.common;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Snapshot of a {@link Timeline}: per-turn offsets and p50/p95 per stage. */
public record LatencyReport(List<Turn> turns) {

    /** Offsets in ms from the turn's earliest mark. */
    public record Turn(int number, Map<Stage, Long> offsetsMillis) {
        public Turn {
            offsetsMillis = Map.copyOf(offsetsMillis);
        }
    }

    public LatencyReport {
        turns = List.copyOf(turns);
    }

    /** Nearest-rank percentile (0-100) of a stage's offset across the turns that marked it. */
    public Optional<Long> percentile(Stage stage, int percentile) {
        List<Long> values = new ArrayList<>();
        for (Turn t : turns) {
            Long v = t.offsetsMillis().get(stage);
            if (v != null) {
                values.add(v);
            }
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        values.sort(null);
        int rank = (int) Math.ceil(percentile / 100.0 * values.size());
        return Optional.of(values.get(Math.max(rank, 1) - 1));
    }

    /** Text table: one row per turn plus p50 and p95 rows; one column per stage seen. */
    public String render() {
        Set<Stage> seen = EnumSet.noneOf(Stage.class);
        turns.forEach(t -> seen.addAll(t.offsetsMillis().keySet()));

        StringBuilder sb = new StringBuilder("Latency report (ms from first event of each turn)\n");
        sb.append(String.format("%-6s", "turn"));
        for (Stage s : seen) {
            sb.append(String.format(" %" + width(s) + "s", s.name()));
        }
        sb.append('\n');
        for (Turn t : turns) {
            sb.append(String.format("%-6d", t.number()));
            for (Stage s : seen) {
                Long v = t.offsetsMillis().get(s);
                sb.append(String.format(" %" + width(s) + "s", v == null ? "-" : v.toString()));
            }
            sb.append('\n');
        }
        for (int p : new int[] {50, 95}) {
            sb.append(String.format("%-6s", "p" + p));
            for (Stage s : seen) {
                sb.append(String.format(" %" + width(s) + "s",
                        percentile(s, p).map(String::valueOf).orElse("-")));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static int width(Stage s) {
        return Math.max(s.name().length(), 6);
    }
}
