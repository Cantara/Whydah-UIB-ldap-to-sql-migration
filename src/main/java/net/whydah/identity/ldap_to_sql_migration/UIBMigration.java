package net.whydah.identity.ldap_to_sql_migration;

import net.whydah.identity.user.identity.BCryptService;
import net.whydah.identity.user.identity.LDAPUserIdentity;
import net.whydah.identity.user.identity.RDBMSLdapUserIdentityDao;
import net.whydah.identity.user.identity.RDBMSUserIdentity;
import net.whydah.identity.user.identity.UserIdentityConverter;
import org.apache.commons.dbcp.BasicDataSource;
import org.constretto.ConstrettoBuilder;
import org.constretto.ConstrettoConfiguration;
import org.constretto.model.Resource;

import javax.naming.NamingException;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class UIBMigration {

    static String usage() {
        return "Usage: java -jar uib-ldap-to-sql-migration.jar [--dry-run] [--print-passwords] [-u <usernameOrUid>] [-n <maxUsersToMigrate>]";
    }

    public static void main(String[] args) {
        try {
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

            MigrationLdapUserIdentityDao ldapUserIdentityDao = new MigrationLdapUserIdentityDao(primaryLdapUrl, primaryAdmPrincipal, primaryAdmCredentials, primaryUidAttribute, primaryUsernameAttribute, new Mapper());

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
        } catch (Throwable t) {
            System.err.printf("Unexpected error, exiting....%n");
            t.printStackTrace();
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

    private final MigrationLdapUserIdentityDao ldapUserIdentityDao;
    private final RDBMSLdapUserIdentityDao rdbmsLdapUserIdentityDao;
    private final UserIdentityConverter converter;

    final boolean dryRun;
    final int maxUsersToMigrate;
    final boolean printPasswords;
    final int N_THREADS = 8;
    final CountDownLatch finishedWorkers = new CountDownLatch(N_THREADS);

    final BlockingQueue<LDAPUserIdentity> queue = new ArrayBlockingQueue<>(40);
    final LDAPUserIdentity ENDSIGNAL = new LDAPUserIdentity("__END_LDAP_USER_IDENTITY__", "END", "END", "END", "end@end.com", "s3cr3t", "12345678", "END");
    final AtomicInteger migrationCount = new AtomicInteger();
    final AtomicBoolean stop = new AtomicBoolean();


    public UIBMigration(MigrationLdapUserIdentityDao ldapUserIdentityDao, RDBMSLdapUserIdentityDao rdbmsLdapUserIdentityDao, BCryptService bCryptService, boolean dryRun, int maxUsersToMigrate, boolean printPasswords) {
        this.ldapUserIdentityDao = ldapUserIdentityDao;
        this.rdbmsLdapUserIdentityDao = rdbmsLdapUserIdentityDao;
        this.converter = new UserIdentityConverter(bCryptService);
        this.dryRun = dryRun;
        this.maxUsersToMigrate = maxUsersToMigrate;
        this.printPasswords = printPasswords;
    }

    public void migrate() {
        try {
            for (int i = 0; i < N_THREADS; i++) {
                new Thread(new MigrationWorker(), "migrate-" + i).start();
            }
            System.out.printf("MIGRATION LDAP -> SQL%n");
            Iterator<LDAPUserIdentity> ldapUserIdentitiesIterator = ldapUserIdentityDao.allUsersWithPassword().iterator();
            int count = 0;
            while (ldapUserIdentitiesIterator.hasNext()) {
                LDAPUserIdentity ldapUserIdentity;
                try {
                    ldapUserIdentity = ldapUserIdentitiesIterator.next();
                } catch (Exception e) {
                    e.printStackTrace();
                    continue;
                }
                try {
                    if (count >= maxUsersToMigrate) {
                        break;
                    }
                    if (stop.get()) {
                        break;
                    }
                    queue.put(ldapUserIdentity);
                } finally {
                    count++;
                }
            }
            for (int i = 0; i < N_THREADS; i++) {
                queue.put(ENDSIGNAL);
            }
            finishedWorkers.await(60, TimeUnit.MINUTES);
        } catch (NamingException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void migrateUser(String user) {
        try {
            System.out.printf("MIGRATION LDAP -> SQL of user: %s%n", user);
            LDAPUserIdentity ldapUserIdentity = ldapUserIdentityDao.getUserIndentityWithPassword(user);
            if (printPasswords) {
                System.out.printf("USER: %s ==::== PASS: '%s'%n", ldapUserIdentity, ldapUserIdentity.getPassword());
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

    class MigrationWorker implements Runnable {

        @Override
        public void run() {
            try {
                for (; !stop.get(); ) {
                    final int i = migrationCount.incrementAndGet();
                    LDAPUserIdentity ldapUserIdentity;
                    try {
                        ldapUserIdentity = queue.take();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        return;
                    }
                    try {
                        if (ENDSIGNAL == ldapUserIdentity) {
                            System.out.printf("MigrationWorker reached ENDSIGNAL. Thread: %s%n", Thread.currentThread().getName());
                            return;
                        }
                        if (!dryRun) {
                            String uid = ldapUserIdentity.getUid();
                            RDBMSUserIdentity existingIdentity = rdbmsLdapUserIdentityDao.get(uid);
                            if (existingIdentity != null) {
                                System.out.printf("#%d Skipping USER: %s%n", i, ldapUserIdentity);
                                continue;
                            }
                        }
                        if (printPasswords) {
                            System.out.printf("#%d USER: %s ==::== PASS: '%s'%n", i, ldapUserIdentity, ldapUserIdentity.getPassword());
                        } else {
                            System.out.printf("#%d USER: %s%n", i, ldapUserIdentity);
                        }
                        if (!dryRun) {
                            RDBMSUserIdentity rdbmsUserIdentity = converter.convertFromLDAPUserIdentity(ldapUserIdentity);
                            rdbmsLdapUserIdentityDao.create(rdbmsUserIdentity);
                        }
                    } catch (Throwable t) {
                        System.out.printf("Error while converting user: uid=%s, username=%s%n", ldapUserIdentity.getUid(), ldapUserIdentity.getUsername());
                        t.printStackTrace();
                        stop.set(true);
                    }
                }
            } finally {
                finishedWorkers.countDown();
            }
        }
    }

    public static class Mapper implements LdapDataMapper {

        public String firstName(String firstname) {
            if (firstname.contains("á")) {
                return firstname.replaceAll("á", "a");
            }
            return firstname;
        }

        @Override
        public LDAPUserIdentity toLDAPUserIdentity(String uid, String username, String firstname, String lastname, String email, String personRef, String cellPhone, String password) {
            try {
                LDAPUserIdentity id = new LDAPUserIdentity(
                        uid,
                        username,
                        firstname,
                        lastname,
                        email,
                        password,
                        cellPhone,
                        personRef
                );
                return id;
            } catch (Exception e) {
                System.out.printf("Unable to create LDAPUserIdentity from attributes. uid='%s', username='%s', firstname='%s', lastname='%s', email='%s', personRef='%s', cellPhone='%s', password='%s'%n",
                        uid, username, firstname, lastname, email, personRef, cellPhone, "*****");
                e.printStackTrace();
                return null;
            }
        }
    }
}
