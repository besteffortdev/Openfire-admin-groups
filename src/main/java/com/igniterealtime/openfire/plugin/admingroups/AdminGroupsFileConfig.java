package com.igniterealtime.openfire.plugin.admingroups;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.jivesoftware.openfire.group.GroupManager;
import org.jivesoftware.openfire.group.GroupNotFoundException;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the {@code <admingroups>} block of {@code conf/openfire.xml} to the plugin's settings, as
 * the sister plugins do with theirs. Declare-only: the file is never written, and what it does not
 * mention keeps its value. The format is in the README under "Declarative config".
 */
public final class AdminGroupsFileConfig {

    private static final Logger Log = LoggerFactory.getLogger(AdminGroupsFileConfig.class);

    /** Enough warnings to see the shape of a mistake; past that the file itself is the problem. */
    private static final int MAX_WARNINGS = 20;

    /** The element read from {@code openfire.xml}; Openfire ignores elements it does not know. */
    static final String CONFIG_ELEMENT = "admingroups";

    /** Returns a group's own spelling of its name. */
    @FunctionalInterface
    interface GroupSpelling {
        String of(String name) throws GroupNotFoundException;
    }

    /**
     * What the block declares, before anything is applied.
     *
     * @param groups the declared group list, or null when the block has no {@code <groups>}
     * @param refreshSeconds the declared interval, or null when it declares none
     */
    public record Declaration(boolean blockPresent, List<String> groups, Integer refreshSeconds,
                              List<String> warnings) {

        static final Declaration NONE = new Declaration(false, null, null, List.of());
    }

    /** What one pass over the file did; shown on the admin page. */
    public record IngestResult(boolean blockPresent, boolean groupsDeclared, int settingsUpdated,
                               List<String> warnings) {

        static final IngestResult EMPTY = new IngestResult(false, false, 0, List.of());
    }

    private final GroupSpelling spelling;
    private volatile IngestResult lastResult = IngestResult.EMPTY;
    private volatile Long lastLoadedAtMillis;

    AdminGroupsFileConfig(GroupSpelling spelling) {
        this.spelling = spelling;
    }

    static GroupSpelling openfireGroups() {
        return name -> GroupManager.getInstance().getGroup(name).getName();
    }

    /** The {@code conf/openfire.xml} this reads. */
    public Path configFile() {
        return JiveGlobals.getHomePath().resolve("conf").resolve("openfire.xml");
    }

    public IngestResult lastResult() {
        return lastResult;
    }

    /** When the file was last read, or null if it never has been. */
    public Long lastLoadedAtMillis() {
        return lastLoadedAtMillis;
    }

    /** Reads and applies the block. A problem with the file becomes a warning, never an exception. */
    IngestResult ingest() {
        return apply(read());
    }

    /** Reads the block without applying it. Never throws. */
    Declaration read() {
        return read(configFile());
    }

    Declaration read(Path file) {
        if (!Files.isRegularFile(file)) {
            Log.info("No {}; skipping file-based configuration", file);
            return Declaration.NONE;
        }
        Element block;
        try {
            Document doc = hardenedReader().read(file.toFile());
            Element jive = doc.getRootElement();
            block = jive == null ? null : jive.element(CONFIG_ELEMENT);
        } catch (DocumentException | SAXException e) {
            Log.warn("Could not parse {} for the admin console groups", file, e);
            return Declaration.NONE;
        }
        if (block == null) {
            Log.info("No <{}> block in {}; skipping file-based configuration", CONFIG_ELEMENT, file);
            return Declaration.NONE;
        }
        return parse(block);
    }

    /** A reader that refuses DTDs and external entities (CERT IDS17-J); defence in depth for an admin-owned file. */
    private static SAXReader hardenedReader() throws SAXException {
        SAXReader reader = new SAXReader();
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return reader;
    }

    /** What an {@code <admingroups>} element declares; what it could not use becomes a warning. */
    Declaration parse(Element block) {
        List<String> warnings = new ArrayList<>();
        Integer refreshSeconds = refreshSeconds(block, warnings);
        Element groupsEl = block.element("groups");
        List<String> groups = groupsEl == null ? null : groups(groupsEl, warnings);
        return new Declaration(true, groups, refreshSeconds, List.copyOf(warnings));
    }

