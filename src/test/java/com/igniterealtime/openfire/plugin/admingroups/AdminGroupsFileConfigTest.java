package com.igniterealtime.openfire.plugin.admingroups;

import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.jivesoftware.openfire.group.GroupNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminGroupsFileConfigTest {

    /** The directory's own spellings; "broken" stands for a provider that fails. */
    private static final Map<String, String> DIRECTORY = Map.of(
        "domain admins", "Domain Admins",
        "ops", "ops");

    private final AdminGroupsFileConfig config = new AdminGroupsFileConfig(name -> {
        if (name.equals("broken")) throw new IllegalStateException("LDAP is down");
        String spelled = DIRECTORY.get(name.toLowerCase());
        if (spelled == null) throw new GroupNotFoundException(name);
        return spelled;
    });

    private AdminGroupsFileConfig.Declaration parse(String block) throws DocumentException {
        Element root = DocumentHelper.parseText("<jive>" + block + "</jive>").getRootElement();
        return config.parse(root.element(AdminGroupsFileConfig.CONFIG_ELEMENT));
    }

    @Test
    void declaredGroupsAndIntervalAreRead() throws DocumentException {
        AdminGroupsFileConfig.Declaration declared = parse(
            "<admingroups refreshSeconds='120'><groups>"
                + "<group name='Domain Admins'/><group name='ops'/>"
                + "</groups></admingroups>");

        assertTrue(declared.blockPresent());
        assertEquals(List.of("Domain Admins", "ops"), declared.groups());
        assertEquals(120, declared.refreshSeconds());
        assertEquals(List.of(), declared.warnings());
    }

    @Test
    void withoutAGroupsElementTheListIsLeftAlone() throws DocumentException {
        AdminGroupsFileConfig.Declaration declared = parse("<admingroups refreshSeconds='30'/>");
        assertNull(declared.groups());
    }

    @Test
    void anEmptyGroupsElementClearsTheList() throws DocumentException {
        assertEquals(List.of(), parse("<admingroups><groups/></admingroups>").groups());
    }

    @Test
    void namesTakeTheDirectorysSpellingSoGroupEventsMatch() throws DocumentException {
        AdminGroupsFileConfig.Declaration declared = parse(
            "<admingroups><groups><group name=' domain admins '/></groups></admingroups>");
        assertEquals(List.of("Domain Admins"), declared.groups());
    }

    @Test
    void aGroupThatIsNotFoundIsKeptBecauseLdapMayBeDownAtStartUp() throws DocumentException {
        AdminGroupsFileConfig.Declaration declared = parse(
            "<admingroups><groups><group name='Later'/><group name='broken'/></groups></admingroups>");

        assertEquals(List.of("Later", "broken"), declared.groups());
        assertEquals(2, declared.warnings().size());
    }

    @Test
    void unusableNamesAreSkippedWithAWarning() throws DocumentException {
        AdminGroupsFileConfig.Declaration declared = parse(
            "<admingroups><groups>"
                + "<group name='a,b'/><group name=' '/><group/><group name='ops'/>"
                + "</groups></admingroups>");

        assertEquals(List.of("ops"), declared.groups());
        assertEquals(3, declared.warnings().size());
    }

    @Test
    void repeatsCollapseIncludingThoseThatDifferOnlyInSpelling() throws DocumentException {
        AdminGroupsFileConfig.Declaration declared = parse(
            "<admingroups><groups>"
                + "<group name='Domain Admins'/><group name='DOMAIN ADMINS'/>"
                + "</groups></admingroups>");
        assertEquals(List.of("Domain Admins"), declared.groups());
    }

    @Test
    void aBadIntervalIsIgnoredWithAWarning() throws DocumentException {
        AdminGroupsFileConfig.Declaration declared = parse("<admingroups refreshSeconds='soon'/>");
        assertNull(declared.refreshSeconds());
        assertEquals(1, declared.warnings().size());
    }

    @Test
    void warningsAreCapped() throws DocumentException {
        String groups = "<group name='x,y'/>".repeat(50);
        AdminGroupsFileConfig.Declaration declared = parse("<admingroups><groups>" + groups + "</groups></admingroups>");
        assertEquals(20, declared.warnings().size());
    }

    @Test
    void aFileWithTheBlockIsRead(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("openfire.xml"),
            "<?xml version='1.0' encoding='UTF-8'?><jive><setup>true</setup>"
                + "<admingroups><groups><group name='ops'/></groups></admingroups></jive>");

        assertEquals(List.of("ops"), config.read(file).groups());
    }

    @Test
    void aFileWithoutTheBlockDeclaresNothing(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("openfire.xml"), "<jive><setup>true</setup></jive>");
        assertFalse(config.read(file).blockPresent());
    }

    @Test
    void aMissingOrBrokenFileDeclaresNothing(@TempDir Path dir) throws IOException {
        assertFalse(config.read(dir.resolve("absent.xml")).blockPresent());
        Path broken = Files.writeString(dir.resolve("broken.xml"), "<jive><admingroups>");
        assertFalse(config.read(broken).blockPresent());
    }

    @Test
    void entitiesAreRefusedSoTheFileCannotPullInOtherFiles(@TempDir Path dir) throws IOException {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "ops");
        Path file = Files.writeString(dir.resolve("openfire.xml"),
            "<?xml version='1.0'?><!DOCTYPE jive [<!ENTITY s SYSTEM '" + secret.toUri() + "'>]>"
                + "<jive><admingroups><groups><group name='&s;'/></groups></admingroups></jive>");

        assertFalse(config.read(file).blockPresent());
    }
}
