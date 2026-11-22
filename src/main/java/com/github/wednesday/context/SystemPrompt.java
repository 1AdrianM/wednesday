package com.github.wednesday.context;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads the system prompt text from a file, so it can be edited without touching code. */
public final class SystemPrompt {

    /** Default prompt bundled with the application. */
    public static final String DEFAULT_RESOURCE = "/prompts/system.txt";

    private SystemPrompt() {
    }

    public static String fromClasspath(String resource) {
        try (InputStream in = SystemPrompt.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("system prompt not found on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String fromFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read system prompt " + path, e);
        }
    }

    /** The bundled default prompt. */
    public static String defaultPrompt() {
        return fromClasspath(DEFAULT_RESOURCE);
    }
}
