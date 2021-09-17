package net.whydah.identity.ldap_to_sql_migration;

import net.whydah.identity.user.identity.LDAPUserIdentity;

public interface LdapDataMapper {

    LDAPUserIdentity toLDAPUserIdentity(String uid, String username, String firstname, String lastname, String email, String personRef, String cellPhone, String password);
}
