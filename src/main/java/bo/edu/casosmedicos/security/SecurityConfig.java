package bo.edu.casosmedicos.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AuthenticationSuccessHandler successHandler;
    private final AuthenticationFailureHandler failureHandler;

    public SecurityConfig(AuthenticationSuccessHandler successHandler,
                          AuthenticationFailureHandler failureHandler) {
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/images/**", "/js/**", "/fonts/**", "/login", "/error").permitAll()
                .requestMatchers("/ceo/**").hasAuthority("ceo")
                .requestMatchers("/medico/**").hasAuthority("doctor")
                .requestMatchers("/enfermeria/**").hasAuthority("enfermero")
                .requestMatchers("/paciente/**").hasAuthority("paciente")
                .requestMatchers("/admin/**").hasAuthority("admin")
                .requestMatchers("/documentos/cv/**").hasAnyAuthority("admin", "ceo")
                .requestMatchers("/documentos/atencion/**").hasAnyAuthority("doctor", "paciente", "ceo", "enfermero")
                .requestMatchers("/chat/adjunto/**").hasAnyAuthority("paciente", "doctor")
                // Chat en vivo: solo paciente y doctor (el caso se valida en ChatHandshakeInterceptor)
                .requestMatchers("/ws/chat", "/ws/avisos").hasAnyAuthority("paciente", "doctor")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .successHandler(successHandler)
                .failureHandler(failureHandler)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}