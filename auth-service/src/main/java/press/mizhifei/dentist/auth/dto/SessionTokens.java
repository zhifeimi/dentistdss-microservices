package press.mizhifei.dentist.auth.dto;

public record SessionTokens(
        AuthResponse response,
        String refreshToken,
        String csrfToken) {
}
