package com.github.wednesday.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Records when each {@link Stage} happens in each turn of a session. Thread-safe; stages can be
 * marked from any thread. Only the first mark of a stage in a turn counts (e.g. first token).
 */
public final class Timeline {

    private final LongSupplier nanoClock;
    private final List<Map<Stage, Long>> turns = new ArrayList<>();

    /** @param nanoClock monotonic nanosecond source (e.g. {@code System::nanoTime}) */
    public Timeline(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public static Timeline system() {
        return new Timeline(System::nanoTime);
    }

    /** Starts a new turn; later marks go to it. Returns the 1-based turn number. */
    public synchronized int beginTurn() {
        turns.add(new EnumMap<>(Stage.class));
        return turns.size();
    }

    /** Marks a stage on the current turn. Ignored if no turn was begun or the stage is already marked. */
    public synchronized void mark(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        if (!turns.isEmpty()) {
            turns.getLast().putIfAbsent(stage, nanoClock.getAsLong());
        }
    }

    /** Per-turn stage offsets in milliseconds from the earliest mark of that turn. */
    public synchronized LatencyReport report() {
        List<LatencyReport.Turn> result = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            Map<Stage, Long> marks = turns.get(i);
            Map<Stage, Long> offsets = new EnumMap<>(Stage.class);
            if (!marks.isEmpty()) {
                long origin = Collections.min(marks.values());
                marks.forEach((stage, nanos) -> offsets.put(stage, (nanos - origin) / 1_000_000));
            }
            result.add(new LatencyReport.Turn(i + 1, offsets));
        }
        return new LatencyReport(result);
    }

    /** Milliseconds between two stages of a 1-based turn, if both were marked. */
    public synchronized Optional<Long> between(int turn, Stage from, Stage to) {
        Map<Stage, Long> marks = turns.get(turn - 1);
        Long a = marks.get(from);
        Long b = marks.get(to);
        return a == null || b == null ? Optional.empty() : Optional.of((b - a) / 1_000_000);
    }
}
