package com.kiano.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.platform.web.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/auth/login, POST /api/v1/auth/logout, GET /api/v1/auth/me.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AppUserMapper userMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties securityProperties;

    public AuthController(AppUserMapper userMapper, JwtService jwtService, PasswordEncoder passwordEncoder,
            SecurityProperties securityProperties) {
        this.userMapper = userMapper;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.securityProperties = securityProperties;
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record AuthResponse(long userId, String email, String name, String role) {
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
            @Nullable HttpServletResponse response) {
        AppUserEntity user = userMapper.selectOne(Wrappers.<AppUserEntity>lambdaQuery()
                .eq(AppUserEntity::getEmail, request.email().trim().toLowerCase()));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Deliberately the same error for unknown email and wrong password.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS",
                    "Invalid email or password");
        }
        String token = jwtService.issue(user);
        if (response != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, authCookie(token, jwtService.tokenTtl().toSeconds()));
        }
        return new AuthResponse(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Nullable HttpServletResponse response) {
        if (response != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, authCookie("", 0));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthResponse me(CurrentUser user) {
        AppUserEntity entity = userMapper.selectById(user.userId());
        if (entity == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User no longer exists");
        }
        return new AuthResponse(entity.getId(), entity.getEmail(), entity.getName(), entity.getRole());
    }

    private String authCookie(String token, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(SecurityConfig.AUTH_COOKIE, token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .secure(securityProperties.isCookieSecure())
                .build();
        return cookie.toString();
    }
}
