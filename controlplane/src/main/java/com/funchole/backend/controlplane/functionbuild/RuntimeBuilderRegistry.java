package com.funchole.backend.controlplane.functionbuild;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link RuntimeBuilder} for a FunctionVersion runtime type.
 * Spring collects every {@link RuntimeBuilder} bean into {@code builders} -
 * adding a new runtime (Python, Go, Rust, ...) means registering one more
 * {@link RuntimeBuilder} bean, never touching this class or adding a branch
 * here.
 */
@Component
public class RuntimeBuilderRegistry {

    private final List<RuntimeBuilder> builders;

    public RuntimeBuilderRegistry(List<RuntimeBuilder> builders) {
        this.builders = builders;
    }

    public RuntimeBuilder resolve(String runtimeType) {
        return builders.stream()
                .filter(builder -> builder.supports(runtimeType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No RuntimeBuilder registered for runtime type: " + runtimeType));
    }
}
