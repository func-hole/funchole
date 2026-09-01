package com.funchole.backend.controlplane.security;

import com.funchole.backend.controlplane.entity.AppUser;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AppUserPrincipal implements UserDetails {

    private final UUID id;
    private final String username;
    private final String password;
    private final Collection<? extends GrantedAuthority> authorities;
    private final boolean passwordChangeRequired;

    public AppUserPrincipal(AppUser appUser) {
        this.id = appUser.getId();
        this.username = appUser.getUsername();
        this.password = appUser.getPasswordHash();
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        this.passwordChangeRequired = appUser.isPasswordChangeRequired();
    }

    public UUID getId() {
        return id;
    }

    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
