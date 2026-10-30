<%--
    Choose the groups whose members may use the admin console, and see who can and why.
    Element text is escaped with text(), attribute values with attr(): escapeHTMLTags leaves
    quotes alone, so it is not enough inside an attribute.
--%>
<%@ page contentType="text/html;charset=UTF-8" %>
<%@ page import="com.igniterealtime.openfire.plugin.admingroups.AdminGroupsPlugin" %>
<%@ page import="com.igniterealtime.openfire.plugin.admingroups.AdminGroupsFileConfig" %>
<%@ page import="com.igniterealtime.openfire.plugin.admingroups.AdminList" %>
<%@ page import="java.time.Instant" %>
<%@ page import="org.jivesoftware.openfire.XMPPServer" %>
<%@ page import="org.jivesoftware.openfire.user.User" %>
<%@ page import="org.jivesoftware.util.ParamUtils" %>
<%@ page import="org.jivesoftware.util.StringUtils" %>
<%@ page import="org.slf4j.LoggerFactory" %>
<%@ page import="org.xmpp.packet.JID" %>
<%@ page import="java.time.ZoneOffset" %>
<%@ page import="java.time.format.DateTimeFormatter" %>
<%@ page import="java.util.List" %>
<jsp:useBean id="webManager" class="org.jivesoftware.util.WebManager"/>
<% webManager.init(request, response, session, application, out); %>
<%!
    /** How many of a group's administrators the table lists before summarising the rest. */
    static final int SHOWN_PER_GROUP = 50;

    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
        .withZone(ZoneOffset.UTC);

    /** Logs a failed admin action in full and returns the fixed message the page shows instead. */
    static String failure(String action, RuntimeException e) {
        LoggerFactory.getLogger(AdminGroupsPlugin.class).warn("Admin console: could not {}", action, e);
        return "Could not " + action + "; the Openfire log has the details.";
    }

    static String text(String value) {
        return StringUtils.escapeHTMLTags(value == null ? "" : value);
    }

    static String attr(String value) {
        return StringUtils.escapeForXML(value == null ? "" : value);
    }

    static AdminList.GroupResult find(AdminList.Snapshot snapshot, String name) {
        if (snapshot == null) return null;
        for (AdminList.GroupResult result : snapshot.groups()) {
            if (result.name().equals(name)) return result;
        }
        return null;
    }
%>
<%
    AdminGroupsPlugin plugin = AdminGroupsPlugin.getInstance();

    // PluginServlet has already checked the submitted token and minted a new one for this response,
    // so it is rendered into the forms and never compared here.
    String csrf = (String) request.getAttribute("csrf");

    String errorMessage = null;
    String successMessage = null;

    User user = webManager.getUser();
    JID self = user == null ? null : XMPPServer.getInstance().createJID(user.getUsername(), null);

    String action = "POST".equalsIgnoreCase(request.getMethod())
        ? ParamUtils.getStringParameter(request, "action", "") : "";
    String group = ParamUtils.getStringParameter(request, "group", "").trim();

    if (plugin != null && !action.isEmpty()) {
        try {
            switch (action) {
                case "add":
                    plugin.addGroup(group);
                    webManager.logEvent("Admin Console Groups: added group " + group, null);
                    successMessage = "Members of \"" + group + "\" can now use the admin console.";
                    break;
                case "remove":
                    if (self != null && plugin.removalLocksOut(group, self)) {
                        errorMessage = "You are an administrator only through \"" + group + "\", so removing it "
                            + "would lock you out. Remove it while signed in as another administrator.";
                    } else {
                        plugin.removeGroup(group);
                        webManager.logEvent("Admin Console Groups: removed group " + group, null);
                        successMessage = "Members of \"" + group + "\" are no longer administrators through it.";
                    }
                    break;
                case "refresh":
                    plugin.refresh();
                    successMessage = "The groups were read again.";
                    break;
                case "readfile":
                    AdminGroupsFileConfig.Declaration declared = plugin.readConfigFile();
                    if (self != null && declared.groups() != null && plugin.groupsLockOut(declared.groups(), self)) {
                        errorMessage = "The group list in the configuration file does not make you an "
                            + "administrator, so applying it would lock you out. Nothing was applied.";
                        break;
                    }
                    AdminGroupsFileConfig.IngestResult read = plugin.applyConfigFile(declared);
                    if (!read.blockPresent()) {
                        successMessage = "There is no <admingroups> block in "
                            + plugin.getFileConfig().configFile() + ", so nothing was applied.";
                    } else if (read.settingsUpdated() == 0) {
                        successMessage = "The configuration file was read. It already agrees with this "
                            + "server, so nothing changed.";
                    } else {
                        webManager.logEvent("Admin Console Groups: applied the configuration file", null);
                        successMessage = "Applied from the configuration file: " + read.settingsUpdated()
                            + " setting(s).";
                    }
                    break;
                default:
                    break;
            }
        } catch (IllegalArgumentException e) {
            errorMessage = e.getMessage();
        } catch (RuntimeException e) {
            errorMessage = failure("change the admin console groups", e);
        }
    }
