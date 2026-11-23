package com.github.wednesday.session;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStateMachineTest {

    /** Every (from, to) pair, so a new transition cannot slip in unreviewed. */
    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "IDLE,      IDLE,      false",
            "IDLE,      LISTENING, true",
            "IDLE,      THINKING,  true",   // typed text skips listening
            "IDLE,      SPEAKING,  false",
            "LISTENING, IDLE,      true",   // nothing heard, cancelled or STT failed
            "LISTENING, LISTENING, false",
            "LISTENING, THINKING,  true",   // final transcript
            "LISTENING, SPEAKING,  false",
            "THINKING,  IDLE,      true",   // reply failed, finished without audio, or reset
            "THINKING,  LISTENING, false",  // barge-in is Phase 4
            "THINKING,  THINKING,  false",
            "THINKING,  SPEAKING,  true",   // first audio
            "SPEAKING,  IDLE,      true",   // reply finished or reset
            "SPEAKING,  LISTENING, false",  // barge-in is Phase 4
            "SPEAKING,  THINKING,  false",
            "SPEAKING,  SPEAKING,  false",
    })
    void transitionTable(SessionState from, SessionState to, boolean allowed) {
        assertEquals(allowed, SessionStateMachine.allowed(from, to));

        List<SessionState> seen = new ArrayList<>();
        var machine = new SessionStateMachine(from, seen::add);
        assertEquals(allowed, machine.moveTo(to));
        assertEquals(allowed ? to : from, machine.current(), "illegal transitions leave the state unchanged");
        assertEquals(allowed ? List.of(to) : List.of(), seen, "listener hears only real changes");
    }

    @Test
    void tableCoversEveryPair() {
        int pairs = 0;
        for (SessionState from : SessionState.values()) {
            for (SessionState to : SessionState.values()) {
                SessionStateMachine.allowed(from, to);
                pairs++;
            }
        }
        assertEquals(16, pairs, "update transitionTable when states change");
        assertEquals(EnumSet.of(SessionState.IDLE, SessionState.LISTENING, SessionState.THINKING, SessionState.SPEAKING),
                EnumSet.allOf(SessionState.class));
    }

    @Test
    void startsIdle() {
        assertTrue(new SessionStateMachine(s -> { }).current() == SessionState.IDLE);
    }
}
