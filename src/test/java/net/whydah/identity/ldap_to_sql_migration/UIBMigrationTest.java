package net.whydah.identity.ldap_to_sql_migration;

import net.whydah.identity.Main;
import net.whydah.identity.config.ApplicationMode;
import net.whydah.identity.dataimport.DatabaseMigrationHelper;
import net.whydah.identity.dataimport.WhydahUserIdentityImporter;
import net.whydah.identity.ldapserver.EmbeddedADS;
import net.whydah.identity.user.identity.BCryptService;
import net.whydah.identity.user.identity.LDAPUserIdentity;
import net.whydah.identity.user.identity.LdapUserIdentityDao;
import net.whydah.identity.user.identity.RDBMSLdapUserIdentityDao;
import net.whydah.identity.user.identity.RDBMSLdapUserIdentityRepository;
import net.whydah.identity.user.identity.RDBMSUserIdentity;
import net.whydah.identity.user.identity.UserIdentityConverter;
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
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class UIBMigrationTest {

    static final String ldapPath = "target/LdapUIBUserIdentityDaoTest/ldap";
    static Main main = null;

    static ConstrettoConfiguration configuration;
    static LdapUserIdentityDao ldapUserIdentityDao;
    static RDBMSLdapUserIdentityDao rdbmsLdapUserIdentityDao;
    static BCryptService bCryptService;
    static RDBMSLdapUserIdentityRepository rdbmsLdapUserIdentityRepository;
    static UserIdentityConverter converter;

    @BeforeClass
    public static void setUp() {
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

        BasicDataSource dataSource = UIBMigration.initBasicDataSource(configuration);
        DatabaseMigrationHelper dbHelper = new DatabaseMigrationHelper(dataSource);
        dbHelper.cleanDatabase();
        dbHelper.upgradeDatabase();

        rdbmsLdapUserIdentityDao = new RDBMSLdapUserIdentityDao(dataSource);
        bCryptService = new BCryptService(configuration.evaluateToString("userdb.password.pepper"), configuration.evaluateToInt("userdb.password.bcrypt.preferredcost"));
        rdbmsLdapUserIdentityRepository = new RDBMSLdapUserIdentityRepository(rdbmsLdapUserIdentityDao, bCryptService, configuration);

        converter = new UserIdentityConverter(bCryptService);
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

        UIBMigration uibMigration = new UIBMigration(ldapUserIdentityDao, rdbmsLdapUserIdentityDao, bCryptService, false, Integer.MAX_VALUE, true);

        //uibMigration.migrateDryRun();
        uibMigration.migrate();
        uibMigration.migrate();

        System.out.printf("USERS IN SQL AFTER MIGRATION:%n");
        List<RDBMSUserIdentity> rdbmsUserIdentities = rdbmsLdapUserIdentityDao.allUsersList();
        assertEquals(ldapUserByUid.size(), rdbmsUserIdentities.size());
        for (RDBMSUserIdentity rdbmsUserIdentity : rdbmsUserIdentities) {
            String password = rdbmsUserIdentity.getPasswordBCrypt();
            if (password == null) {
                password = rdbmsUserIdentity.getPassword(); // not bcrypt, probably plaintext
            }
            System.out.printf("USER: %s ==::== PASS: '%s'%n", rdbmsUserIdentity, password);
            LDAPUserIdentity ldapUserIdentity = ldapUserByUid.get(rdbmsUserIdentity.getUid());
            assertNotNull(ldapUserIdentity);
            RDBMSUserIdentity copy = converter.convertFromLDAPUserIdentity(ldapUserIdentity);
            assertEquals(copy, rdbmsUserIdentity);
            RDBMSUserIdentity authenticatedUser = rdbmsLdapUserIdentityRepository.authenticate(ldapUserIdentity.getUsername(), ldapUserIdentity.getPassword());
            assertNotNull(authenticatedUser);
            assertEquals(rdbmsUserIdentity, authenticatedUser);
        }
    }

}