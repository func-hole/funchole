package com.funchole.backend.controlplane.config;

import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.controlplane.service.FunctionVersionInvocationService;
import com.funchole.backend.invocationcontract.FunctionVersionInvocationHandoff;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link FunctionVersionInvocationService} using only types
 * controlplane is allowed to depend on: its own {@link FunctionVersionRepository}
 * and the {@code invocation-contract} module's {@link FunctionVersionInvocationHandoff}.
 * The concrete handoff implementation is registered elsewhere (the
 * {@code invocation} module's own configuration, present on this
 * application's runtime classpath only) and resolved here purely by
 * interface type.
 */
@Configuration
public class FunctionVersionInvocationConfig {

    @Bean
    FunctionVersionInvocationService functionVersionInvocationService(
            FunctionVersionRepository functionVersionRepository,
            FunctionVersionInvocationHandoff functionVersionInvocationHandoff
    ) {
        return new FunctionVersionInvocationService(functionVersionRepository, functionVersionInvocationHandoff);
    }
}
