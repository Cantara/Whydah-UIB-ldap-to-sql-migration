package net.whydah.identity.ldap_to_sql_migration;

import net.whydah.identity.user.identity.LDAPUserIdentity;
import net.whydah.identity.user.identity.LdapUserIdentityDao;
import net.whydah.identity.user.identity.RDBMSLdapUserIdentityDao;
import net.whydah.identity.user.identity.RDBMSUserIdentity;

import javax.naming.NamingException;
import java.util.function.Consumer;

public class UIBMigration {

    public static void main(String[] args) {
        // TODO Add wiring. See tests for example of wiring.
    }

    private final LdapUserIdentityDao ldapUserIdentityDao;
    private final RDBMSLdapUserIdentityDao rdbmsLdapUserIdentityDao;

    public UIBMigration(LdapUserIdentityDao ldapUserIdentityDao, RDBMSLdapUserIdentityDao rdbmsLdapUserIdentityDao) {
        this.ldapUserIdentityDao = ldapUserIdentityDao;
        this.rdbmsLdapUserIdentityDao = rdbmsLdapUserIdentityDao;
    }

    public void migrate() {
        doMigrate(rdbmsLdapUserIdentityDao::create);
    }

    public void migrateDryRun() {
        doMigrate(rdbmsUserIdentity -> {
        });
    }

    private void doMigrate(Consumer<RDBMSUserIdentity> writeCallback) {
        try {
            System.out.printf("MIGRATION LDAP -> SQL%n");
            for (LDAPUserIdentity ldapUserIdentity : ldapUserIdentityDao.allUsersWithPassword()) {
                System.out.printf("USER: %s ==::== PASS: '%s'%n", ldapUserIdentity.toString(), ldapUserIdentity.getPassword());
                RDBMSUserIdentity rdbmsUserIdentity = toRDBMSUserIdentity(ldapUserIdentity);
                writeCallback.accept(rdbmsUserIdentity);
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
