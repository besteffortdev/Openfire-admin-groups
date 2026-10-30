package com.igniterealtime.openfire.plugin.admingroups;

import org.jivesoftware.openfire.admin.AdminProvider;
import org.jivesoftware.openfire.group.GroupManager;
import org.jivesoftware.openfire.group.GroupNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The administrators the plugin reports to Openfire: every account the wrapped provider lists,
 * plus the local members of the configured groups. Writes go to the wrapped provider, minus the
 * accounts that are there only because of a group.
 */
public final class AdminList {

    private static final Logger Log = LoggerFactory.getLogger(AdminList.class);

    /** Reads a group's current members and administrators. */
    @FunctionalInterface
    interface GroupLookup {
        Collection<JID> membersOf(String group) throws GroupNotFoundException;
    }

    /**
     * One configured group as of a refresh. A group that could not be read grants nobody.
     *
     * @param admins the group's local accounts, which it makes administrators
     * @param ignored members that are not accounts on this server, and so are never administrators
     */
    public record GroupResult(String name, boolean found, List<JID> admins, int ignored) {
    }

    /** Who was an administrator at one refresh, and why. */
    public record Snapshot(List<JID> explicit, List<GroupResult> groups, Instant takenAt) {

        /** Every administrator: the wrapped provider's first, then each group's in order. */
        public List<JID> all() {
            Set<JID> all = new LinkedHashSet<>(explicit);
            for (GroupResult group : groups) all.addAll(group.admins());
            return List.copyOf(all);
        }

        /** The accounts that are administrators only because of a group. */
        Set<JID> groupOnly() {
            Set<JID> result = new HashSet<>();
            for (GroupResult group : groups) result.addAll(group.admins());
            result.removeAll(explicit);
            return result;
        }

        /** What makes {@code jid} an administrator: "admin list" and/or group names; empty if nothing. */
        public List<String> grantsOf(JID jid) {
            List<String> sources = new ArrayList<>();
            if (explicit.contains(jid)) sources.add("admin list");
            for (GroupResult group : groups) {
                if (group.admins().contains(jid)) sources.add(group.name());
            }
            return sources;
        }
    }

    private final AdminProvider delegate;
    private final GroupLookup lookup;
    private final String domain;
    private final Supplier<List<String>> configuredGroups;
    private final Runnable afterWrite;

    private volatile Snapshot last;

    /**
     * @param delegate the provider that was in use before the plugin, still the home of the
     *     explicitly listed administrators
     * @param domain this server's XMPP domain: only accounts in it can be made administrators
     * @param afterWrite run after every write, to make Openfire re-read the list
     */
    AdminList(AdminProvider delegate, GroupLookup lookup, String domain,
              Supplier<List<String>> configuredGroups, Runnable afterWrite) {
        this.delegate = delegate;
        this.lookup = lookup;
        this.domain = domain;
        this.configuredGroups = configuredGroups;
        this.afterWrite = afterWrite;
    }

    /** Reads groups through Openfire, bypassing its group cache so that LDAP changes are seen. */
    static GroupLookup openfireGroups() {
        return group -> GroupManager.getInstance().getGroup(group, true).getAll();
    }

    /** Re-reads the wrapped provider and every group; a new, modifiable list each time, as Openfire expects. */
    synchronized List<JID> admins() {
        Snapshot previous = last;
        Snapshot now = take(previous);
        logChanges(previous, now);
        last = now;
        return new ArrayList<>(now.all());
    }

    /**
     * Stores {@code requested} in the wrapped provider, leaving out the accounts that are on it
     * only because of a group: storing them would keep them administrators after they leave it.
     * A read-only provider is not written to, so a user deletion does not fail on it.
     */
    void setAdmins(List<JID> requested) {
        if (delegate.isReadOnly()) {
            Log.debug("Not storing the admin list: {} is read-only", delegate.getClass().getName());
        } else {
            delegate.setAdmins(explicitPart(requested));
        }
        afterWrite.run();
    }