%>
<html>
<head>
    <title>Admin Console Groups</title>
    <meta name="pageID" content="admin-groups"/>
</head>
<body>

<% if (errorMessage != null) { %>
<div class="jive-error">
    <table cellpadding="0" cellspacing="0" border="0">
        <tr><td class="jive-icon-label"><%= text(errorMessage) %></td></tr>
    </table>
</div><br>
<% } %>
<% if (successMessage != null) { %>
<div class="jive-success">
    <table cellpadding="0" cellspacing="0" border="0">
        <tr><td class="jive-icon-label"><%= text(successMessage) %></td></tr>
    </table>
</div><br>
<% } %>

<% if (plugin == null) { %>
<p>The Admin Console Groups plugin is not running; the Openfire log says why.</p>
<% } else {
    String notInEffect = plugin.notInEffect();
    AdminList.Snapshot snapshot = plugin.getSnapshot();
    List<String> groups = plugin.getGroups();
    AdminGroupsFileConfig fileConfig = plugin.getFileConfig();
    AdminGroupsFileConfig.IngestResult ingest = fileConfig.lastResult();
    Long readAt = fileConfig.lastLoadedAtMillis();
    List<String> suggestions;
    try {
        suggestions = plugin.suggestions();
    } catch (RuntimeException e) {
        LoggerFactory.getLogger(AdminGroupsPlugin.class).warn("Admin console: could not list the groups", e);
        suggestions = List.of();
    }
%>

<p>
    Members of the groups below can sign in to this admin console, as well as the accounts marked
    <b>Is Administrator</b> on their user page. A group's own administrators count as members. Only
    accounts on this server are made administrators.
</p>

<% if (notInEffect != null) { %>
<div class="jive-error">
    <table cellpadding="0" cellspacing="0" border="0">
        <tr><td class="jive-icon-label">Group members are not administrators right now: <%= text(notInEffect) %></td></tr>
    </table>
</div><br>
<% } %>

<div class="jive-contentBoxHeader">Groups</div>
<div class="jive-contentBox">
    <% if (ingest.groupsDeclared()) { %>
    <p class="jive-description">
        This list is declared in the configuration file (see below). Changes made here last until
        the file is read again: at the next start, or with <b>Read the configuration file</b>.
    </p>
    <% } %>
    <div class="jive-table">
        <table cellpadding="0" cellspacing="0" border="0" width="100%">
            <thead>
            <tr><th nowrap>Group</th><th nowrap>Administrators through it</th><th width="1%">&nbsp;</th></tr>
            </thead>
            <tbody>
            <% if (groups.isEmpty()) { %>
            <tr><td colspan="3">No groups yet, so only the accounts marked as administrators can use the console.</td></tr>
            <% } %>
            <% for (String name : groups) {
                AdminList.GroupResult result = find(snapshot, name); %>
            <tr>
                <td nowrap><%= text(name) %></td>
                <td>
                    <% if (result == null) { %>
                    Not read yet.
                    <% } else if (!result.found()) { %>
                    <b>Cannot be read</b>, so it makes nobody an administrator. The Openfire log says why.
                    <% } else if (result.admins().isEmpty()) { %>
                    Nobody: the group has no accounts on this server.
                    <% } else {
                        List<JID> admins = result.admins();
                        for (int i = 0; i < Math.min(admins.size(), SHOWN_PER_GROUP); i++) { %>
                    <%= i > 0 ? ", " : "" %><%= text(admins.get(i).toBareJID()) %>
                    <%  }
                        if (admins.size() > SHOWN_PER_GROUP) { %>
                    and <%= admins.size() - SHOWN_PER_GROUP %> more
                    <%  }
                    } %>
                    <% if (result != null && result.ignored() > 0) { %>
                    <br><span class="jive-description"><%= result.ignored() %> member(s) from other servers ignored.</span>
                    <% } %>
                </td>
                <td nowrap>
                    <form action="admin-groups.jsp" method="post" style="margin: 0">
                        <input type="hidden" name="csrf" value="<%= attr(csrf) %>">
                        <input type="hidden" name="action" value="remove">
                        <input type="hidden" name="group" value="<%= attr(name) %>">
                        <input type="submit" value="Remove">
                    </form>
                </td>
            </tr>
            <% } %>
            </tbody>
        </table>
    </div>
    <br>
    <form action="admin-groups.jsp" method="post">
        <input type="hidden" name="csrf" value="<%= attr(csrf) %>">
        <input type="hidden" name="action" value="add">
        <label for="group">Add a group:</label>
        <input type="text" name="group" id="group" size="40" list="known-groups" autocomplete="off">
        <datalist id="known-groups">
            <% for (String name : suggestions) { %>
            <option value="<%= attr(name) %>">
            <% } %>
        </datalist>
        <input type="submit" value="Add">
    </form>
</div>

<br>
<div class="jive-contentBoxHeader">Who can use the admin console</div>
<div class="jive-contentBox">
    <div class="jive-table">
        <table cellpadding="0" cellspacing="0" border="0" width="100%">
            <thead>
            <tr><th nowrap>Account</th><th nowrap>Because of</th></tr>
            </thead>
            <tbody>
            <% if (snapshot == null) { %>
            <tr><td colspan="2">Not read yet.</td></tr>
            <% } else {
                for (JID jid : snapshot.all()) { %>
            <tr>
                <td nowrap><%= text(jid.toBareJID()) %></td>
                <td><%= text(String.join(", ", snapshot.grantsOf(jid))) %></td>
            </tr>
            <%  }
            } %>
            </tbody>
        </table>
    </div>
    <p class="jive-description">
        "admin list" is the administrators of <code><%= text(plugin.getWrappedProviderName()) %></code>,
        which the plugin keeps. Groups are read again after every change made in Openfire, and every
        <%= plugin.getRefreshSeconds() %> seconds (<code>plugin.admingroups.refreshSeconds</code>) to
        pick up changes made in LDAP.
        <% if (snapshot != null) { %>Last read <%= WHEN.format(snapshot.takenAt()) %>.<% } %>
    </p>
    <form action="admin-groups.jsp" method="post">
        <input type="hidden" name="csrf" value="<%= attr(csrf) %>">
        <input type="hidden" name="action" value="refresh">
        <input type="submit" value="Read the groups again now">
    </form>
</div>

<br>
<div class="jive-contentBoxHeader">Configuration file</div>
<div class="jive-contentBox">
    <p>
        The groups can also be declared in an <code>&lt;admingroups&gt;</code> block in
        <code><%= text(fileConfig.configFile().toString()) %></code>, so a server can arrive already
        configured. It is read at start-up and with the button below. A <code>&lt;groups&gt;</code>
        element there replaces the list above; settings the block does not mention are left alone.
    </p>
    <table cellpadding="3" cellspacing="0" border="0" width="100%">
        <tr>
            <td width="20%" nowrap><b>Last read</b></td>
            <td>
                <%= readAt == null ? "Never" : WHEN.format(Instant.ofEpochMilli(readAt)) %>
                <% if (readAt != null && !ingest.blockPresent()) { %>
                <span class="jive-description">&nbsp;&mdash; no <code>&lt;admingroups&gt;</code> block found</span>
                <% } %>
            </td>
        </tr>
        <% if (ingest.blockPresent()) { %>
        <tr>
            <td nowrap><b>Applied</b></td>
            <td>
                <%= ingest.settingsUpdated() %> setting(s)<%= ingest.groupsDeclared() ? "; the file declares the group list" : "" %>
                <% if (ingest.settingsUpdated() == 0) { %>
                <span class="jive-description">&nbsp;&mdash; the file already agreed with this server</span>
                <% } %>
            </td>
        </tr>
        <% } %>
        <% if (!ingest.warnings().isEmpty()) { %>
        <tr>
            <td nowrap valign="top"><b>Warnings</b></td>
            <td>
                <ul style="margin:0; padding-left:1.2em">
                    <% for (String warning : ingest.warnings()) { %>
                    <li><span style="color:#a00"><%= text(warning) %></span></li>
                    <% } %>
                </ul>
            </td>
        </tr>
        <% } %>
    </table>
    <br>
    <form action="admin-groups.jsp" method="post">
        <input type="hidden" name="csrf" value="<%= attr(csrf) %>">
        <input type="hidden" name="action" value="readfile">
        <input type="submit" value="Read the configuration file">
    </form>
</div>

<% } %>

</body>
</html>
