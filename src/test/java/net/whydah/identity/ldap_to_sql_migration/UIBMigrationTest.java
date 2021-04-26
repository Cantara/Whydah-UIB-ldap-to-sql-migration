package net.whydah.identity.ldap_to_sql_migration;

import net.whydah.identity.Main;
import net.whydah.identity.config.ApplicationMode;
import net.whydah.identity.ldapserver.EmbeddedADS;
import net.whydah.identity.user.identity.LdapAuthenticator;
import net.whydah.identity.user.identity.LdapUserIdentityDao;
import net.whydah.identity.util.FileUtils;
import org.constretto.ConstrettoBuilder;
import org.constretto.ConstrettoConfiguration;
import org.constretto.model.Resource;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Map;

public class UIBMigrationTest {
    private static final String ldapPath = "target/LdapUIBUserIdentityDaoTest/ldap";
    private static Main main = null;
    private static LdapUserIdentityDao ldapUserIdentityDao;
    private static LdapAuthenticator ldapAuthenticator;


    @BeforeClass
    public static void setUp() {
        FileUtils.deleteDirectory(new File(ldapPath));

        ApplicationMode.setCIMode();
        final ConstrettoConfiguration config = new ConstrettoBuilder()
                .createPropertiesStore()
                .addResource(Resource.create("classpath:useridentitybackend.properties"))
                .addResource(Resource.create("classpath:useridentitybackend-test.properties"))
                .done()
                .getConfiguration();


        Map<String, String> ldapProperties = Main.ldapProperties(config);
        ldapProperties.put("ldap.embedded.directory", ldapPath);
        ldapProperties.put(EmbeddedADS.PROPERTY_BIND_PORT, "10589");
        String primaryLdapUrl = "ldap://localhost:10589/dc=people,dc=whydah,dc=no";
        ldapProperties.put("ldap.primary.url", primaryLdapUrl);
        FileUtils.deleteDirectories(ldapPath);

        main = new Main(6651); // web-server and application-context never started
        main.startEmbeddedDS(ldapProperties); // need embedded ldap server to run, used by dao

        String primaryAdmPrincipal = config.evaluateToString("ldap.primary.admin.principal");
        String primaryAdmCredentials = config.evaluateToString("ldap.primary.admin.credentials");
        String primaryUidAttribute = config.evaluateToString("ldap.primary.uid.attribute");
        String primaryUsernameAttribute = config.evaluateToString("ldap.primary.username.attribute");
        String readonly = config.evaluateToString("ldap.primary.readonly");

        ldapUserIdentityDao = new LdapUserIdentityDao(primaryLdapUrl, primaryAdmPrincipal, primaryAdmCredentials, primaryUidAttribute, primaryUsernameAttribute, readonly);
        ldapAuthenticator = new LdapAuthenticator(primaryLdapUrl, primaryAdmPrincipal, primaryAdmCredentials, primaryUidAttribute, primaryUsernameAttribute);
    }


    @AfterClass
    public static void stop() {
        if (main != null) {
            main.stopEmbeddedDS();
        }
    }


    @Test
    public void thatMigrationCopiesAllUsersFromLdapToSql() {
        // TODO setup test data in ldap.

        // TODO perform migration to SQL
        new UIBMigration();

        // TODO verify that users are copied to SQL properly
    }
}