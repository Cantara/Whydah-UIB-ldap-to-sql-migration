package net.whydah.identity.ldap_to_sql_migration;


import com.netflix.hystrix.exception.HystrixBadRequestException;
import net.whydah.identity.user.identity.CommandLdapSearch;
import net.whydah.identity.user.identity.LDAPUserIdentity;
import org.constretto.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.PartialResultException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class MigrationLdapUserIdentityDao {
    private static final Logger log = LoggerFactory.getLogger(MigrationLdapUserIdentityDao.class);

    private static final String ATTRIBUTE_NAME_SN = "sn";
    private static final String ATTRIBUTE_NAME_GIVENNAME = "givenName";
    private static final String ATTRIBUTE_NAME_MAIL = "mail";
    private static final String ATTRIBUTE_NAME_MOBILE = "mobile";
    private static final String ATTRIBUTE_NAME_PASSWORD = "userpassword";
    private static final String ATTRIBUTE_NAME_PERSONREF = "employeeNumber";

    private final Hashtable<String, String> admenv;
    private final String uidAttribute;
    private final String usernameAttribute;
    private final LdapDataMapper mapper;

    public MigrationLdapUserIdentityDao(@Configuration("ldap.primary.url") String primaryLdapUrl,
                                        @Configuration("ldap.primary.admin.principal") String primaryAdmPrincipal,
                                        @Configuration("ldap.primary.admin.credentials") String primaryAdmCredentials,
                                        @Configuration("ldap.primary.uid.attribute") String primaryUidAttribute,
                                        @Configuration("ldap.primary.username.attribute") String primaryUsernameAttribute,
                                        LdapDataMapper mapper) {
        admenv = new Hashtable<>(4);
        admenv.put(Context.PROVIDER_URL, primaryLdapUrl);
        admenv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        admenv.put(Context.SECURITY_PRINCIPAL, primaryAdmPrincipal);
        admenv.put(Context.SECURITY_CREDENTIALS, primaryAdmCredentials);
        this.uidAttribute = primaryUidAttribute;
        this.usernameAttribute = primaryUsernameAttribute;
        this.mapper = mapper;
    }

    public LDAPUserIdentity getUserIndentityWithPassword(String usernameOrUid) throws NamingException {
        Attributes attributes = getUserAttributesForUsernameOrUid(usernameOrUid);
        LDAPUserIdentity id = fromLdapAttributesWithPassword(attributes);
        return id;
    }

    private Attributes getUserAttributesForUsernameOrUid(String usernameOrUid) throws NamingException {
        Attributes userAttributesForUsername = getAttributesFor(usernameAttribute, usernameOrUid);
        if (userAttributesForUsername != null) {
            return userAttributesForUsername;
        }

        log.debug("No attributes found for username=" + usernameOrUid + ", trying uid");
        return getAttributesFor(uidAttribute, usernameOrUid);
    }

    private Attributes getAttributesFor(String attributeName, String attributeValue) throws NamingException {
        SearchControls constraints = new SearchControls();
        constraints.setSearchScope(SearchControls.SUBTREE_SCOPE);
        log.trace("getAttributesForUid using {}={}", attributeName, attributeValue);
        try {
            SearchResult searchResult = new CommandLdapSearch(getContext(), "", "(" + attributeName + "=" + attributeValue + ")", constraints).execute();
            if (searchResult == null) {
                log.trace("getAttributesForUid found no attributes for {}={}.", attributeName, attributeValue);
                return null;
            }
            return searchResult.getAttributes();
        } catch (HystrixBadRequestException he) {
            if (he.getCause() instanceof NamingException) {
                NamingException pre = (NamingException) he.getCause();
                if (pre instanceof PartialResultException) {
                    log.trace("Partial Search only. Due to speed optimization, full search in LDAP/AD is not enabled. {}: {}, PartialResultException: {}", attributeName, attributeValue, pre.getMessage());
                } else {
                    log.trace("NamingException. {}: {}", attributeName, attributeValue);
                    throw pre;
                }
            }
            throw he;
        }
    }

    private DirContext getContext() throws NamingException {
        return new InitialDirContext(admenv);
    }


    public Iterable<LDAPUserIdentity> allUsersWithPassword() throws NamingException {
        SearchControls constraints = new SearchControls();
        constraints.setSearchScope(SearchControls.SUBTREE_SCOPE);
        final NamingEnumeration objs = new InitialDirContext(admenv).search("", "(objectClass=*)", constraints);

        return new Iterable<LDAPUserIdentity>() {
            @Override
            public Iterator<LDAPUserIdentity> iterator() {
                return new Iterator<LDAPUserIdentity>() {
                    LDAPUserIdentity next = null;

                    @Override
                    public boolean hasNext() {
                        if (next != null) {
                            return true;
                        }
                        try {
                            while (objs.hasMore()) {
                                next = doGetNext();
                                if (next != null) {
                                    return true;
                                }
                            }
                            return false;
                        } catch (NamingException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public LDAPUserIdentity next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException("next() called after end of iterator, please guard with hasNext()");
                        }
                        LDAPUserIdentity result = next;
                        next = null;
                        return result;
                    }

                    private LDAPUserIdentity doGetNext() {
                        //Each item is a SearchResult object
                        try {
                            for (int i = 0; ; i++) {

                                SearchResult match;
                                try {
                                    if (i > 0 && !objs.hasMore()) {
                                        return null;
                                    }
                                    match = (SearchResult) objs.next();
                                } catch (NoSuchElementException e) {
                                    return null;
                                }

                                if (match == null) {
                                    return null;
                                }

                                //Get the node's attributes
                                Attributes attrs = match.getAttributes();

                                if (attrs == null) {
                                    return null;
                                }

                                Attribute uidAttributeValue = attrs.get(uidAttribute);
                                Attribute usernameAttributeValue = attrs.get(usernameAttribute);

                                if (uidAttributeValue != null && usernameAttributeValue != null) {
                                    LDAPUserIdentity ldapUser = fromLdapAttributesWithPassword(attrs);
                                    if (ldapUser != null) {
                                        return ldapUser;
                                    }
                                }
                            }
                        } catch (NamingException e) {
                            throw new RuntimeException(e);
                        }
                    }
                };
            }
        };
    }

    private LDAPUserIdentity fromLdapAttributesWithPassword(Attributes attributes) throws NamingException {
        return mapper.toLDAPUserIdentity(
                (String) attributes.get(uidAttribute).get(),
                (String) attributes.get(usernameAttribute).get(),
                getAttribValue(attributes, ATTRIBUTE_NAME_GIVENNAME),
                getAttribValue(attributes, ATTRIBUTE_NAME_SN),
                getAttribValue(attributes, ATTRIBUTE_NAME_MAIL),
                getAttribValue(attributes, ATTRIBUTE_NAME_PERSONREF),
                getAttribValue(attributes, ATTRIBUTE_NAME_MOBILE),
                getBinaryAttribValueAsUtf8String(attributes, ATTRIBUTE_NAME_PASSWORD)
        );
    }

    private String getAttribValue(Attributes attributes, String attributeName) throws NamingException {
        Attribute attribute = attributes.get(attributeName);
        if (attribute != null) {
            return (String) attribute.get();
        } else {
            return null;
        }
    }

    private String getBinaryAttribValueAsUtf8String(Attributes attributes, String attributeName) throws NamingException {
        Attribute attribute = attributes.get(attributeName);
        if (attribute != null) {
            return new String((byte[]) attribute.get(0), StandardCharsets.UTF_8);
        } else {
            return null;
        }
    }
}



