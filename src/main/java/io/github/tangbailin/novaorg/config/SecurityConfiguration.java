package io.github.tangbailin.novaorg.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.core.userdetails.User;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean JdbcUserDetailsManager userDetailsService(DataSource ds) { return new JdbcUserDetailsManager(ds); }
    @Bean @DependsOn("flywayInitializer")
    InitializingBean bootstrapUsers(JdbcUserDetailsManager users, PasswordEncoder encoder, Environment env) {
        return () -> {
            for(String name:java.util.List.of("operator","reviewer","admin")) {
                if(users.userExists(name)) continue;
                String password=env.getProperty("NOVAORG_"+name.toUpperCase()+"_PASSWORD", "");
                if(password.length()<12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 || password.startsWith("CHANGE_ME")) throw new IllegalStateException("首次启动请设置 12–72 字节的 NOVAORG_"+name.toUpperCase()+"_PASSWORD，不能使用示例占位符（可使用 scripts/start.ps1 自动生成）");
                users.createUser(User.withUsername(name).password(encoder.encode(password)).roles(name.toUpperCase()).build());
            }
        };
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
            .requestMatchers("/login.html","/login.js","/style.css","/favicon.svg","/api/auth/csrf","/actuator/health","/error").permitAll()
            .requestMatchers("/api/tasks/*/approve").hasAnyRole("REVIEWER","ADMIN")
            .requestMatchers("/api/tasks/audit","/api/audit/**","/api/metrics/**").hasAnyRole("REVIEWER","ADMIN")
            .anyRequest().authenticated())
            .formLogin(f -> f.loginPage("/login.html").loginProcessingUrl("/api/login")
                .successHandler((req,res,auth)-> {res.setContentType("application/json");res.getWriter().write("{\"ok\":true}");})
                .failureHandler((req,res,e)-> {res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Invalid username or password\"}");}).permitAll())
            .logout(l->l.logoutUrl("/api/logout").logoutSuccessHandler((req,res,a)->res.setStatus(204)))
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)-> {
                if(req.getRequestURI().startsWith("/api/")) {res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"message\":\"Login required\"}");}
                else res.sendRedirect("/login.html");
            }));
        return http.build();
    }
}
