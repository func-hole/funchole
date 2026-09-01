package com.funchole.backend.api.controller;

import com.funchole.backend.api.dto.AuthRequest;
import com.funchole.backend.api.dto.AuthTokenResponse;
import com.funchole.backend.api.mapper.AuthTokenMapper;
import com.funchole.backend.api.security.JwtService;
import com.funchole.backend.api.security.JwtToken;
import com.funchole.backend.core.base.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuthTokenMapper authTokenMapper;

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            AuthTokenMapper authTokenMapper
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.authTokenMapper = authTokenMapper;
    }

    @PostMapping("/token")
    public ApiResponse<AuthTokenResponse> issueToken(@Valid @RequestBody AuthRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password())
        );

        JwtToken jwtToken = jwtService.generateToken(authentication.getName());
        return ApiResponse.success(authTokenMapper.toResponse(jwtToken));
    }
}
