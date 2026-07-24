package press.mizhifei.dentist.auth.service;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.test.util.ReflectionTestUtils;
import press.mizhifei.dentist.auth.dto.SessionTokens;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthCookieServiceTest {

    @Test
    void usesNonPrefixedRefreshCookieForInsecureDevelopment() {
        AuthCookieService service = cookieService(false, "Lax");
        HttpHeaders headers = new HttpHeaders();

        service.addSessionCookies(headers, new SessionTokens(null, "refresh-token", "csrf-token"));

        List<String> cookies = headers.get(HttpHeaders.SET_COOKIE);
        String refreshCookie = cookieStartingWith(cookies, AuthCookieService.DEVELOPMENT_REFRESH_COOKIE + "=");
        String expiredSecureCookie = cookieStartingWith(cookies, AuthCookieService.SECURE_REFRESH_COOKIE + "=");
        String csrfCookie = cookieStartingWith(cookies, AuthCookieService.CSRF_COOKIE + "=");

        assertTrue(refreshCookie.startsWith(AuthCookieService.DEVELOPMENT_REFRESH_COOKIE + "=refresh-token"));
        assertFalse(refreshCookie.contains("; Secure"));
        assertTrue(refreshCookie.contains("; HttpOnly"));
        assertTrue(refreshCookie.contains("; SameSite=Lax"));
        assertTrue(expiredSecureCookie.contains("; Max-Age=0"));
        assertTrue(expiredSecureCookie.contains("; Secure"));
        assertFalse(csrfCookie.contains("; HttpOnly"));
        assertFalse(csrfCookie.contains("; Secure"));
        assertEquals("csrf-token", headers.getFirst(AuthCookieService.CSRF_HEADER));
        assertEquals("no-store", headers.getCacheControl());
    }

    @Test
    void securePrefixAlwaysCarriesSecureAttribute() {
        AuthCookieService service = cookieService(true, "None");
        HttpHeaders headers = new HttpHeaders();

        service.addSessionCookies(headers, new SessionTokens(null, "refresh-token", "csrf-token"));
        service.clearSessionCookies(headers);

        List<String> cookies = headers.get(HttpHeaders.SET_COOKIE);
        cookies.stream()
                .filter(cookie -> cookie.startsWith(AuthCookieService.SECURE_REFRESH_COOKIE + "="))
                .forEach(cookie -> assertTrue(cookie.contains("; Secure")));

        String activeRefreshCookie = cookies.stream()
                .filter(cookie -> cookie.startsWith(AuthCookieService.SECURE_REFRESH_COOKIE + "=refresh-token"))
                .findFirst()
                .orElseThrow();
        assertTrue(activeRefreshCookie.contains("; SameSite=None"));
    }

    @Test
    void readsOnlyRefreshCookieSelectedForCurrentEnvironment() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(AuthCookieService.SECURE_REFRESH_COOKIE, "secure-token"),
                new Cookie(AuthCookieService.DEVELOPMENT_REFRESH_COOKIE, "development-token"));

        assertEquals("development-token", cookieService(false, "Lax").readRefreshToken(request));
        assertEquals("secure-token", cookieService(true, "None").readRefreshToken(request));
    }

    @Test
    void springCsrfRepositoryUsesTheExistingXsrfCookieContract() {
        AuthCookieService service = cookieService(false, "Lax");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken token = service.csrfTokenRepository().generateToken(request);

        service.csrfTokenRepository().saveToken(token, request, response);

        Cookie cookie = response.getCookie(AuthCookieService.CSRF_COOKIE);
        assertNotNull(cookie);
        assertEquals(AuthCookieService.CSRF_COOKIE, cookie.getName());
        assertFalse(cookie.isHttpOnly());
        assertFalse(cookie.getSecure());
        assertEquals("Lax", cookie.getAttribute("SameSite"));
        assertEquals("/", cookie.getPath());
    }

    @Test
    void validatesDoubleSubmitCsrfToken() {
        AuthCookieService service = cookieService(true, "None");

        assertDoesNotThrow(() -> service.validateCsrf("matching-token", "matching-token"));
        assertThrows(InvalidCsrfTokenException.class, () -> service.validateCsrf("header-token", "cookie-token"));
        assertThrows(InvalidCsrfTokenException.class, () -> service.validateCsrf(null, "cookie-token"));
    }

    private AuthCookieService cookieService(boolean secure, String sameSite) {
        AuthCookieService service = new AuthCookieService();
        ReflectionTestUtils.setField(service, "secure", secure);
        ReflectionTestUtils.setField(service, "sameSite", sameSite);
        ReflectionTestUtils.setField(service, "refreshTokenDays", 30L);
        return service;
    }

    private String cookieStartingWith(List<String> cookies, String prefix) {
        return cookies.stream()
                .filter(cookie -> cookie.startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }
}
