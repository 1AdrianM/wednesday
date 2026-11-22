package com.github.wednesday.common;

import java.util.Objects;
import java.util.UUID;

/** Identifies one client session; appears in every log line written on its behalf. */
public record SessionId(String value) {

    public SessionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
    }

    /** A new random id, short enough to read in logs. */
    public static SessionId random() {
        return new SessionId(UUID.randomUUID().toString().substring(0, 12));
    }

    @Override
    public String toString() {
        return value;
    }
}
