package com.plutext.memberroll.server;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The worked example of an own-data store: one JSON document per note on
 * the filesystem, one directory per owner (keyed by the token's stable
 * {@code sub}), no database. Copy this shape for new per-user data; when
 * cross-user queries arrive ("browse everyone's shared X"), add an index
 * table beside it rather than walking directories.
 *
 * Writes are atomic (temp file + ATOMIC_MOVE) so a crashed request never
 * leaves a half-written document. Ids are caller-chosen but validated
 * against a strict pattern — the id is a path component, so the pattern
 * is also the path-traversal guard.
 */
final class NoteStore {

    private static final Pattern NOTE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final int MAX_NOTE_BYTES = 64 * 1024;

    private final Path root;

    NoteStore(Path root) {
        this.root = root;
    }

    /** The store used by the resources: MEMBERROLL_DATA or ~/memberroll-server. */
    static NoteStore fromEnv() {
        String configured = System.getenv("MEMBERROLL_DATA");
        Path base = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), "memberroll-server");
        return new NoteStore(base.resolve("notes"));
    }

    static boolean isValidId(String id) {
        return id != null && NOTE_ID.matcher(id).matches();
    }

    /** Create or replace one note document; returns the stored JSON. */
    String put(String owner, String id, String title, String body) throws IOException {
        JsonObject stored = Json.createObjectBuilder()
                .add("id", id)
                .add("title", title)
                .add("body", body)
                .add("updated_ms", System.currentTimeMillis())
                .build();
        Path dir = ownerDir(owner);
        Files.createDirectories(dir);
        Path tmp = dir.resolve(id + ".json.tmp");
        Files.writeString(tmp, stored.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(id + ".json"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return stored.toString();
    }

    /** The stored JSON, or null when absent. */
    String get(String owner, String id) throws IOException {
        Path file = ownerDir(owner).resolve(id + ".json");
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }

    /** True when a note was deleted, false when there was none. */
    boolean delete(String owner, String id) throws IOException {
        return Files.deleteIfExists(ownerDir(owner).resolve(id + ".json"));
    }

    /** All of one owner's notes, newest first. */
    List<JsonObject> list(String owner) throws IOException {
        Path dir = ownerDir(owner);
        if (!Files.isDirectory(dir)) return List.of();
        List<JsonObject> notes = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .toList()) {
                try (JsonReader reader = Json.createReader(new StringReader(
                        Files.readString(file, StandardCharsets.UTF_8)))) {
                    notes.add(reader.readObject());
                }
            }
        }
        notes.sort(Comparator.comparingLong(
                (JsonObject n) -> n.getJsonNumber("updated_ms").longValue()).reversed());
        return notes;
    }

    private Path ownerDir(String owner) {
        // sub is a Keycloak-issued UUID, but normalize defensively anyway:
        // it is used as a path component
        if (!owner.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("unexpected subject format");
        }
        return root.resolve(owner);
    }
}
