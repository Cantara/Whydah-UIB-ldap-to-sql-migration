package net.whydah.identity.ldap_to_sql_migration;

import net.whydah.identity.user.identity.BCryptService;
import net.whydah.identity.user.identity.LDAPUserIdentity;
import net.whydah.identity.user.identity.LdapUserIdentityDao;
import net.whydah.identity.user.identity.RDBMSLdapUserIdentityDao;
import net.whydah.identity.user.identity.RDBMSUserIdentity;
import net.whydah.identity.user.identity.UserIdentityConverter;
import org.apache.commons.dbcp.BasicDataSource;
import org.constretto.ConstrettoBuilder;
import org.constretto.ConstrettoConfiguration;
import org.constretto.model.Resource;

import javax.naming.NamingException;

public class UIBMigration {

    static String usage() {
        return "Usage: java -jar uib-ldap-to-sql-migration.jar [--dry-run] [--print-passwords] [-u <usernameOrUid>] [-n <maxUsersToMigrate>]";
    }

    public static void main(String[] args) {
        boolean dryRun = false;
        int maxUsersToMigrate = Integer.MAX_VALUE;
        boolean printPasswords = false;
        String specificUser = null;
        for (int i = 0; i < args.length; i++) {
            if ("-h".equalsIgnoreCase(args[i]) || "--help".equalsIgnoreCase(args[i])) {
                System.out.printf("%s%n", usage());
                return;
            }
            if ("--dry-run".equalsIgnoreCase(args[i])) {
                dryRun = true;
            }
            if ("-n".equalsIgnoreCase(args[i])) {
                if ((i + 1) < args.length) {
                    maxUsersToMigrate = Integer.parseInt(args[i + 1]);
                    i++;
                } else {
                    System.out.printf("%s%n", usage());
                    return;
                }
            }
            if ("--print-passwords".equalsIgnoreCase(args[i])) {
                printPasswords = true;
            }
            if ("-u".equalsIgnoreCase(args[i])) {
                if ((i + 1) < args.length) {
                    specificUser = args[i + 1];
                    i++;
                } else {
                    System.out.printf("%s%n", usage());
                    return;
                }
            }
        }

        System.out.printf("UIB LDAP -> SQL migration started with options: dry-run=%s, maxUsers=%d, print-passwords=%s, specificUser=%s%n", dryRun, maxUsersToMigrate, printPasswords, specificUser);

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

        RDBMSLdapUserIdentityDao rdbmsLdapUserIdentityDao = null;
        if (!dryRun) {
            BasicDataSource dataSource = initBasicDataSource(config);
            rdbmsLdapUserIdentityDao = new RDBMSLdapUserIdentityDao(dataSource);
        }

        BCryptService bCryptService = new BCryptService(config.evaluateToString("userdb.password.pepper"), config.evaluateToInt("userdb.password.bcrypt.preferredcost"));

        UIBMigration uibMigration = new UIBMigration(ldapUserIdentityDao, rdbmsLdapUserIdentityDao, bCryptService, dryRun, maxUsersToMigrate, printPasswords);

        // run LDAP -> SQL migration
        if (specificUser != null) {
            uibMigration.migrateUser(specificUser);
        } else {
            uibMigration.migrate();
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
    private final BCryptService bCryptService;
    private final UserIdentityConverter converter;

    final boolean dryRun;
    final int maxUsersToMigrate;
    final boolean printPasswords;

    public UIBMigration(LdapUserIdentityDao ldapUserIdentityDao, RDBMSLdapUserIdentityDao rdbmsLdapUserIdentityDao, BCryptService bCryptService, boolean dryRun, int maxUsersToMigrate, boolean printPasswords) {
        this.ldapUserIdentityDao = ldapUserIdentityDao;
        this.rdbmsLdapUserIdentityDao = rdbmsLdapUserIdentityDao;
        this.bCryptService = bCryptService;
        this.converter = new UserIdentityConverter(bCryptService);
        this.dryRun = dryRun;
        this.maxUsersToMigrate = maxUsersToMigrate;
        this.printPasswords = printPasswords;
    }

    public void migrate() {
        try {
            System.out.printf("MIGRATION LDAP -> SQL%n");
            int i = 0;
            for (LDAPUserIdentity ldapUserIdentity : ldapUserIdentityDao.allUsersWithPassword()) {
                if (i >= maxUsersToMigrate) {
                    break;
                }
                if (printPasswords) {
                    if (ldapUserIdentity.getHashedPassword() != null) {
                        System.out.printf("USER: %s ==::== PASS: '%s'%n", ldapUserIdentity, ldapUserIdentity.getHashedPassword());
                    } else {
                        System.out.printf("USER: %s ==::== PASS: '%s'%n", ldapUserIdentity, ldapUserIdentity.getPassword());
                    }
                } else {
                    System.out.printf("USER: %s%n", ldapUserIdentity);
                }
                if (!dryRun) {
                    RDBMSUserIdentity rdbmsUserIdentity = converter.convertFromLDAPUserIdentity(ldapUserIdentity);
                    rdbmsLdapUserIdentityDao.create(rdbmsUserIdentity);
                }
                i++;
            }
        } catch (NamingException e) {
            throw new RuntimeException(e);
        }
    }

    public void migrateUser(String user) {
        try {
            System.out.printf("MIGRATION LDAP -> SQL of user: %s%n", user);
            LDAPUserIdentity ldapUserIdentity = ldapUserIdentityDao.getUserIndentityWithPassword(user);
            if (printPasswords) {
                if (ldapUserIdentity.getHashedPassword() != null) {
                    System.out.printf("USER: %s ==::== PASS: '%s'%n", ldapUserIdentity, ldapUserIdentity.getHashedPassword());
                } else {
                    System.out.printf("USER: %s ==::== PASS: '%s'%n", ldapUserIdentity, ldapUserIdentity.getPassword());
                }
            } else {
                System.out.printf("USER: %s%n", ldapUserIdentity);
            }
            if (!dryRun) {
                RDBMSUserIdentity rdbmsUserIdentity = converter.convertFromLDAPUserIdentity(ldapUserIdentity);
                rdbmsLdapUserIdentityDao.create(rdbmsUserIdentity);
            }
        } catch (NamingException e) {
            throw new RuntimeException(e);
        }
    }
}
