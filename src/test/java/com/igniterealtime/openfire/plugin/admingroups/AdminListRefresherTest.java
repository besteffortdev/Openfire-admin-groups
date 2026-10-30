package com.igniterealtime.openfire.plugin.admingroups;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminListRefresherTest {

    private List<String> configured;
    private int refreshes;
    private AdminListRefresher refresher;

    @BeforeEach
    void setUp() {
        configured = new ArrayList<>(List.of("ops", "Domain Admins"));
        refreshes = 0;
        refresher = new AdminListRefresher(() -> refreshes++, Runnable::run, () -> List.copyOf(configured),
            saved -> {
                configured.clear();
                configured.addAll(saved);
            });
    }

    @Test
    void aChangeToAConfiguredGroupRefreshes() {
        refresher.onMembershipChange("ops");
        assertEquals(1, refreshes);
    }

    @Test
    void aChangeToAnyOtherGroupDoesNot() {
        refresher.onMembershipChange("sales");
        assertEquals(0, refreshes);
    }

    @Test
    void aRenameIsFollowed() {
        refresher.onRenamed("ops", "operations");
        assertEquals(List.of("operations", "Domain Admins"), configured);
    }

    @Test
    void aRenameToANameThatCannotBeStoredDropsTheGroup() {
        refresher.onRenamed("ops", "ops, east");
        assertEquals(List.of("Domain Admins"), configured);
    }

    @Test
    void renamesOfOtherGroupsAreIgnored() {
        refresher.onRenamed("sales", "marketing");
        refresher.onRenamed(null, "marketing");
        assertEquals(List.of("ops", "Domain Admins"), configured);
    }

    @Test
    void aDeletedGroupIsDroppedSoANewOneByThatNameIsNotTrusted() {
        refresher.onDeleting("ops");
        assertEquals(List.of("Domain Admins"), configured);
    }

    @Test
    void aFailedRefreshIsLoggedNotThrown() {
        // It runs as a scheduled task, which an escaping exception would cancel for good.
        AdminListRefresher failing = new AdminListRefresher(() -> {
            throw new IllegalStateException("LDAP is down");
        }, Runnable::run, List::of, saved -> { });
        assertDoesNotThrow(failing::refreshNow);
    }
}
