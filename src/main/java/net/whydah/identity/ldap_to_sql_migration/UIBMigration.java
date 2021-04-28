package net.whydah.identity.ldap_to_sql_migration;

import net.whydah.identity.dataimport.DatabaseMigrationHelper;
import net.whydah.identity.user.identity.LDAPUserIdentity;
import net.whydah.identity.user.identity.LdapUserIdentityDao;
import net.whydah.identity.user.identity.RDBMSLdapUserIdentityDao;
import net.whydah.identity.user.identity.RDBMSUserIdentity;
import org.apache.commons.dbcp.BasicDataSource;
import org.constretto.ConstrettoBuilder;
import org.constretto.ConstrettoConfiguration;
import org.constretto.model.Resource;

import javax.naming.NamingException;
import java.util.function.Consumer;

public class UIBMigration {

    public static void main(String[] args) {
        final ConstrettoConfiguration config = new ConstrettoBuilder()
                .createPropertiesStore()
                .addResource(Resource.create("classpath:useridentitybackend.properties"))
                .addResource(Resource.create("file:./useridentitybackend_override.properties"))
                .done()
                .getConfiguration();

        String primaryLdapUrl = config.evaluateToString("ldap.primary.url");
        String primaryAdmPrincipal = config.evaluateToString("ldap.primary.admin.principal");
        String primaryAdmCredentials = config.evaluateToString("ldap.primary.admin.credentials");
        String primaryUidAttribute = config.evaluateToString("ldap.primary.uid.attribute");
        String primaryUsernameAttribute = config.evaluateToString("ldap.primary.username.attribute");
        String readonly = config.evaluateToString("ldap.primary.readonly");

        LdapUserIdentityDao ldapUserIdentityDao = new LdapUserIdentityDao(primaryLdapUrl, primaryAdmPrincipal, primaryAdmCredentials, primaryUidAttribute, primaryUsernameAttribute, readonly);

        BasicDataSource dataSource = initBasicDataSource(config);
        DatabaseMigrationHelper dbHelper = new DatabaseMigrationHelper(dataSource);
        dbHelper.cleanDatabase();
        dbHelper.upgradeDatabase();

        RDBMSLdapUserIdentityDao rdbmsLdapUserIdentityDao = new RDBMSLdapUserIdentityDao(dataSource);

        UIBMigration uibMigration = new UIBMigration(ldapUserIdentityDao, rdbmsLdapUserIdentityDao);

        boolean dryRun = false;
        int maxUsersToMigrate = Integer.MAX_VALUE;
        for (int i = 0; i < args.length; i++) {
            if ("--dry-run".equalsIgnoreCase(args[i])) {
                dryRun = true;
            }
            if ("-n".equalsIgnoreCase(args[i])) {
                if ((i + 1) < args.length) {
                    maxUsersToMigrate = Integer.parseInt(args[i + 1]);
                }
            }
        }

        if (dryRun) {
            System.out.printf("Migration (dry-run) started with maxUsers=%d%n", maxUsersToMigrate);
            uibMigration.migrateDryRun(maxUsersToMigrate);
        } else {
            System.out.printf("Migration started with maxUsers=%d%n", maxUsersToMigrate);
            uibMigration.migrate(maxUsersToMigrate);
        }
    }

    static BasicDataSource initBasicDataSource(ConstrettoConfiguration configuration) {
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

    private final LdapUserIdentityDao ldapUserIdentityDao;
    private final RDBMSLdapUserIdentityDao rdbmsLdapUserIdentityDao;

    public UIBMigration(LdapUserIdentityDao ldapUserIdentityDao, RDBMSLdapUserIdentityDao rdbmsLdapUserIdentityDao) {
        this.ldapUserIdentityDao = ldapUserIdentityDao;
        this.rdbmsLdapUserIdentityDao = rdbmsLdapUserIdentityDao;
    }

    public void migrate(int maxUsersToMigrate) {
        doMigrate(maxUsersToMigrate, rdbmsLdapUserIdentityDao::create);
    }

    public void migrateDryRun(int maxUsersToMigrate) {
        doMigrate(maxUsersToMigrate, rdbmsUserIdentity -> {
        });
    }

    private void doMigrate(int maxUsersToMigrate, Consumer<RDBMSUserIdentity> writeCallback) {
        try {
            System.out.printf("MIGRATION LDAP -> SQL%n");
            int i = 0;
            for (LDAPUserIdentity ldapUserIdentity : ldapUserIdentityDao.allUsersWithPassword()) {
                if (i >= maxUsersToMigrate) {
                    break;
                }
                System.out.printf("USER: %s ==::== PASS: '%s'%n", ldapUserIdentity.toString(), ldapUserIdentity.getPassword());
                RDBMSUserIdentity rdbmsUserIdentity = toRDBMSUserIdentity(ldapUserIdentity);
                writeCallback.accept(rdbmsUserIdentity);
                i++;
            }
        } catch (NamingException e) {
            throw new RuntimeException(e);
        }
    }

    public static RDBMSUserIdentity toRDBMSUserIdentity(LDAPUserIdentity a) {
        return new RDBMSUserIdentity(
                a.getUid(),
                a.getUsername(),
                a.getFirstName(),
                a.getLastName(),
                a.getEmail(),
                a.getPassword(),
                a.getCellPhone(),
                a.getPersonRef()
        );
    }
}