    private synchronized List<JID> explicitPart(List<JID> requested) {
        Set<JID> groupOnly = last == null ? Set.of() : last.groupOnly();
        Set<JID> explicitNow = bare(delegate.getAdmins());
        Set<JID> keep = new LinkedHashSet<>();
        for (JID jid : bare(requested)) {
            if (explicitNow.contains(jid) || !groupOnly.contains(jid)) keep.add(jid);
        }
        return new ArrayList<>(keep);
    }

    boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    /**
     * Whether {@code jid} would be an administrator if {@code groups} were the configured groups.
     * Reads the wrapped provider and the groups afresh, by the same rules as {@link #admins}.
     */
    boolean wouldBeAdmin(JID jid, List<String> groups) {
        JID bare = jid.asBareJID();
        if (bare(delegate.getAdmins()).contains(bare)) return true;
        if (bare.getNode() == null || !domain.equals(bare.getDomain())) return false;
        for (String group : groups) {
            try {
                for (JID member : lookup.membersOf(group)) {
                    if (member != null && bare.equals(member.asBareJID())) return true;
                }
            } catch (GroupNotFoundException | RuntimeException ignored) {
                // A group that cannot be read grants nobody, here as in admins().
            }
        }
        return false;
    }

    /** The provider whose administrators are kept; shown on the admin page. */
    public String delegateName() {
        return delegate.getClass().getName();
    }

    /** The last refresh, or null before the first. */
    public Snapshot snapshot() {
        return last;
    }

    private Snapshot take(Snapshot previous) {
        List<JID> explicit = List.copyOf(bare(delegate.getAdmins()));
        List<GroupResult> groups = new ArrayList<>();
        for (String name : configuredGroups.get()) groups.add(resolve(name, previous));
        return new Snapshot(explicit, List.copyOf(groups), Instant.now());
    }

    private GroupResult resolve(String name, Snapshot previous) {
        boolean wasReadable = previous == null || previous.groups().stream()
            .noneMatch(group -> group.name().equals(name) && !group.found());
        try {
            Collection<JID> members = lookup.membersOf(name);
            Set<JID> admins = new LinkedHashSet<>();
            int ignored = 0;
            for (JID member : members) {
                if (member == null) continue;
                JID bare = member.asBareJID();
                if (bare.getNode() != null && domain.equals(bare.getDomain())) admins.add(bare);
                else ignored++;
            }
            if (!wasReadable) Log.info("Group '{}' can be read again", name);
            return new GroupResult(name, true, List.copyOf(admins), ignored);
        } catch (GroupNotFoundException notFound) {
            if (wasReadable) Log.warn("Group '{}' was not found; it makes nobody an administrator", name);
            return new GroupResult(name, false, List.of(), 0);
        } catch (RuntimeException e) {
            // One broken group must not fail the whole list: Openfire would then refuse every
            // administrator, including the ones listed explicitly.
            if (wasReadable) Log.warn("Could not read group '{}'; it makes nobody an administrator", name, e);
            return new GroupResult(name, false, List.of(), 0);
        }
    }

    /** Logs every account that gained or lost administrator rights through a group. */
    private static void logChanges(Snapshot previous, Snapshot now) {
        Set<JID> before = previous == null ? Set.of() : previous.groupOnly();
        Set<JID> after = now.groupOnly();
        for (JID jid : after) {
            if (!before.contains(jid)) {
                Log.info("{} is an administrator through group(s) {}", jid, now.grantsOf(jid));
            }
        }
        for (JID jid : before) {
            if (!after.contains(jid)) {
                Log.info("{} is no longer an administrator through a group{}", jid,
                    now.explicit().contains(jid) ? " (still on the admin list)" : "");
            }
        }
    }

    private static Set<JID> bare(Collection<JID> jids) {
        Set<JID> result = new LinkedHashSet<>();
        if (jids == null) return result;
        for (JID jid : jids) {
            if (jid != null) result.add(jid.asBareJID());
        }
        return result;
    }
}
