package press.mizhifei.dentist.auth.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import press.mizhifei.dentist.auth.dto.ApiResponse;
import press.mizhifei.dentist.auth.dto.AuthResponse;
import press.mizhifei.dentist.auth.dto.OAuthLoginRequest;
import press.mizhifei.dentist.auth.service.OAuthUserService;

import java.io.IOException;
import java.util.List;

/**
 * OAuth2 Login Success Handler for redirect-based OAuth2 flows
 * Migrated from oauth-service to consolidate authentication functionality
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final OAuthUserService oAuthUserService;

    @Value("${app.oauth2.authorizedRedirectUris:http://localhost:3000/oauth2/redirect,https://dentistdss.vercel.app/oauth2/redirect,https://dentistgpt.vercel.app/oauth2/redirect,https://dentist.mizhifei.press/oauth2/redirect}")
    private List<String> authorizedRedirectUris;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        if (!(authentication instanceof OAuth2AuthenticationToken)) {
            super.onAuthenticationSuccess(request, response, authentication);
            return;
        }

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String registrationId = ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();

        String email = oAuth2User.getAttribute("email");
        String firstName = oAuth2User.getAttribute("given_name");
        String lastName = oAuth2User.getAttribute("family_name");
        String providerId = oAuth2User.getName(); // Typically 'sub' claim for Google

        if (email == null) {
            log.error("Email not found from OAuth2 provider: {}", registrationId);
            getRedirectStrategy().sendRedirect(request, response, determineTargetUrl(request, response, authentication) + "?error=EmailNotFound");
            return;
        }

        OAuthLoginRequest oAuthLoginRequest = OAuthLoginRequest.builder()
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .provider(registrationId.toUpperCase()) // e.g., GOOGLE
                .providerId(providerId)
                .build();

        try {
            log.debug("Processing OAuth login request: {}", oAuthLoginRequest);
            // Process OAuth login through OAuthUserService (no circular dependency)
            ApiResponse<AuthResponse> authResponseApi = oAuthUserService.processOAuthLogin(oAuthLoginRequest);
            
            if (!authResponseApi.isSuccess() || authResponseApi.getDataObject() == null || authResponseApi.getDataObject().getAccessToken() == null) {
                log.error("Failed to get valid AuthResponse from OAuthUserService for email: {}", email);
                getRedirectStrategy().sendRedirect(request, response, determineTargetUrl(request, response, authentication) + "?error=AuthenticationFailed");
                return;
            }

            String token = authResponseApi.getDataObject().getAccessToken();
            // Use the first configured redirect URI as the target
            // In a real app, you might get this from the 'state' parameter or other means
            String targetUrl = authorizedRedirectUris.get(0);
            String redirectUrl = UriComponentsBuilder.fromUriString(targetUrl)
                    .queryParam("token", token)
                    .build().toUriString();

            log.info("Redirecting to: {}", redirectUrl);
            getRedirectStrategy().sendRedirect(request, response, redirectUrl);

        } catch (Exception e) {
            log.error("Error processing OAuth login for email {}: {}", email, e.getMessage(), e);
            String errorRedirect = authorizedRedirectUris.get(0) + "?error=LoginProcessingError";
             try {
                 getRedirectStrategy().sendRedirect(request, response, errorRedirect);
             } catch (IOException ex) {
                 logger.error("Failed to redirect after error", ex);
             }
        }
    }

    // Optional: Override determineTargetUrl if you need more complex logic for the base redirect before token
    // For now, we construct the full redirect URL with token inside onAuthenticationSuccess
}
