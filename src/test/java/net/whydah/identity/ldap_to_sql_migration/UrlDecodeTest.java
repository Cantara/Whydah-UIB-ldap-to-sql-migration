package net.whydah.identity.ldap_to_sql_migration;

import org.junit.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

public class UrlDecodeTest {

    @Test
    public void decode() throws UnsupportedEncodingException {
        String input = "%3C%3Fxml+version%3D%221.0%22+encoding%3D%22UTF-8%22+standalone%3D%22yes%22%3F%3E+%0A+%3Capplicationcredential%3E%0A++++%3Cparams%3E%0A++++++++%3CapplicationID%3E2212%3C%2FapplicationID%3E%0A++++++++%3CapplicationName%3EWhydah-UserAdminService-1%3C%2FapplicationName%3E%0A++++++++%3CapplicationSecret%3EatLe0JWm1dm50ixzrV79hHLxz%3C%2FapplicationSecret%3E%0A++++++++%3Capplicationurl%3E%3C%2Fapplicationurl%3E%0A++++++++%3Cminimumsecuritylevel%3E0%3C%2Fminimumsecuritylevel%3E%0A++++++++%3CDEFCON%3EDEFCON5%3C%2FDEFCON%3E%0A++++%3C%2Fparams%3E+%0A%3C%2Fapplicationcredential%3E%0A";
        String decoded = URLDecoder.decode(input, "UTF-8");
        System.out.printf("%s%n", decoded);
    }
}
