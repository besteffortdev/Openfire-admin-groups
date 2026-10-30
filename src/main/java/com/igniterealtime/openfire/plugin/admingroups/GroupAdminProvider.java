package com.igniterealtime.openfire.plugin.admingroups;

import org.jivesoftware.openfire.admin.AdminProvider;
import org.xmpp.packet.JID;

import java.util.List;

/**
 * The {@link AdminProvider} Openfire uses while the plugin is loaded. Openfire creates providers
 * from a class name, so this class only forwards to the {@link AdminList} the plugin hands it
 * through {@link #bind}.
 */
public class GroupAdminProvider implements AdminProvider {

    private static volatile AdminList bound;

    /** Called by Openfire's {@code AdminManager}, which creates providers by reflection. */
    public GroupAdminProvider() {
    }

    static void bind(AdminList adminList) {
        bound = adminList;
    }

    static void unbind() {
        bound = null;
    }

    @Override
    public List<JID> getAdmins() {
        return current().admins();
    }

    @Override
    public void setAdmins(List<JID> admins) {
        current().setAdmins(admins);
    }

    @Override
    public boolean isReadOnly() {
        return current().isReadOnly();
    }

    private static AdminList current() {
        AdminList adminList = bound;
        // Failing is safer than answering with an empty list, which Openfire reads as "let the
        // account named admin in".
        if (adminList == null) throw new IllegalStateException("The Admin Console Groups plugin is not loaded");
        return adminList;
    }
}
