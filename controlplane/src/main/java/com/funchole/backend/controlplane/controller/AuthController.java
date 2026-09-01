package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.dto.AuthRequest;
import com.funchole.backend.controlplane.dto.AuthTokenResponse;
import com.funchole.backend.controlplane.mapper.AuthTokenMapper;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.security.JwtService;
import com.funchole.backend.controlplane.security.JwtToken;
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

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        JwtToken jwtToken = jwtService.generateToken(principal.getId(), principal.getUsername());
        return ApiResponse.success(authTokenMapper.toResponse(jwtToken));
    }
}
