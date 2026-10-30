package com.igniterealtime.openfire.plugin.admingroups;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The comma-separated group list kept in {@code plugin.admingroups.groups}: parsing it, and the
 * edits the admin page and the group events make to it. Every result is a new list.
 */
public final class GroupList {

    private GroupList() {
    }

    /** The names in a stored value, trimmed, without blanks or repeats, in their stored order. */
    public static List<String> parse(String value) {
        if (value == null) return List.of();
        Set<String> names = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) names.add(name);
        }
        return List.copyOf(names);
    }

    /** The stored form of a list. */
    static String format(List<String> names) {
        return String.join(",", names);
    }

    /**
     * The list with {@code name} appended, unless it is already there.
     *
     * @throws IllegalArgumentException if the name is blank or contains a comma, which the stored
     *     form cannot hold
     */
    static List<String> with(List<String> names, String name) {
        String clean = storable(name);
        if (names.contains(clean)) return List.copyOf(names);
        List<String> result = new ArrayList<>(names);
        result.add(clean);
        return List.copyOf(result);
    }

    static List<String> without(List<String> names, String name) {
        List<String> result = new ArrayList<>(names);
        result.remove(name);
        return List.copyOf(result);
    }

    /**
     * The list with {@code from} replaced by {@code to} in the same position.
     *
     * @throws IllegalArgumentException if {@code to} is blank or contains a comma
     */
    static List<String> renamed(List<String> names, String from, String to) {
        String clean = storable(to);
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) result.add(name.equals(from) ? clean : name);
        return List.copyOf(result);
    }

    /**
     * The trimmed name, as the stored form can hold it.
     *
     * @throws IllegalArgumentException if it is blank or contains a comma
     */
    static String storable(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("A group name cannot be blank.");
        if (clean.contains(",")) {
            throw new IllegalArgumentException("A group whose name contains a comma cannot be used here.");
        }
        return clean;
    }
}
