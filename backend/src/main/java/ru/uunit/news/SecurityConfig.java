package ru.uunit.news;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {

  @Bean
  SessionRegistry sessions() {
    return new SessionRegistryImpl();
  }

  @Bean
  HttpSessionEventPublisher sessionEvents() {
    return new HttpSessionEventPublisher();
  }

  @Bean
  PasswordEncoder passwords() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService users(JdbcTemplate db) {
    return username ->
      db
        .query(
          "SELECT username,password_hash,role FROM app_users WHERE username=?",
          (r, n) ->
            org.springframework.security.core.userdetails.User.withUsername(r.getString("username"))
              .password(r.getString("password_hash"))
              .roles(r.getString("role"))
              .build(),
          username
        )
        .stream()
        .findFirst()
        .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
  }

  private void json(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write("{\"message\":\"" + message + "\"}");
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, SessionRegistry registry, RateLimiter rates)
    throws Exception {
    http.csrf(csrf ->
      csrf
        .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
    );
    http.authorizeHttpRequests(auth ->
      auth
        .requestMatchers("/api/auth/**", "/api/health", "/api/dev/mailbox")
        .permitAll()
        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/news/*/view")
        .permitAll()
        .requestMatchers(
          org.springframework.http.HttpMethod.GET,
          "/api/news/**",
          "/api/communities/**",
          "/api/media/**"
        )
        .permitAll()
        .requestMatchers("/api/**")
        .authenticated()
        .anyRequest()
        .permitAll()
    );
    http.formLogin(form ->
      form
        .loginProcessingUrl("/api/auth/login")
        .successHandler((request, response, auth) -> json(response, 200, "Вход выполнен"))
        .failureHandler((request, response, error) ->
          json(response, 401, "Неверный логин или пароль")
        )
    );
    http.logout(logout ->
      logout
        .logoutUrl("/api/auth/logout")
        .logoutSuccessHandler((req, res, auth) -> json(res, 200, "Вы вышли из аккаунта"))
    );
    http.sessionManagement(s ->
      s
        .maximumSessions(-1)
        .sessionRegistry(registry)
        .expiredSessionStrategy(event ->
          json(event.getResponse(), 401, "Сессия завершена. Войдите заново.")
        )
    );
    http.addFilterBefore(
      new OncePerRequestFilter() {
        @Override
        protected void doFilterInternal(
          jakarta.servlet.http.HttpServletRequest req,
          HttpServletResponse res,
          jakarta.servlet.FilterChain chain
        ) throws jakarta.servlet.ServletException, IOException {
          if (req.getMethod().equals("POST") && req.getRequestURI().equals("/api/auth/login")) {
            try {
              rates.check("login:" + req.getRemoteAddr(), 30, Duration.ofMinutes(15));
            } catch (ApiException e) {
              json(res, 429, e.getMessage());
              return;
            }
          }
          chain.doFilter(req, res);
        }
      },
      UsernamePasswordAuthenticationFilter.class
    );
    http.exceptionHandling(errors ->
      errors
        .authenticationEntryPoint((req, res, e) -> json(res, 401, "Войдите в аккаунт"))
        .accessDeniedHandler((req, res, e) ->
          json(res, 403, "Нет доступа или истекла защита формы. Обновите страницу.")
        )
    );
    http.headers(headers -> headers.contentTypeOptions(c -> {}).frameOptions(f -> f.deny()));
    return http.build();
  }
}
