package io.github.tangbailin.novaorg.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.core.userdetails.User;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcUserDetailsManager users;
    private final PasswordEncoder encoder;
    public AuthController(JdbcUserDetailsManager users,PasswordEncoder encoder) {this.users=users;this.encoder=encoder;}
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @GetMapping("/me") public Map<String,Object> me(Authentication auth) {return Map.of("username",auth.getName(),"roles",auth.getAuthorities().stream().map(Object::toString).toList());}
    @PostMapping("/password") public Map<String,Boolean> password(Authentication auth,@RequestBody PasswordChange change) {
        var user=users.loadUserByUsername(auth.getName());
        if(change.newPassword()==null || change.newPassword().length()<12 || change.newPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 || !encoder.matches(change.currentPassword()==null?"":change.currentPassword(),user.getPassword()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"原密码不正确，或新密码长度不在 12–72 位范围");
        users.updateUser(User.withUserDetails(user).password(encoder.encode(change.newPassword())).build());
        return Map.of("ok",true);
    }
    public record PasswordChange(String currentPassword,String newPassword) {}
}
