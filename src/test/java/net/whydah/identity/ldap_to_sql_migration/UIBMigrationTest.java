package net.whydah.identity.ldap_to_sql_migration;

import net.whydah.identity.Main;
import net.whydah.identity.config.ApplicationMode;
import net.whydah.identity.dataimport.DatabaseMigrationHelper;
import net.whydah.identity.dataimport.WhydahUserIdentityImporter;
import net.whydah.identity.ldapserver.EmbeddedADS;
import net.whydah.identity.user.identity.LDAPUserIdentity;
import net.whydah.identity.user.identity.LdapUserIdentityDao;
import net.whydah.identity.user.identity.RDBMSLdapUserIdentityDao;
import net.whydah.identity.user.identity.RDBMSUserIdentity;
import net.whydah.identity.util.FileUtils;
import org.apache.commons.dbcp.BasicDataSource;
import org.constretto.ConstrettoBuilder;
import org.constretto.ConstrettoConfiguration;
import org.constretto.model.Resource;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.naming.NamingException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static net.whydah.identity.ldap_to_sql_migration.UIBMigration.toRDBMSUserIdentity;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class UIBMigrationTest {

    private static final String ldapPath = "target/LdapUIBUserIdentityDaoTest/ldap";
    private static Main main = null;

    private static ConstrettoConfiguration configuration;
    private static LdapUserIdentityDao ldapUserIdentityDao;
    private static RDBMSLdapUserIdentityDao rdbmsLdapUserIdentityDao;

    @BeforeClass
    public static void setUp() throws IOException {
        FileUtils.deleteDirectory(new File(ldapPath));

        ApplicationMode.setCIMode();
        configuration = new ConstrettoBuilder()
                .createPropertiesStore()
                .addResource(Resource.create("classpath:useridentitybackend.properties"))
                .addResource(Resource.create("classpath:useridentitybackend-test.properties"))
                .done()
                .getConfiguration();

        Map<String, String> ldapProperties = Main.ldapProperties(configuration);
        ldapProperties.put("ldap.embedded.directory", ldapPath);
        ldapProperties.put(EmbeddedADS.PROPERTY_BIND_PORT, "10589");
        String primaryLdapUrl = "ldap://localhost:10589/dc=people,dc=whydah,dc=no";
        ldapProperties.put("ldap.primary.url", primaryLdapUrl);
        FileUtils.deleteDirectories(ldapPath);

        main = new Main(6651); // web-server and application-context never started
        main.startEmbeddedDS(ldapProperties); // need embedded ldap server to run, used by dao

        String primaryAdmPrincipal = configuration.evaluateToString("ldap.primary.admin.principal");
        String primaryAdmCredentials = configuration.evaluateToString("ldap.primary.admin.credentials");
        String primaryUidAttribute = configuration.evaluateToString("ldap.primary.uid.attribute");
        String primaryUsernameAttribute = configuration.evaluateToString("ldap.primary.username.attribute");
        String readonly = configuration.evaluateToString("ldap.primary.readonly");

        ldapUserIdentityDao = new LdapUserIdentityDao(primaryLdapUrl, primaryAdmPrincipal, primaryAdmCredentials, primaryUidAttribute, primaryUsernameAttribute, readonly);

        BasicDataSource dataSource = initBasicDataSource(configuration);
        DatabaseMigrationHelper dbHelper = new DatabaseMigrationHelper(dataSource);
        dbHelper.cleanDatabase();
        dbHelper.upgradeDatabase();

        rdbmsLdapUserIdentityDao = new RDBMSLdapUserIdentityDao(dataSource);
    }

    private static BasicDataSource initBasicDataSource(ConstrettoConfiguration configuration) {
        String jdbcdriver = configuration.evaluateToString("roledb.jdbc.driver");
        String jdbcurl = configuration.evaluateToString("roledb.jdbc.url");
        String roledbuser = configuration.evaluateToString("roledb.jdbc.user");
        String roledbpasswd = configuration.evaluateToString("roledb.jdbc.password");

        BasicDataSource dataSource = new BasicDataSource();
        dataSource.setDriverClassName(jdbcdriver);
        dataSource.setUrl(jdbcurl);
        dataSource.setUsername(roledbuser);
        dataSource.setPassword(roledbpasswd);
        return dataSource;
    }

    @AfterClass
    public static void stop() {
        if (main != null) {
            main.stopEmbeddedDS();
        }
    }

    @Test
    public void thatMigrationCopiesAllUsersFromLdapToSql() throws NamingException {
        InputStream userImportStream = FileUtils.openFileOnClasspath("users.csv");
        List<LDAPUserIdentity> users = WhydahUserIdentityImporter.parseUsers(userImportStream);
        for (LDAPUserIdentity ldapUserIdentity : users) {
            assertTrue(ldapUserIdentityDao.addUserIdentity(ldapUserIdentity));
        }
        Map<String, LDAPUserIdentity> ldapUserByUid = StreamSupport
                .stream(Spliterators.spliteratorUnknownSize(ldapUserIdentityDao.allUsersWithPassword().iterator(), Spliterator.ORDERED), false)
                .collect(Collectors.toMap(LDAPUserIdentity::getUid, i -> i));

        UIBMigration uibMigration = new UIBMigration(ldapUserIdentityDao, rdbmsLdapUserIdentityDao);

        //uibMigration.migrateDryRun();
        uibMigration.migrate();

        System.out.printf("USERS IN SQL AFTER MIGRATION:%n");
        List<RDBMSUserIdentity> rdbmsUserIdentities = rdbmsLdapUserIdentityDao.allUsersList();
        assertEquals(ldapUserByUid.size(), rdbmsUserIdentities.size());
        for (RDBMSUserIdentity rdbmsUserIdentity : rdbmsUserIdentities) {
            System.out.printf("USER: %s ==::== PASS: '%s'%n", rdbmsUserIdentity.toString(), rdbmsUserIdentity.getPassword());
            LDAPUserIdentity ldapUserIdentity = ldapUserByUid.get(rdbmsUserIdentity.getUid());
            assertNotNull(ldapUserIdentity);
            RDBMSUserIdentity copy = toRDBMSUserIdentity(ldapUserIdentity);
            assertEquals(copy, rdbmsUserIdentity);
        }
    }

}