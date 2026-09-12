package com.funchole.backend.invocation;

import com.funchole.backend.invocationcontract.FunctionVersionInvocationHandoff;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the concrete Invocation-side implementation of
 * {@link FunctionVersionInvocationHandoff} as a Spring bean. This class lives
 * in the {@code invocation} module - not controlplane - so controlplane
 * never needs to import {@link InvocationRegistry}, {@link JdbcInvocationRegistry},
 * or {@link Invocation} to obtain a working handoff: it only ever sees the
 * {@code invocation-contract} interface type, and Spring's classpath
 * component scan (controlplane depends on this module at {@code runtimeOnly},
 * not {@code implementation}) finds this configuration and wires the real
 * adapter automatically.
 *
 * <p>Uses a {@link NoopInvocationEventPublisher} (the same default
 * {@link JdbcInvocationRegistry}'s single-arg constructor already applies) -
 * real NATS-backed ready-event publication from this entry point is a
 * separate, deliberately out-of-scope follow-up; direct invocations created
 * here are durably persisted and inspectable immediately, but are not yet
 * picked up by the Dispatcher.
 */
@Configuration
public class InvocationHandoffConfig {

    @Bean
    InvocationRegistry invocationRegistry(DataSource dataSource) {
        return new JdbcInvocationRegistry(dataSource);
    }

    @Bean
    FunctionVersionInvocationHandoff functionVersionInvocationHandoff(InvocationRegistry invocationRegistry) {
        return new InvocationRegistryFunctionVersionInvocationHandoff(invocationRegistry);
    }
}
