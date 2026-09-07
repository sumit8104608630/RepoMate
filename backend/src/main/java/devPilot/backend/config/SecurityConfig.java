package devPilot.backend.config;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.apache.catalina.Context;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatContextCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

import devPilot.backend.security.GithubOAuth2UserService;
import jakarta.servlet.ServletContextInitializer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final GithubOAuth2UserService gitHubOAuth2UserService;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final Environment environment;

    private static final String SESSION_COOKIE_NAME = "DEVPILOT_SESSION";

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private boolean isSecureCrossSiteMode() {
        boolean renderProfileActive = environment.acceptsProfiles(Profiles.of("render"));
        String explicit = environment.getProperty("APP_PRODUCTION_MODE", "false");
        boolean explicitFlag = "true".equalsIgnoreCase(explicit);
        return renderProfileActive || explicitFlag;
    }

    @Bean
    ServletContextInitializer sessionCookieContextInitializer() {
        final boolean secureMode = isSecureCrossSiteMode();
        return servletContext -> {
            jakarta.servlet.SessionCookieConfig cfg = servletContext.getSessionCookieConfig();
            cfg.setName(SESSION_COOKIE_NAME);
            cfg.setHttpOnly(true);
            cfg.setPath("/");
            if (secureMode) {
                cfg.setSecure(true);
            }
        };
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatWebServerCustomizer() {
        final boolean secureMode = isSecureCrossSiteMode();
        return factory -> {
            factory.addContextCustomizers((Context context) -> {
                Rfc6265CookieProcessor cookieProcessor = new Rfc6265CookieProcessor();
                cookieProcessor.setSameSiteCookies(secureMode ? "none" : "lax");
                context.setCookieProcessor(cookieProcessor);
            });
            RemoteIpValve remoteIpValve = new RemoteIpValve();
            remoteIpValve.setRemoteIpHeader("X-Forwarded-For");
            remoteIpValve.setProtocolHeader("X-Forwarded-Proto");
            remoteIpValve.setPortHeader("X-Forwarded-Port");
            remoteIpValve.setHostHeader("X-Forwarded-Host");
            remoteIpValve.setProtocolHeaderHttpsValue("https");
            factory.addEngineValves(remoteIpValve);
        };
    }

    @Bean
    OAuth2AuthorizationRequestResolver oauth2AuthorizationRequestResolver(
            @Value("${spring.security.oauth2.client.registration.github.redirect-uri}") String configuredRedirectUri) {
        String redirectUri = stripTrailingSlash(configuredRedirectUri);
        DefaultOAuth2AuthorizationRequestResolver defaultResolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository,
                        "/oauth2/authorization");
        defaultResolver.setAuthorizationRequestCustomizer(builder -> {
            Object regId = builder.build().getAttributes().get("registration_id");
            if (regId != null && "github".equals(regId.toString())) {
                builder.redirectUri(redirectUri);
            }
        });
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return defaultResolver.resolve(request);
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
                OAuth2AuthorizationRequest req = defaultResolver.resolve(request, registrationId);
                if (req == null) return null;
                if ("github".equals(registrationId)) {
                    return OAuth2AuthorizationRequest.from(req)
                            .redirectUri(redirectUri)
                            .build();
                }
                return req;
            }
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationSuccessHandler oauth2SuccessHandler,
            AuthenticationFailureHandler oauth2FailureHandler,
            OAuth2AuthorizationRequestResolver oauth2AuthorizationRequestResolver) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/login-url",
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(authEndpoint -> authEndpoint
                                .authorizationRequestResolver(oauth2AuthorizationRequestResolver))
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(gitHubOAuth2UserService))
                        .successHandler(oauth2SuccessHandler)
                        .failureHandler(oauth2FailureHandler))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value()))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(SESSION_COOKIE_NAME));

        return http.build();
    }

    @Bean
    AuthenticationSuccessHandler oauth2SuccessHandler(
            @Value("${app.frontend-url}") String frontendUrl) {
        SimpleUrlAuthenticationSuccessHandler handler = new SimpleUrlAuthenticationSuccessHandler();
        handler.setDefaultTargetUrl(stripTrailingSlash(frontendUrl) + "/auth/callback");
        return handler;
    }

    @Bean
    AuthenticationFailureHandler oauth2FailureHandler(
            @Value("${app.frontend-url}") String frontendUrl) {
        return new AuthenticationFailureHandler() {
            @Override
            public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                    AuthenticationException exception) throws IOException {
                String base = stripTrailingSlash(frontendUrl) + "/login?error=oauth_failed";
                String message = exception != null ? exception.getMessage() : null;
                if (message != null && !message.isBlank()) {
                    base += "&error_description=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
                }
                String oauthError = request.getParameter("error");
                if (oauthError != null && !oauthError.isBlank()) {
                    base += "&oauth_error=" + URLEncoder.encode(oauthError, StandardCharsets.UTF_8);
                    String oauthDesc = request.getParameter("error_description");
                    if (oauthDesc != null && !oauthDesc.isBlank()) {
                        base += "&oauth_error_description=" + URLEncoder.encode(oauthDesc, StandardCharsets.UTF_8);
                    }
                }
                response.sendRedirect(base);
            }
        };
    }
}
