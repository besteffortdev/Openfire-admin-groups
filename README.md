# Openfire Admin Console Groups

An Openfire plugin that lets the members of chosen user groups sign in to the admin console.
For example, Active Directory's *Domain Admins*. The accounts already marked **Is Administrator**
keep their access; the groups only add to it.

Configure it at **Server > Server Manager > Admin Console Groups**. The page lists the groups, the
accounts each one makes administrators, and everyone who can use the console with the reason.

## Why not core's `GroupBasedAdminProvider`

Openfire 5 ships `org.jivesoftware.openfire.admin.GroupBasedAdminProvider`, enabled by setting
`provider.admin.className`. It falls short in five ways, each checked in the 5.1.2 bytecode:

| Core `GroupBasedAdminProvider` | This plugin |
|---|---|
| One group (`provider.group.groupBasedAdminProvider.groupName`). | Any number of groups. |
| *Replaces* the admin list: existing administrators who are not in the group are locked out. | Adds to the admin list, which stays editable. |
| Read-only: the **Is Administrator** checkbox is greyed out. Deleting a user who is an administrator makes core call `setAdmins`, which throws. | Writes go to the wrapped provider; a read-only one is skipped instead of throwing. |
| `AdminManager` caches the list once; group changes count only after a restart. | Rebuilt on every group event, setting change, and every 60 s. Each rebuild reads past the group cache, so LDAP changes are seen too. |
| Counts `Group.getMembers()` only, so a group's own administrators are left out. Remote JIDs in the group are accepted. | Counts members and group administrators, local accounts only. |

## Behaviour

- **Revocation:** removing someone from a group ends their access at the next rebuild. Changes made
  in Openfire count at once; changes made in LDAP count within `refreshSeconds`. `AuthCheckFilter`
  calls `AdminManager.isUserAdmin` on every request, so an open session ends at its next page load.
- **Self-lockout guard:** the page refuses to remove the group that is the signed-in
  administrator's only source of access.
- **Renamed or deleted groups:** a rename in Openfire is carried into the setting. A deleted group
  is dropped, so a new group with the same name is not trusted by accident.
- **Failure isolation:** a group that cannot be read (not found, LDAP error) grants nobody. The
  other groups and the explicit administrators are unaffected. It is logged once when it starts
  failing and once when it recovers.
- **Audit trail:**
  - every account that gains or loses administrator rights through a group is logged at INFO;
  - adding or removing a group on the page is recorded in Openfire's security audit log.
- **Scope:** group-granted administrators count everywhere Openfire calls `isUserAdmin`, including
  the XEP-0133 service administration commands.

## Declarative config (openfire.xml)

The groups can be declared in an `<admingroups>` block in Openfire's own `conf/openfire.xml`, so a
server can be deployed already configured, as with the sister plugins' `<federation>` and
`<samessage>` blocks. The block is read when the plugin starts, before the first admin list is
built, and again when **Read the configuration file** is pressed on the admin page.

```xml
<jive>
  <admingroups refreshSeconds="60">
    <groups>
      <group name="Domain Admins"/>
      <group name="Openfire Operators"/>
    </groups>
  </admingroups>
</jive>
```

- **Read, never written.** What the file declares goes into the ordinary settings. It shows on the
  admin page and can be changed there, until the file is read again.
- **`<groups>` replaces the list.** A `<groups>` element replaces the whole group list, the way
  SAMessage's `visibleTo` group list does, so taking a group out of the file revokes it at the next
  read. An empty `<groups/>` clears the list. A block with no `<groups>` leaves the list alone, for
  example one that only sets `refreshSeconds`. The admin page says when the list comes from the file.
- **Spelling and missing groups.** Each name is stored in the directory's own spelling, so
  `domain admins` becomes `Domain Admins` and Openfire's group events match it. A group that is not
  found is still kept, with a warning: at start-up LDAP may simply be unreachable, and dropping the
  group would lock its members out. It makes nobody an administrator until it can be read.
- **Warnings.** Anything unusable is skipped, listed on the admin page under *Warnings*, and
  written to `openfire.log`: a name containing a comma, a missing `name`, or a `refreshSeconds` that
  is not a number or is out of range. It never stops the rest of the block.
- **Lockout guard.** The button refuses to apply a group list that would end the access of the
  administrator pressing it. Nothing is applied in that case. At start-up there is nobody to lock
  out, and the file is applied as written.
- **Hardened parser.** The file is parsed with DTDs and external entities refused.

## Settings

