package com.igniterealtime.openfire.plugin.admingroups;

import org.jivesoftware.util.SystemProperty;

/**
 * The plugin's settings, registered as Openfire {@link SystemProperty} instances so each appears
 * under Server Manager -> System Properties with its default and is unregistered on unload.
 */
final class AdminGroupsProperties {

    /**
     * Must equal plugin.xml's {@code <name>}: Openfire removes a plugin's properties on unload by
     * that name, and a registration left behind makes the next hot reload fail.
     */
    static final String PLUGIN = "Admin Console Groups";

    /** Comma-separated names of the groups whose members are administrators; empty grants nobody. */
    static final SystemProperty<String> GROUPS = register("plugin.admingroups.groups",
        SystemProperty.Builder.ofType(String.class).setDefaultValue(""));

    /**
     * Seconds between re-reads of the groups. Changes made in Openfire raise events and count at
     * once; this is how a change made in LDAP, which raises none, is noticed.
     */
    static final SystemProperty<Integer> REFRESH_SECONDS = register("plugin.admingroups.refreshSeconds",
        SystemProperty.Builder.ofType(Integer.class).setDefaultValue(60).setMinValue(10).setMaxValue(86400));

    /**
     * {@code provider.admin.className} as it was before the plugin replaced it; empty means it was
     * unset. Written by the plugin, and only read back when an unclean shutdown left the plugin's
     * own class in that property.
     */
    static final SystemProperty<String> PREVIOUS_PROVIDER = register("plugin.admingroups.previousProvider",
        SystemProperty.Builder.ofType(String.class).setDefaultValue(""));

    private AdminGroupsProperties() {
    }

    /**
     * Registers a dynamic property under this plugin, or adopts the one a previous load left in
     * Openfire's registry, so a reload never fails on a duplicate key.
     */
    @SuppressWarnings("unchecked")
    private static <T> SystemProperty<T> register(String key, SystemProperty.Builder<T> builder) {
        try {
            return builder.setKey(key).setPlugin(PLUGIN).setDynamic(true).build();
        } catch (IllegalArgumentException alreadyRegistered) {
            return (SystemProperty<T>) SystemProperty.getProperty(key).orElseThrow(() -> alreadyRegistered);
        }
    }
}
