package com.igniterealtime.openfire.plugin.admingroups;

import org.jivesoftware.openfire.event.GroupEventListener;
import org.jivesoftware.openfire.group.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps Openfire's admin list in step with the groups. Openfire reads the list once and keeps it,
 * so a membership change counts only after a refresh: one follows every change to a configured
 * group, and the plugin's timer covers changes made in LDAP, which raise no events. Renames and
 * deletions of a configured group are carried into the setting.
 */
final class AdminListRefresher implements GroupEventListener {

    private static final Logger Log = LoggerFactory.getLogger(AdminListRefresher.class);

    private final Runnable refresh;
    private final Executor executor;
    private final Supplier<List<String>> configuredGroups;
    private final Consumer<List<String>> saveGroups;

    /**
     * @param refresh makes Openfire re-read the admin list
     * @param executor runs requested refreshes, off the thread that raised the event
     */
    AdminListRefresher(Runnable refresh, Executor executor,
                       Supplier<List<String>> configuredGroups, Consumer<List<String>> saveGroups) {
        this.refresh = refresh;
        this.executor = executor;
        this.configuredGroups = configuredGroups;
        this.saveGroups = saveGroups;
    }

    /** Refreshes on the executor. */
    void requestRefresh() {
        executor.execute(this::refreshNow);
    }

    /**
     * Refreshes on this thread. A failure is logged, not thrown: this also runs as the body of a
     * scheduled task, which an escaping exception would cancel for good (CERT TPS03-J).
     */
    void refreshNow() {
        try {
            refresh.run();
        } catch (RuntimeException e) {
            Log.warn("Could not refresh the admin list; Openfire keeps the previous one", e);
        }
    }

    void onMembershipChange(String group) {
        if (configuredGroups.get().contains(group)) requestRefresh();
    }

    /** Drops a configured group that is being deleted, so a new group by that name is not trusted by accident. */
    void onDeleting(String group) {
        List<String> configured = configuredGroups.get();
        if (!configured.contains(group)) return;
        saveGroups.accept(GroupList.without(configured, group));
        Log.info("Group '{}' is being deleted; it no longer makes anyone an administrator", group);
    }

    /** Follows a rename of a configured group; a name the setting cannot hold drops the group instead. */
    void onRenamed(String from, String to) {
        List<String> configured = configuredGroups.get();
        if (from == null || !configured.contains(from)) return;
        try {
            saveGroups.accept(GroupList.renamed(configured, from, to));
            Log.info("Group '{}' was renamed to '{}'; the admin console groups follow it", from, to);
        } catch (IllegalArgumentException unstorable) {
            saveGroups.accept(GroupList.without(configured, from));
            Log.warn("Group '{}' was renamed to '{}', which cannot be stored; it no longer makes anyone "
                + "an administrator", from, to);
        }
    }

    @Override
    public void groupCreated(Group group, Map<String, ?> params) {
        guarded("a created group", () -> onMembershipChange(group.getName()));
    }

    @Override
    public void groupDeleting(Group group, Map<String, ?> params) {
        guarded("a deleted group", () -> onDeleting(group.getName()));
    }

    @Override
    public void groupModified(Group group, Map<String, ?> params) {
        if (params == null || !"nameModified".equals(params.get("type"))) return;
        Object original = params.get("originalValue");
        guarded("a renamed group", () -> onRenamed(original instanceof String name ? name : null, group.getName()));
    }

    @Override
    public void memberAdded(Group group, Map<String, ?> params) {
        guarded("a membership change", () -> onMembershipChange(group.getName()));
    }

    @Override
    public void memberRemoved(Group group, Map<String, ?> params) {
        guarded("a membership change", () -> onMembershipChange(group.getName()));
    }

    @Override
    public void adminAdded(Group group, Map<String, ?> params) {
        guarded("a membership change", () -> onMembershipChange(group.getName()));
    }

    @Override
    public void adminRemoved(Group group, Map<String, ?> params) {
        guarded("a membership change", () -> onMembershipChange(group.getName()));
    }

    /**
     * Runs one event's handling. The barrier keeps a failure here out of the group operation that
     * raised the event, and names what failed.
     */
    private static void guarded(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            Log.warn("Could not handle {} for the admin console groups", what, e);
        }
    }
}
