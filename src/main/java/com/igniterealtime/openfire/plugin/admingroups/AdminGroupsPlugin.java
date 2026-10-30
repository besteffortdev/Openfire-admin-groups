package com.igniterealtime.openfire.plugin.admingroups;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.admin.AdminManager;
import org.jivesoftware.openfire.admin.AdminProvider;
import org.jivesoftware.openfire.admin.DefaultAdminProvider;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.event.GroupEventDispatcher;
import org.jivesoftware.openfire.group.Group;
import org.jivesoftware.openfire.group.GroupManager;
import org.jivesoftware.openfire.group.GroupNotFoundException;
import org.jivesoftware.util.ClassUtils;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.TaskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TimerTask;
import java.util.function.Consumer;

/**
 * Openfire entry point. On load it applies the {@code <admingroups>} block of openfire.xml, then
 * puts {@link GroupAdminProvider} in place of the configured admin provider, which keeps its own
 * administrators; on unload it puts the previous setting back. The admin page works through the
 * public methods.
 */
public class AdminGroupsPlugin implements Plugin {

    private static final Logger Log = LoggerFactory.getLogger(AdminGroupsPlugin.class);

    private static final String PROVIDER_CLASS = GroupAdminProvider.class.getName();

    /** How many group names the admin page suggests; enough for any hand-picked LDAP filter. */
    private static final int MAX_SUGGESTIONS = 500;

    private static volatile AdminGroupsPlugin instance;

    private final AdminGroupsFileConfig fileConfig = new AdminGroupsFileConfig(AdminGroupsFileConfig.openfireGroups());
    private AdminList adminList;
    private AdminListRefresher refresher;
    private TimerTask timer;
    /** The {@code provider.admin.className} value to put back on unload; null when there is nothing to put back. */
    private volatile String providerToRestore;
    /** Why group members are not administrators now; null while they are. */
    private volatile String notInEffect = "The plugin is starting.";

    private final Consumer<String> onGroupsChange =
        value -> guarded("apply the new group list", this::requestRefresh);
    private final Consumer<Integer> onIntervalChange =
        seconds -> guarded("reschedule the refresh", () -> schedule(seconds));
    @SuppressWarnings("rawtypes")
    private final Consumer<Class> onProviderChange =
        providerClass -> guarded("follow the admin provider change", () -> providerChanged(providerClass));

    /** The loaded plugin, for the admin page; null while it is not loaded. */
    public static AdminGroupsPlugin getInstance() {
        return instance;
    }

