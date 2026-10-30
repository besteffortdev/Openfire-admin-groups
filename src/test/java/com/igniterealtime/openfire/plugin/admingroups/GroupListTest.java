package com.igniterealtime.openfire.plugin.admingroups;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupListTest {

    @Test
    void parsingTrimsAndDropsBlanksAndRepeats() {
        assertEquals(List.of("Domain Admins", "ops"), GroupList.parse(" Domain Admins ,, ops,Domain Admins, "));
    }

    @Test
    void nothingStoredMeansNoGroups() {
        assertEquals(List.of(), GroupList.parse(null));
        assertEquals(List.of(), GroupList.parse(""));
    }

    @Test
    void formatAndParseRoundTrip() {
        List<String> groups = List.of("Domain Admins", "ops");
        assertEquals(groups, GroupList.parse(GroupList.format(groups)));
    }

    @Test
    void addingAppendsOnce() {
        List<String> once = GroupList.with(List.of("ops"), " security ");
        assertEquals(List.of("ops", "security"), once);
        assertEquals(once, GroupList.with(once, "security"));
    }

    @Test
    void namesTheStoredFormCannotHoldAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> GroupList.with(List.of(), "a,b"));
        assertThrows(IllegalArgumentException.class, () -> GroupList.with(List.of(), "  "));
        assertThrows(IllegalArgumentException.class, () -> GroupList.with(List.of(), null));
    }

    @Test
    void removingAnAbsentGroupChangesNothing() {
        assertEquals(List.of("ops"), GroupList.without(List.of("ops"), "security"));
        assertEquals(List.of(), GroupList.without(List.of("ops"), "ops"));
    }

    @Test
    void aRenameKeepsThePosition() {
        assertEquals(List.of("a", "renamed", "c"), GroupList.renamed(List.of("a", "b", "c"), "b", "renamed"));
    }

    @Test
    void aRenameOntoAnotherConfiguredGroupLeavesOneEntry() {
        assertEquals(List.of("a", "c"), GroupList.renamed(List.of("a", "b", "c"), "b", "a"));
    }
}