    private List<String> groups(Element groupsEl, List<String> warnings) {
        List<String> names = List.of();
        for (Element groupEl : groupsEl.elements("group")) {
            String raw = groupEl.attributeValue("name");
            try {
                names = GroupList.with(names, spelled(GroupList.storable(raw), warnings));
            } catch (IllegalArgumentException unstorable) {
                warn(warnings, "skipped <group name='" + (raw == null ? "" : raw) + "'>: "
                    + unstorable.getMessage());
            }
        }
        return names;
    }

    /**
     * The group's own spelling, so that Openfire's group events match it. A group that cannot be
     * found is kept as written: at start-up LDAP may simply be unreachable, and dropping the group
     * would lock its members out.
     */
    private String spelled(String name, List<String> warnings) {
        try {
            return spelling.of(name);
        } catch (GroupNotFoundException notFound) {
            warn(warnings, "group '" + name + "' was not found; it is kept, and makes nobody an "
                + "administrator until it exists");
            return name;
        } catch (RuntimeException e) {
            // The barrier keeps a failing group provider from failing the plugin start.
            Log.warn("Could not look up group '{}' declared in openfire.xml", name, e);
            warn(warnings, "group '" + name + "' could not be looked up; it is kept as written");
            return name;
        }
    }

    private static Integer refreshSeconds(Element block, List<String> warnings) {
        String raw = block.attributeValue("refreshSeconds");
        if (raw == null) return null;
        try {
            return Integer.valueOf(raw.strip());
        } catch (NumberFormatException notANumber) {
            warn(warnings, "<" + CONFIG_ELEMENT + "> refreshSeconds='" + raw + "' is not a number, ignored");
            return null;
        }
    }

    /** Applies a declaration to the settings and remembers the result for the admin page. */
    IngestResult apply(Declaration declared) {
        if (!declared.blockPresent()) return remember(IngestResult.EMPTY);

        List<String> warnings = new ArrayList<>(declared.warnings());
        int updated = 0;
        if (declared.groups() != null && !declared.groups().equals(AdminGroupsPlugin.groups())) {
            AdminGroupsProperties.GROUPS.setValue(GroupList.format(declared.groups()));
            updated++;
        }
        if (declared.refreshSeconds() != null) updated += applyRefresh(declared.refreshSeconds(), warnings);

        IngestResult result = new IngestResult(true, declared.groups() != null, updated, List.copyOf(warnings));
        Log.info("Read <{}> from {}: {} setting(s) changed; groups: {}{}", CONFIG_ELEMENT, configFile(), updated,
            declared.groups() == null ? "not declared" : declared.groups(),
            warnings.isEmpty() ? "" : "; " + warnings.size() + " warning(s)");
        for (String warning : warnings) Log.warn("{}: {}", configFile().getFileName(), warning);
        return remember(result);
    }

    /**
     * Range-checked by writing the value and reading it back: {@link org.jivesoftware.util.SystemProperty}
     * returns the default for an out-of-range value, so the bounds live only in {@link AdminGroupsProperties}.
     */
    private static int applyRefresh(int declared, List<String> warnings) {
        int previous = AdminGroupsProperties.REFRESH_SECONDS.getValue();
        if (declared == previous) return 0;
        AdminGroupsProperties.REFRESH_SECONDS.setValue(declared);
        if (AdminGroupsProperties.REFRESH_SECONDS.getValue() != declared) {
            AdminGroupsProperties.REFRESH_SECONDS.setValue(previous);
            warn(warnings, "<" + CONFIG_ELEMENT + "> refreshSeconds='" + declared + "' is outside the range allowed for "
                + AdminGroupsProperties.REFRESH_SECONDS.getKey() + ", ignored");
            return 0;
        }
        return 1;
    }

    private IngestResult remember(IngestResult result) {
        lastResult = result;
        lastLoadedAtMillis = System.currentTimeMillis();
        return result;
    }

    private static void warn(List<String> warnings, String message) {
        if (warnings.size() < MAX_WARNINGS) warnings.add(message);
    }
}
