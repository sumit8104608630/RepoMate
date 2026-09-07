package devPilot.backend.config;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
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
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;

import devPilot.backend.security.GithubOAuth2UserService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final GithubOAuth2UserService gitHubOAuth2UserService;
    private final ClientRegistrationRepository clientRegistrationRepository;

    private static final String SESSION_COOKIE_NAME = "DEVPILOT_SESSION";

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Bean
    ServletContextInitializer sessionCookieContextInitializer(
            @Value("${APP_PRODUCTION_MODE:false}") boolean productionMode) {
        return servletContext -> {
            jakarta.servlet.SessionCookieConfig cfg = servletContext.getSessionCookieConfig();
            cfg.setName(SESSION_COOKIE_NAME);
            cfg.setHttpOnly(true);
            cfg.setPath("/");
            if (productionMode) {
                cfg.setSecure(true);
            }
        };
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCookieSameSiteCustomizer(
            @Value("${APP_PRODUCTION_MODE:false}") boolean productionMode) {
        final String sameSiteCookies = productionMode ? "none" : "lax";
        return factory -> factory.addContextCustomizers(context -> {
            Rfc6265CookieProcessor processor = new Rfc6265CookieProcessor();
            processor.setSameSiteCookies(sameSiteCookies);
            processor.setHttpOnlySessions(true);
            processor.setSecurePagesOnly(productionMode);
            context.setCookieProcessor(processor);
        });
    }

    @Bean
    FilterRegistrationBean<Filter> sessionCookieAttrsFilter(
            @Value("${APP_PRODUCTION_MODE:false}") boolean productionMode) {
        final String sameSite = productionMode ? "None" : "Lax";
        final boolean secure = productionMode;
        Filter filter = new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletResponse response = (HttpServletResponse) res;
                SessionCookieResponseWrapper wrapped = new SessionCookieResponseWrapper(response, sameSite, secure);
                chain.doFilter(req, wrapped);
                wrapped.flushHeaders();
            }
        };
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }

    private static String rewriteSetCookieHeader(String value, String sameSite, boolean secure) {
        if (value == null) return null;
        String v = value;
        v = v.replaceAll("(?i);\\s*SameSite=[^;]*", "");
        v = v.replaceAll("(?i);\\s*Secure", "");
        if (secure) {
            v = v + "; Secure";
        }
        v = v + "; SameSite=" + sameSite;
        return v;
    }

    private static final class SessionCookieResponseWrapper extends HttpServletResponseWrapper {
        private final String sameSite;
        private final boolean secure;

        SessionCookieResponseWrapper(HttpServletResponse response, String sameSite, boolean secure) {
            super(response);
            this.sameSite = sameSite;
            this.secure = secure;
        }

        private boolean isSessionCookie(String headerValue) {
            return headerValue != null && headerValue.contains(SESSION_COOKIE_NAME + "=");
        }

        @Override
        public void setHeader(String name, String value) {
            if ("Set-Cookie".equalsIgnoreCase(name) && isSessionCookie(value)) {
                super.setHeader(name, rewriteSetCookieHeader(value, sameSite, secure));
            } else {
                super.setHeader(name, value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if ("Set-Cookie".equalsIgnoreCase(name) && isSessionCookie(value)) {
                super.addHeader(name, rewriteSetCookieHeader(value, sameSite, secure));
            } else {
                super.addHeader(name, value);
            }
        }

        @Override
        public void addCookie(Cookie cookie) {
            if (SESSION_COOKIE_NAME.equals(cookie.getName())) {
                cookie.setHttpOnly(true);
                cookie.setPath("/");
                if (secure) cookie.setSecure(true);
                String header = buildSetCookieHeader(cookie, sameSite, secure);
                super.addHeader("Set-Cookie", header);
            } else {
                super.addCookie(cookie);
            }
        }

        void flushHeaders() {
        }

        private static String buildSetCookieHeader(Cookie c, String sameSite, boolean secure) {
            StringBuilder sb = new StringBuilder();
            sb.append(c.getName()).append('=').append(c.getValue() == null ? "" : c.getValue());
            if (c.getPath() != null) sb.append("; Path=").append(c.getPath());
            if (c.getDomain() != null) sb.append("; Domain=").append(c.getDomain());
            if (c.getMaxAge() >= 0) sb.append("; Max-Age=").append(c.getMaxAge());
            if (c.isHttpOnly()) sb.append("; HttpOnly");
            if (secure) sb.append("; Secure");
            sb.append("; SameSite=").append(sameSite);
            return sb.toString();
        }
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
                    AuthenticationException exception) throws IOException, ServletException {
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
