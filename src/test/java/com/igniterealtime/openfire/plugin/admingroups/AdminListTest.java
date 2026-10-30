package com.igniterealtime.openfire.plugin.admingroups;

import org.jivesoftware.openfire.admin.AdminProvider;
import org.jivesoftware.openfire.group.GroupNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.JID;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminListTest {

    private static final String DOMAIN = "example.org";
    private static final JID ADMIN = new JID("admin@example.org");
    private static final JID ALICE = new JID("alice@example.org");
    private static final JID BOB = new JID("bob@example.org");
    private static final JID CAROL = new JID("carol@example.org");

    /** Stands in for DefaultAdminProvider: a stored list, optionally read-only. */
    static final class StoredProvider implements AdminProvider {
        final List<JID> stored = new ArrayList<>();
        boolean readOnly;
        int writes;

        @Override
        public List<JID> getAdmins() {
            return new ArrayList<>(stored);
        }

        @Override
        public void setAdmins(List<JID> admins) {
            if (readOnly) throw new UnsupportedOperationException("read only");
            writes++;
            stored.clear();
            stored.addAll(admins);
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }
    }

    private StoredProvider delegate;
    private Map<String, List<JID>> directory;
    private List<String> configured;
    private int afterWrites;
    private AdminList adminList;

    @BeforeEach
    void setUp() {
        delegate = new StoredProvider();
        delegate.stored.add(ADMIN);
        directory = new HashMap<>();
        configured = new ArrayList<>();
        afterWrites = 0;
        adminList = new AdminList(delegate, this::lookup, DOMAIN, () -> List.copyOf(configured), () -> afterWrites++);
    }

    private List<JID> lookup(String group) throws GroupNotFoundException {
        if (group.equals("broken")) throw new IllegalStateException("LDAP is down");
        List<JID> members = directory.get(group);
        if (members == null) throw new GroupNotFoundException(group);
        return members;
    }

    @Test
    void withNoGroupsTheListIsTheWrappedProvidersOwn() {
        assertEquals(List.of(ADMIN), adminList.admins());
    }

    @Test
    void groupMembersAreAddedAfterTheExplicitAdministrators() {
        directory.put("ops", List.of(ALICE, ADMIN, BOB));
        configured.add("ops");

        assertEquals(List.of(ADMIN, ALICE, BOB), adminList.admins());
    }

    @Test
    void everyConfiguredGroupCountsAndRepeatsAreDropped() {
        directory.put("ops", List.of(ALICE));
        directory.put("security", List.of(ALICE, CAROL));
        configured.addAll(List.of("ops", "security"));

        assertEquals(List.of(ADMIN, ALICE, CAROL), adminList.admins());
        assertEquals(List.of("ops", "security"), adminList.snapshot().grantsOf(ALICE));
    }

    @Test
    void theReturnedListCanBeModifiedAsOpenfireDoes() {
        List<JID> admins = adminList.admins();
        assertDoesNotThrow(() -> admins.add(BOB));
    }

    @Test
    void accountsOfOtherServersAndBareDomainsAreNeverAdministrators() {
        directory.put("ops", List.of(new JID("mallory@evil.example"), new JID("example.org"), ALICE));
        configured.add("ops");

        assertEquals(List.of(ADMIN, ALICE), adminList.admins());
        assertEquals(2, adminList.snapshot().groups().get(0).ignored());
    }

    @Test
    void membersWithAResourceAreStoredBare() {
        directory.put("ops", List.of(new JID("alice@example.org/phone")));
        configured.add("ops");

        assertEquals(List.of(ADMIN, ALICE), adminList.admins());
    }

    @Test
    void aMissingGroupGrantsNobodyButTheRestStillApply() {
        directory.put("ops", List.of(ALICE));
        configured.addAll(List.of("gone", "ops"));

        assertEquals(List.of(ADMIN, ALICE), adminList.admins());
        assertFalse(adminList.snapshot().groups().get(0).found());
    }

    @Test
    void aFailingLookupDoesNotLockOutTheExplicitAdministrators() {
        directory.put("ops", List.of(ALICE));
        configured.addAll(List.of("broken", "ops"));

        assertEquals(List.of(ADMIN, ALICE), adminList.admins());
        assertFalse(adminList.snapshot().groups().get(0).found());
    }

    @Test
    void leavingTheGroupEndsAccessAtTheNextRead() {
        directory.put("ops", new ArrayList<>(List.of(ALICE, BOB)));
        configured.add("ops");
        adminList.admins();

        directory.get("ops").remove(BOB);

        assertEquals(List.of(ADMIN, ALICE), adminList.admins());
    }

    @Test
    void writesLeaveOutAccountsThatAreThereOnlyThroughAGroup() {
        directory.put("ops", List.of(ALICE));
        configured.add("ops");
        // What AdminManager.addAdminAccount does: the cached list plus one, written back whole.
        List<JID> cached = adminList.admins();
        cached.add(CAROL);

        adminList.setAdmins(cached);

        assertEquals(List.of(ADMIN, CAROL), delegate.stored);
    }

    @Test
    void anExplicitAdministratorWhoIsAlsoInAGroupStaysExplicit() {
        delegate.stored.add(ALICE);
        directory.put("ops", List.of(ALICE));
        configured.add("ops");

        adminList.setAdmins(adminList.admins());

        assertEquals(List.of(ADMIN, ALICE), delegate.stored);
    }

    @Test
    void removingAnExplicitAdministratorStillWorks() {
        delegate.stored.add(BOB);
        List<JID> cached = adminList.admins();
        cached.remove(BOB);

        adminList.setAdmins(cached);

        assertEquals(List.of(ADMIN), delegate.stored);
    }

    @Test
    void aReadOnlyProviderIsNotWrittenSoAUserDeletionDoesNotFail() {
        delegate.readOnly = true;
        List<JID> cached = adminList.admins();
        cached.remove(ADMIN);

        assertDoesNotThrow(() -> adminList.setAdmins(cached));
        assertEquals(List.of(ADMIN), delegate.stored);
        assertEquals(1, afterWrites);
    }

    @Test
    void everyWriteIsFollowedByAReload() {
        adminList.setAdmins(adminList.admins());
        assertEquals(1, afterWrites);
        assertEquals(1, delegate.writes);
    }

    @Test
    void wouldBeAdminJudgesAProposedGroupList() {
        delegate.stored.add(CAROL);
        directory.put("ops", List.of(ALICE, BOB, CAROL));
        directory.put("security", List.of(BOB));

        assertFalse(adminList.wouldBeAdmin(ALICE, List.of("security")));
        assertTrue(adminList.wouldBeAdmin(BOB, List.of("security")));
        assertTrue(adminList.wouldBeAdmin(CAROL, List.of()));
        assertTrue(adminList.wouldBeAdmin(new JID("alice@example.org/console"), List.of("ops")));
    }

    @Test
    void wouldBeAdminCountsAnUnreadableGroupAsGrantingNobody() {
        directory.put("ops", List.of(ALICE));

        assertFalse(adminList.wouldBeAdmin(ALICE, List.of("broken", "gone")));
        assertTrue(adminList.wouldBeAdmin(ALICE, List.of("broken", "ops")));
    }

    @Test
    void wouldBeAdminNeverCountsAnotherServersAccount() {
        directory.put("ops", List.of(new JID("alice@evil.example")));
        assertFalse(adminList.wouldBeAdmin(new JID("alice@evil.example"), List.of("ops")));
    }

    @Test
    void theSnapshotNamesWhatGrantsEachAdministrator() {
        delegate.stored.add(CAROL);
        directory.put("ops", List.of(ALICE, CAROL));
        configured.add("ops");
        adminList.admins();

        assertEquals(List.of("ops"), adminList.snapshot().grantsOf(ALICE));
        assertEquals(List.of("admin list", "ops"), adminList.snapshot().grantsOf(CAROL));
        assertEquals(List.of(), adminList.snapshot().grantsOf(BOB));
    }

    @Test
    void thereIsNoSnapshotBeforeTheFirstRead() {
        assertNull(adminList.snapshot());
    }

    @Test
    void anUnboundProviderFailsInsteadOfReportingNobody() {
        GroupAdminProvider.unbind();
        GroupAdminProvider provider = new GroupAdminProvider();
        // An empty answer would let an account named "admin" in; failing lets nobody in.
        assertThrows(IllegalStateException.class, provider::getAdmins);
    }

    @Test
    void theBoundProviderForwardsToTheAdminList() {
        directory.put("ops", List.of(ALICE));
        configured.add("ops");
        GroupAdminProvider.bind(adminList);
        try {
            GroupAdminProvider provider = new GroupAdminProvider();
            assertEquals(List.of(ADMIN, ALICE), provider.getAdmins());
            assertFalse(provider.isReadOnly());
        } finally {
            GroupAdminProvider.unbind();
        }
    }
}
