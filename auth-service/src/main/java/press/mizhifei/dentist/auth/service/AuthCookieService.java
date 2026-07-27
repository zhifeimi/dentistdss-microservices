package press.mizhifei.dentist.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.stereotype.Service;
import press.mizhifei.dentist.auth.dto.SessionTokens;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

@Service
public class AuthCookieService {

    public static final String SECURE_REFRESH_COOKIE = "__Secure-dentistdss-refresh";
    public static final String DEVELOPMENT_REFRESH_COOKIE = "dentistdss-refresh";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Value("${app.security.cookies.secure:true}")
    private boolean secure;

    @Value("${app.security.cookies.same-site:None}")
    private String sameSite;

    @Value("${app.security.refresh-token-days:30}")
    private long refreshTokenDays;

    public void addSessionCookies(HttpHeaders headers, SessionTokens tokens) {
        String cookieName = refreshCookieName();
        headers.add(HttpHeaders.SET_COOKIE,
                refreshCookie(cookieName, tokens.refreshToken(), Duration.ofDays(refreshTokenDays), secure).toString());
        headers.add(HttpHeaders.SET_COOKIE,
                refreshCookie(alternateRefreshCookieName(), "", Duration.ZERO, true).toString());
        headers.add(HttpHeaders.SET_COOKIE, csrfCookie(tokens.csrfToken(), Duration.ofDays(refreshTokenDays)).toString());
        headers.set(CSRF_HEADER, tokens.csrfToken());
        headers.setCacheControl("no-store");
    }

    public void clearSessionCookies(HttpHeaders headers) {
        headers.add(HttpHeaders.SET_COOKIE,
                refreshCookie(SECURE_REFRESH_COOKIE, "", Duration.ZERO, true).toString());
        headers.add(HttpHeaders.SET_COOKIE,
                refreshCookie(DEVELOPMENT_REFRESH_COOKIE, "", Duration.ZERO, secure).toString());
        headers.add(HttpHeaders.SET_COOKIE, csrfCookie("", Duration.ZERO).toString());
        headers.setCacheControl("no-store");
    }

    public String readRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        String cookieName = refreshCookieName();
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void validateCsrf(String headerValue, String cookieValue) {
        if (headerValue == null || cookieValue == null || !MessageDigest.isEqual(
                headerValue.getBytes(StandardCharsets.UTF_8),
                cookieValue.getBytes(StandardCharsets.UTF_8))) {
            DefaultCsrfToken expectedToken = new DefaultCsrfToken(
                    CSRF_HEADER,
                    "_csrf",
                    cookieValue == null ? "" : cookieValue);
            throw new InvalidCsrfTokenException(expectedToken, headerValue);
        }
    }

    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(CSRF_COOKIE);
        repository.setHeaderName(CSRF_HEADER);
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie
                .secure(secure)
                .sameSite(sameSite));
        return repository;
    }

    private String refreshCookieName() {
        return secure ? SECURE_REFRESH_COOKIE : DEVELOPMENT_REFRESH_COOKIE;
    }

    private String alternateRefreshCookieName() {
        return secure ? DEVELOPMENT_REFRESH_COOKIE : SECURE_REFRESH_COOKIE;
    }

    private ResponseCookie refreshCookie(String name, String value, Duration maxAge, boolean secureFlag) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secureFlag)
                .sameSite(sameSite)
                .path("/api/auth")
                .maxAge(maxAge)
                .build();
    }

    private ResponseCookie csrfCookie(String value, Duration maxAge) {
        return ResponseCookie.from(CSRF_COOKIE, value)
                .httpOnly(false)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
