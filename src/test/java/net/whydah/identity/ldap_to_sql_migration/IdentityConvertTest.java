package net.whydah.identity.ldap_to_sql_migration;

import net.whydah.identity.user.identity.BCryptService;
import net.whydah.identity.user.identity.LDAPUserIdentity;
import net.whydah.identity.user.identity.RDBMSUserIdentity;
import net.whydah.identity.user.identity.UserIdentityConverter;
import org.junit.Test;

public class IdentityConvertTest {

    @Test
    public void convertUser() {
        BCryptService bCryptService = new BCryptService("4HbdiT8Nmw1QrnE6We", 4);
        UserIdentityConverter converter = new UserIdentityConverter(bCryptService);
        LDAPUserIdentity ldapUserIdentity = new LDAPUserIdentity("21ab3745-0fd4-462d-9f17-6733c1b9e864", "97000341", "Lily Marcela", "Dam", "lilymdam@gmail.com", "JOScNeQTchjUpBTP", "97000341", "83982641-78bd-4ee0-ae70-c3136bc8222b");
        RDBMSUserIdentity rdbmsUserIdentity = converter.convertFromLDAPUserIdentity(ldapUserIdentity);
        rdbmsUserIdentity.toString();
    }
}
