package com.funchole.backend.invocationcontract;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Small architecture check, following this repository's existing reflection-
 * based pattern (no new framework introduced for this): the contract module
 * must stay transport-neutral and free of any dependency on controlplane,
 * the Invocation implementation module, the Dispatcher, the Runtime
 * Registry/Runtime, Spring Web, or persistence/JDBC.
 */
class InvocationContractBoundaryTest {

    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of(
            "com.funchole.backend.controlplane",
            "com.funchole.backend.invocation.",
            "com.funchole.backend.dispatcher",
            "com.funchole.backend.runtimeregistry",
            "com.funchole.backend.runtime",
            "org.springframework",
            "java.sql",
            "javax.sql"
    );

    private static final List<Class<?>> CONTRACT_TYPES = List.of(
            FunctionVersionInvocationHandoff.class,
            DirectInvocationRequest.class,
            DirectInvocationResult.class
    );

    @Test
    void contractTypesHaveNoForbiddenDependency() {
        for (Class<?> type : CONTRACT_TYPES) {
            for (Field field : type.getDeclaredFields()) {
                assertTypeIsAllowed(field.getType());
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    assertTypeIsAllowed(parameterType);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                assertTypeIsAllowed(method.getReturnType());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertTypeIsAllowed(parameterType);
                }
            }
        }
    }

    private void assertTypeIsAllowed(Class<?> type) {
        if (type.isPrimitive()) {
            return;
        }
        String packageName = type.getPackageName();
        for (String forbidden : FORBIDDEN_PACKAGE_PREFIXES) {
            assertThat(packageName.startsWith(forbidden))
                    .as("type %s must not belong to package %s", type.getName(), forbidden)
                    .isFalse();
        }
    }
}
