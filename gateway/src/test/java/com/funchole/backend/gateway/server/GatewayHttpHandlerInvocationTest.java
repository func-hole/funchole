package com.funchole.backend.gateway.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.gateway.GatewayRegistry;
import com.funchole.backend.gateway.GatewayRegistrySnapshot;
import com.funchole.backend.gateway.GatewayRequestContext;
import com.funchole.backend.gateway.GatewayRuntimeEntry;
import com.funchole.backend.gateway.flow.FlowResolution;
import com.funchole.backend.gateway.flow.FlowResolver;
import com.funchole.backend.invocation.CreateInvocationRequest;
import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationRegistry;
import com.funchole.backend.invocation.InvocationStatus;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayHttpHandlerInvocationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID GATEWAY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID FLOW_ID = UUID.fromString("55555555-5555-5555-5555-555555555553");
    private static final UUID FLOW_VERSION_ID = UUID.fromString("66666666-6666-6666-6666-666666666663");
    private static final UUID INVOCATION_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Test
    void createsInvocationFromResolvedFlowVersion() throws Exception {
        GatewayRuntimeEntry gateway = new GatewayRuntimeEntry(
                GATEWAY_ID,
                "Primary Gateway",
                "a6n1y8",
                "funchole.test",
                "a6n1y8.funchole.test",
                null,
                null
        );
        GatewayRegistry registry = new GatewayRegistry(new GatewayRegistrySnapshot(
                Map.of(gateway.hostname(), gateway),
                null,
                Map.of()
        ));
        CountingFlowResolver flowResolver = new CountingFlowResolver(new FlowResolution(
                FLOW_ID,
                "flw_checkout",
                FLOW_VERSION_ID
        ));
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        GatewayHttpHandler handler = new GatewayHttpHandler(
                OBJECT_MAPPER,
                registry,
                flowResolver,
                invocationRegistry
        );
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/checkout",
                Unpooled.copiedBuffer("{\"total\":100}", StandardCharsets.UTF_8)
        );
        request.headers().set(HttpHeaderNames.HOST, gateway.hostname());

        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        JsonNode payload = OBJECT_MAPPER.readTree(response.content().toString(StandardCharsets.UTF_8));

        assertEquals(HttpResponseStatus.ACCEPTED, response.status());
        assertEquals(1, flowResolver.calls);
        assertEquals(FLOW_ID, invocationRegistry.request.flowId());
        assertEquals("flw_checkout", invocationRegistry.request.flowKey());
        assertEquals(FLOW_VERSION_ID, invocationRegistry.request.flowVersionId());
        assertTrue(invocationRegistry.request.inputPayload().contains("\"path\":\"/checkout\""));
        assertTrue(invocationRegistry.request.inputPayload().contains("\"body\":\"{\\\"total\\\":100}\""));
        assertEquals(INVOCATION_ID.toString(), payload.at("/data/invocationId").asText());
        assertEquals("flw_checkout", payload.at("/data/flowKey").asText());
        assertEquals(FLOW_VERSION_ID.toString(), payload.at("/data/flowVersionId").asText());
        assertEquals("PENDING", payload.at("/data/status").asText());
    }

    private static final class CountingFlowResolver implements FlowResolver {
        private final FlowResolution resolution;
        private int calls;

        private CountingFlowResolver(FlowResolution resolution) {
            this.resolution = resolution;
        }

        @Override
        public Optional<FlowResolution> resolve(GatewayRuntimeEntry gateway, GatewayRequestContext requestContext) {
            calls++;
            return Optional.of(resolution);
        }
    }

    private static final class CapturingInvocationRegistry implements InvocationRegistry {
        private CreateInvocationRequest request;

        @Override
        public Invocation create(CreateInvocationRequest request) {
            this.request = request;
            return new Invocation(
                    INVOCATION_ID,
                    request.flowId(),
                    request.flowKey(),
                    request.flowVersionId(),
                    InvocationStatus.PENDING,
                    request.inputPayload(),
                    OffsetDateTime.now(),
                    OffsetDateTime.now()
            );
        }

        @Override
        public Optional<Invocation> findById(UUID invocationId) {
            return Optional.empty();
        }
    }
}