    /**
     * Installs the provider, or nothing. A failed start is undone before it is passed on: Openfire
     * does not call {@link #destroyPlugin} after a failed {@code initializePlugin}, and a half
     * installed admin provider is not something to leave behind (CERT ERR03-J).
     */
    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory) {
        try {
            start();
        } catch (Throwable failure) {
            // Rethrown unchanged. Error is included on purpose: a NoClassDefFoundError from an
            // incompatible Openfire is the likeliest way to get here.
            try {
                destroyPlugin();
            } catch (Throwable rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
        instance = this;
        Log.info("Admin Console Groups started; keeping the administrators of {}; groups: {}",
            adminList.delegateName(), groups());
    }

    private void start() {
        // First, so the first admin list Openfire reads already has the file's groups.
        fileConfig.ingest();

        String key = AdminManager.ADMIN_PROVIDER.getKey();
        String configured = trimToEmpty(JiveGlobals.getProperty(key));
        boolean leftOver = PROVIDER_CLASS.equals(configured);
        String previous = leftOver ? trimToEmpty(AdminGroupsProperties.PREVIOUS_PROVIDER.getValue()) : configured;
        if (leftOver) {
            Log.warn("{} still names this plugin's provider, so the last shutdown was not clean; "
                + "wrapping '{}', saved before it", key, previous);
        } else {
            AdminGroupsProperties.PREVIOUS_PROVIDER.setValue(previous);
        }

        // The provider in use is wrapped as it is, so its state carries over. After an unclean
        // shutdown it may be this plugin's own, or Openfire's fallback, so the saved one is made.
        AdminProvider current = AdminManager.getAdminProvider();
        boolean currentIsOurs = current != null && PROVIDER_CLASS.equals(current.getClass().getName());
        AdminProvider delegate = leftOver || currentIsOurs || current == null ? instantiate(previous) : current;

        adminList = new AdminList(delegate, AdminList.openfireGroups(),
            XMPPServer.getInstance().getServerInfo().getXMPPDomain(),
            AdminGroupsPlugin::groups, AdminGroupsPlugin::reloadAdminManager);
        refresher = new AdminListRefresher(AdminGroupsPlugin::reloadAdminManager,
            task -> TaskEngine.getInstance().submit(task), AdminGroupsPlugin::groups, AdminGroupsPlugin::saveGroups);

        GroupAdminProvider.bind(adminList);
        providerToRestore = previous;
        install(key, leftOver);
        notInEffect = null;

        GroupEventDispatcher.addListener(refresher);
        AdminGroupsProperties.GROUPS.addListener(onGroupsChange);
        AdminGroupsProperties.REFRESH_SECONDS.addListener(onIntervalChange);
        AdminManager.ADMIN_PROVIDER.addListener(onProviderChange);
        schedule(AdminGroupsProperties.REFRESH_SECONDS.getValue());
        reloadAdminManager();
    }

    /**
     * Points {@code provider.admin.className} at {@link GroupAdminProvider}. Openfire loads the class
     * from the property change, on this thread, whose context class loader is the plugin's: that
     * is the only reason it can find a class that lives in a plugin.
     */
    private static void install(String key, boolean leftOver) {
        // Storing the value the property already holds may raise no change, and so load nothing.
        if (leftOver) JiveGlobals.deleteProperty(key);
        AdminManager.ADMIN_PROVIDER.setValue(GroupAdminProvider.class);
        AdminProvider active = AdminManager.getAdminProvider();
        if (!(active instanceof GroupAdminProvider)) {
            throw new IllegalStateException("Openfire did not switch to " + PROVIDER_CLASS + "; it still uses "
                + (active == null ? "no provider" : active.getClass().getName()));
        }
    }

    /** A new instance of the named provider, falling back to the default one as Openfire itself does. */
    private static AdminProvider instantiate(String className) {
        if (className.isEmpty() || PROVIDER_CLASS.equals(className)) return new DefaultAdminProvider();
        try {
            Class<?> type = ClassUtils.forName(className);
            if (AdminProvider.class.isAssignableFrom(type)) {
                return (AdminProvider) type.getDeclaredConstructor().newInstance();
            }
            Log.warn("{} is not an admin provider; keeping the default provider's administrators", className);
        } catch (ReflectiveOperationException e) {
            Log.warn("Could not create admin provider {}; keeping the default provider's administrators",
                className, e);
        }
        return new DefaultAdminProvider();
    }

    /**
     * Also the rollback for a failed {@link #initializePlugin}, so every step tolerates the part it
     * undoes never having been set up, and one failing step does not stop the next.
     */
    @Override
    public void destroyPlugin() {
        instance = null;
        // The listeners go first, so putting the setting back is not taken for an administrator's change.
        guarded("remove the admin provider listener", () -> AdminManager.ADMIN_PROVIDER.removeListener(onProviderChange));
        guarded("stop the refresh timer", this::cancelTimer);
        AdminListRefresher stopping = refresher;
        if (stopping != null) guarded("remove the group listener", () -> GroupEventDispatcher.removeListener(stopping));
        try {
            AdminGroupsProperties.GROUPS.removeListener(onGroupsChange);
            AdminGroupsProperties.REFRESH_SECONDS.removeListener(onIntervalChange);
        } catch (RuntimeException | LinkageError e) {
            // LinkageError too: if the properties class never initialized, there is nothing of it to undo.
            Log.warn("Could not remove the setting listeners", e);
        }

        String previous = providerToRestore;
        if (previous != null) {
            guarded("put the previous admin provider back", () -> restore(previous));
            providerToRestore = null;
        }
        // Openfire would otherwise keep the group members in its cached list until a restart.
        guarded("reload the admin list", AdminGroupsPlugin::reloadAdminManager);
        GroupAdminProvider.unbind();
        refresher = null;
        adminList = null;
        notInEffect = "The plugin is not loaded.";
    }

    private static void restore(String previous) {
        String key = AdminManager.ADMIN_PROVIDER.getKey();
        if (previous.isEmpty()) JiveGlobals.deleteProperty(key);
        else JiveGlobals.setProperty(key, previous);
        if (AdminManager.getAdminProvider() instanceof GroupAdminProvider) {
            Log.warn("Openfire still uses this plugin's admin provider after {} was put back to '{}'; "
                + "restart Openfire", key, previous);
        }
    }

    /** Steps aside when an administrator points {@code provider.admin.className} somewhere else. */
    private void providerChanged(Class<?> providerClass) {
        if (providerClass != null && PROVIDER_CLASS.equals(providerClass.getName())) return;
        String key = AdminManager.ADMIN_PROVIDER.getKey();
        String now = providerClass == null ? "the default provider" : providerClass.getName();
        providerToRestore = null;
        notInEffect = key + " was changed to " + now + " after the plugin started. "
            + "Reload the plugin to add the group members to that provider's administrators.";
        cancelTimer();
        // Queued, not run here: Openfire may not have switched providers yet while listeners run.
        requestRefresh();
        Log.warn("{} was changed to {}; group members are no longer administrators until the plugin is reloaded",
            key, now);
    }

    private synchronized void schedule(int seconds) {
        cancelTimer();
        AdminListRefresher target = refresher;
        if (target == null || notInEffect != null) return;
        timer = new TimerTask() {
            @Override
            public void run() {
                target.refreshNow();
            }
        };
        Duration period = Duration.ofSeconds(seconds);
        TaskEngine.getInstance().schedule(timer, period, period);
    }

    private synchronized void cancelTimer() {
        if (timer == null) return;
        timer.cancel();
        TaskEngine.getInstance().cancelScheduledTask(timer);
        timer = null;
    }

    private void requestRefresh() {
        AdminListRefresher target = refresher;
        if (target != null) target.requestRefresh();
    }

    /** Why group members are not administrators right now, or null when they are. */
    public String notInEffect() {
        return notInEffect;
    }

    public List<String> getGroups() {
        return groups();
    }

    public int getRefreshSeconds() {
        return AdminGroupsProperties.REFRESH_SECONDS.getValue();
    }

    /** The provider whose administrators are kept alongside the groups'. */
    public String getWrappedProviderName() {
        AdminList current = adminList;
        return current == null ? "" : current.delegateName();
    }

    /** The last refresh, or null before the first. */
    public AdminList.Snapshot getSnapshot() {
        AdminList current = adminList;
        return current == null ? null : current.snapshot();
    }

    /** Makes Openfire re-read the groups now, and returns the result. */
    public AdminList.Snapshot refresh() {
        reloadAdminManager();
        return getSnapshot();
    }

    /** Group names to offer on the admin page, sorted; at most {@value #MAX_SUGGESTIONS}. */
    public List<String> suggestions() {
        List<String> names = new ArrayList<>();
        for (Group group : GroupManager.getInstance().getGroups()) {
            names.add(group.getName());
            if (names.size() >= MAX_SUGGESTIONS) break;
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * Adds a group under its own spelling of its name, and applies it at once.
     *
     * @throws IllegalArgumentException with a message for the page, when there is no such group or
     *     its name cannot be stored
     */
    public void addGroup(String name) {
        String wanted = trimToEmpty(name);
        if (wanted.isEmpty()) throw new IllegalArgumentException("Enter the name of a group.");
        Group group;
        try {
            group = GroupManager.getInstance().getGroup(wanted);
        } catch (GroupNotFoundException notFound) {
            throw new IllegalArgumentException("There is no group named \"" + wanted + "\".");
        }
        saveGroups(GroupList.with(groups(), group.getName()));
        reloadAdminManager();
    }

    /** @throws IllegalArgumentException with a message for the page, when the group is not configured */
    public void removeGroup(String name) {
        List<String> configured = groups();
        if (!configured.contains(name)) {
            throw new IllegalArgumentException("\"" + name + "\" is not one of the admin console groups.");
        }
        saveGroups(GroupList.without(configured, name));
        reloadAdminManager();
    }

    /** Whether removing {@code group} would end {@code admin}'s own access, judged on a fresh read. */
    public boolean removalLocksOut(String group, JID admin) {
        return groupsLockOut(GroupList.without(groups(), group), admin);
    }

    /**
     * Whether making {@code groups} the group list would end {@code admin}'s own access, judged on a
     * fresh read. Never true while the plugin is not in effect, since the groups then grant nothing.
     */
    public boolean groupsLockOut(List<String> groups, JID admin) {
        AdminList current = adminList;
        return current != null && notInEffect == null && !current.wouldBeAdmin(admin, groups);
    }

    public AdminGroupsFileConfig getFileConfig() {
        return fileConfig;
    }

    /** Reads the {@code <admingroups>} block of openfire.xml without applying it. */
    public AdminGroupsFileConfig.Declaration readConfigFile() {
        return fileConfig.read();
    }

    /** Applies what {@link #readConfigFile} returned, and makes Openfire re-read the groups. */
    public AdminGroupsFileConfig.IngestResult applyConfigFile(AdminGroupsFileConfig.Declaration declared) {
        AdminGroupsFileConfig.IngestResult result = fileConfig.apply(declared);
        reloadAdminManager();
        return result;
    }

    static List<String> groups() {
        return GroupList.parse(AdminGroupsProperties.GROUPS.getValue());
    }

    private static void saveGroups(List<String> groups) {
        AdminGroupsProperties.GROUPS.setValue(GroupList.format(groups));
    }

    private static void reloadAdminManager() {
        AdminManager.getInstance().refreshAdminAccounts();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Runs one shutdown step or one listener body. The barrier keeps a failure in one step from
     * skipping the next, and keeps it out of the Openfire operation that called the listener.
     */
    private static void guarded(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            Log.warn("Admin Console Groups could not {}", what, e);
        }
    }
}