| System property | Default | Meaning |
|---|---|---|
| `plugin.admingroups.groups` | *empty* | Comma-separated group names. The page and the `<groups>` element of openfire.xml edit it. A group whose name contains a comma cannot be used. |
| `plugin.admingroups.refreshSeconds` | `60` | Seconds between re-reads of the groups (10–86400). |
| `plugin.admingroups.previousProvider` | *empty* | Written by the plugin: `provider.admin.className` as it was before. Do not edit. |

## How it works

- **Wrapping.** While the plugin is loaded, `provider.admin.className` names `GroupAdminProvider`.
  That provider forwards to the provider that was in use before, and adds the groups' members.
  Unloading the plugin puts the previous value back, deleting the property if it was unset, and
  rebuilds Openfire's cached list.
- **Why the class can be loaded at all.** Openfire resolves class-typed properties with
  `ClassUtils.forName`, which tries core's loader and then the thread's context class loader. It
  cannot see plugin classes in general. The plugin sets the property inside `initializePlugin`,
  where `PluginManager` has made the plugin's loader the context loader. The property listener
  runs on that same thread, so `AdminManager` finds the class. The plugin then checks that the
  switch really happened, and rolls back if not.
- **Unclean shutdown.** If Openfire stops without unloading the plugin, the property still names
  the plugin's class at the next start. Openfire falls back to its default provider until the
  plugin loads. The plugin then recognises its own class and wraps the provider saved in
  `previousProvider`.
- **Someone else changes the provider.** If `provider.admin.className` is changed by hand while
  the plugin is loaded, the plugin steps aside: group members stop being administrators, and the
  page says so. Reload the plugin to wrap the new provider.
- **Write-back.** `AdminManager.addAdminAccount` and `removeAdminAccount` write the whole cached
  list back through `setAdmins`. The plugin drops the accounts that are on it only through a
  group, so leaving the group really ends their access.

## Build, test, deploy

```bash
mvn -o clean package           # builds target/admingroups.jar and runs the tests (49)
mvn -o -Dopenfire.version=5.1.0 clean package   # re-check against a newer Openfire
./deploy.sh --no-build         # ships it to demo-xmpp and verifies the reload
```

The admin-provider classes this depends on are byte-identical in 5.0.5, 5.1.0 and 5.1.2. Not tested
in a cluster.

## How 0.1.0 was verified

- **Unit tests:** 33, covering:
  - the union and the write-back;
  - failure isolation;
  - local-only members;
  - the unbound-provider guard;
  - rename and delete handling;
  - the group-list format.
- **Live, on demo-xmpp (Openfire 5.1.2, AD users, `HybridGroupProvider`):** throwaway database
  groups held one test account, `demo-user`, which is not on the admin list. The run checked:
  - refused before any group was configured;
  - a membership written straight to the database, with no event (the LDAP case), granted
    access on the next timer refresh, about 56 s later;
  - the page listed every administrator and the reason;
  - the page refused to remove the signed-in user's only granting group;
  - adding a second group on the page worked, an unknown group was refused, and the first group
    could then be removed;
  - a rename through core's group edit page was carried into the setting;
  - removing the member ended the open session at its next page load, and a new sign-in was
    refused;
  - deleting the group removed it from the setting;
  - uninstalling put `provider.admin.className` back to unset with no WARN or ERROR;
  - after a simulated unclean shutdown, the plugin recognised its own leftover class on load and
    recovered.

  `admin.authorizedJIDs` was unchanged throughout.

## How 0.1.1 was verified

0.1.1 adds the `<admingroups>` block of openfire.xml.

- **Unit tests:** 49, all passing. The 13 new parser tests cover:
  - groups and interval;
  - absent vs empty `<groups>`;
  - the directory's spelling;
  - missing groups kept;
  - unusable names skipped;
  - the warning cap;
  - a missing or broken file;
  - a DOCTYPE/external-entity file refused.

  Four tests cover `wouldBeAdmin`, the one lockout rule now shared by the page's remove guard and
  the file button.
- **Live, on demo-xmpp:** the server's real setting, `Domain Admins`, stayed in every declared list,
  so its members never lost access. The run checked:
  - start-up applied the block: 2 settings, with the throwaway group in effect;
  - the page showed the result, and noted that the list comes from the file;
  - the button refused a file list that would have locked out the user pressing it, and applied
    nothing;
  - a block with a comma name, a missing group and `refreshSeconds="5"` applied the list, kept the
    missing group, kept the interval at 45, and showed all three warnings;
  - with the block removed, the button applied nothing and left the list alone.

  Afterwards `plugin.admingroups.groups` was set back to exactly `Domain Admins` and
  `refreshSeconds` unset again. `openfire.xml` matched its saved copy byte for byte (same md5,
  owner, mode and inode).
