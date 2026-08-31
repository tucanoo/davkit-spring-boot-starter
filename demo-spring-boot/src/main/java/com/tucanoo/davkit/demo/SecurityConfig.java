package com.tucanoo.davkit.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * The security shape a real host needs around DavKit (see "Wiring the starter into a
 * host" in the repository README):
 * <ol>
 *   <li>{@code /webdav/**} in its own chain: no CSRF (Office sends none), no redirect-to-login
 *       (Word reads a 302 as "not a WebDAV server"). DavKit's own filter authenticates there
 *       (signed URLs, OFBA session).</li>
 *   <li>The document page is public for this local demo, like the Grails demo. A real host
 *       should apply its own access rules before issuing signed links.</li>
 *   <li>The optional OFBA flow stays behind form login. Spring Security's {@code /login}
 *       page authenticates the Office dialog before it can reach {@code /davkit/ofba/done}
 *       and collect the session cookie.</li>
 * </ol>
 */
@Configuration
class SecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain davkitChain(HttpSecurity http) throws Exception {
        // An explicit matcher, not securityMatcher("/webdav/**"): with Spring MVC present the
        // String overload builds an MvcRequestMatcher, which never matches requests served by a
        // non-MVC servlet like DavKit's - the chain silently falls through to form login.
        http.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher("/webdav/**"))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    SecurityFilterChain appChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher("/")).permitAll()
                        .anyRequest().authenticated())
                .formLogin(withDefaults());
        return http.build();
    }

    /** Demo credentials: dave / password. {noop} = plain text, acceptable only in a demo. */
    @Bean
    UserDetailsService users() {
        return new InMemoryUserDetailsManager(
                User.withUsername("dave").password("{noop}password").roles("USER").build());
    }
}
