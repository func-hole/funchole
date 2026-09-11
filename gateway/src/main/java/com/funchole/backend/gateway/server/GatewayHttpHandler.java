package com.funchole.backend.gateway.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.gateway.GatewayRegistry;
import com.funchole.backend.gateway.GatewayRequestContext;
import com.funchole.backend.gateway.GatewayRuntimeEntry;
import com.funchole.backend.gateway.flow.FlowResolution;
import com.funchole.backend.gateway.flow.FlowResolver;
import com.funchole.backend.invocation.CreateInvocationRequest;
import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationRegistry;
import com.funchole.backend.invocation.InvocationStatus;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public final class GatewayHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger logger = LoggerFactory.getLogger(GatewayHttpHandler.class);

    private final ObjectMapper objectMapper;
    private final GatewayRegistry gatewayRegistry;
    private final FlowResolver flowResolver;
    private final InvocationRegistry invocationRegistry;
    private final PendingInvocationResponseRegistry pendingResponseRegistry;

    public GatewayHttpHandler(
            ObjectMapper objectMapper,
            GatewayRegistry gatewayRegistry,
            FlowResolver flowResolver,
            InvocationRegistry invocationRegistry,
            PendingInvocationResponseRegistry pendingResponseRegistry
    ) {
        this.objectMapper = objectMapper;
        this.gatewayRegistry = gatewayRegistry;
        this.flowResolver = flowResolver;
        this.invocationRegistry = invocationRegistry;
        this.pendingResponseRegistry = pendingResponseRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
        GatewayRequestContext requestContext = toRequestContext(request);
        logger.info(
                "Gateway request received: method={}, host={}, path={}",
                requestContext.method(),
                requestContext.hostname(),
                requestContext.path()
        );

        if ("/health".equals(requestContext.path())) {
            writeJson(context, HttpResponseStatus.OK, Map.of(
                    "success", true,
                    "service", "gateway",
                    "transport", "raw-netty",
                    "protocol", "https",
                    "status", "ok",
                    "registeredGateways", gatewayRegistry.entries().size()
            ));
            return;
        }

        if (requestContext.hostname().isBlank()) {
            writeJson(context, HttpResponseStatus.BAD_REQUEST, Map.of(
                    "success", false,
                    "message", "Host header is required"
            ));
            return;
        }

        GatewayRuntimeEntry gateway = gatewayRegistry.findByHostname(requestContext.hostname());
        if (gateway == null) {
            logger.info(
                    "Gateway request rejected: reason=unknown-host, host={}, path={}",
                    requestContext.hostname(),
                    requestContext.path()
            );
            writeJson(context, HttpResponseStatus.NOT_FOUND, Map.of(
                    "success", false,
                    "message", "Gateway host not found",
                    "host", requestContext.hostname(),
                    "path", requestContext.path()
            ));
            return;
        }

        Optional<FlowResolution> resolution = flowResolver.resolve(gateway, requestContext);
        if (resolution.isEmpty()) {
            logger.info(
                    "Gateway request rejected: reason=route-not-found, host={}, method={}, path={}",
                    requestContext.hostname(),
                    requestContext.method(),
                    requestContext.path()
            );
            writeJson(context, HttpResponseStatus.NOT_FOUND, Map.of(
                    "success", false,
                    "message", "Route not found",
                    "host", requestContext.hostname(),
                    "path", requestContext.path(),
                    "method", requestContext.method()
            ));
            return;
        }

        FlowResolution flow = resolution.get();
        Invocation invocation = invocationRegistry.create(new CreateInvocationRequest(
                flow.flowId(),
                flow.flowKey(),
                flow.flowVersionId(),
                buildInvocationInput(request, requestContext)
        ));

        logger.info(
                "Gateway invocation created: invocationId={}, flowKey={}, flowVersionId={}, host={}, method={}, path={}",
                invocation.invocationId(),
                invocation.flowKey(),
                invocation.flowVersionId(),
                requestContext.hostname(),
                requestContext.method(),
                requestContext.path()
        );

        UUID invocationId = invocation.invocationId();
        context.channel().closeFuture().addListener(future -> pendingResponseRegistry.cancel(invocationId));
        pendingResponseRegistry.register(invocationId, outcome -> {
            switch (outcome) {
                case COMPLETED -> completeInvocationResponse(context, invocationId);
                case TIMED_OUT -> writeTimeoutResponse(context, invocationId);
            }
        });
        reconcileWithDurableState(context, invocationId);
    }

    /**
     * Closes the lost-wakeup race: the Invocation Registry publishes
     * INVOCATION_READY (and its terminal event is published after the durable
     * terminal persistence) during {@code InvocationRegistry.create()}, which
     * happens BEFORE this method registers the pending HTTP correlation. A
     * very fast pipeline can therefore deliver (and effectively drop) the
     * terminal event before registration, leaving the client waiting until
     * timeout.
     *
     * Once the pending entry exists, a single durable re-read recovers the
     * completion: if the Invocation is already terminal, complete through the
     * registry. A terminal event that arrives after registration resolves the
     * entry first and wins normally; if it arrived before, this reconcile
     * catches it. The registry removes the entry exactly once, so duplicate
     * terminal delivery (event + reconcile) can never write the HTTP response
     * twice.
     */
    private void reconcileWithDurableState(ChannelHandlerContext context, UUID invocationId) {
        Optional<Invocation> reconciled = invocationRegistry.findById(invocationId);
        if (reconciled.isEmpty()) {
            return;
        }
        InvocationStatus status = reconciled.get().status();
        if (status == InvocationStatus.COMPLETED || status == InvocationStatus.FAILED) {
            pendingResponseRegistry.complete(invocationId);
        }
    }

    /**
     * Runs on whatever thread resolved the pending entry (the NATS listener
     * thread for a real completion, this handler's own timeout executor for
     * a timeout) - so the actual write is always dispatched onto the
     * channel's own event loop rather than touching Netty state directly
     * from a foreign thread.
     */
    private void completeInvocationResponse(ChannelHandlerContext context, UUID invocationId) {
        context.channel().eventLoop().execute(() -> {
            Optional<Invocation> invocation = invocationRegistry.findById(invocationId);
            if (invocation.isEmpty()) {
                writeJson(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of(
                        "success", false,
                        "message", "Invocation not found after completion",
                        "invocationId", invocationId.toString()
                ));
                return;
            }
            writeInvocationOutcome(context, invocation.get());
        });
    }

    private void writeTimeoutResponse(ChannelHandlerContext context, UUID invocationId) {
        context.channel().eventLoop().execute(() -> writeJson(context, HttpResponseStatus.GATEWAY_TIMEOUT, Map.of(
                "success", false,
                "message", "Timed out waiting for invocation completion",
                "invocationId", invocationId.toString()
        )));
    }

    private void writeInvocationOutcome(ChannelHandlerContext context, Invocation invocation) {
        if (invocation.status() == InvocationStatus.COMPLETED) {
            writeFinalResponse(context, invocation);
            return;
        }
        if (invocation.status() == InvocationStatus.FAILED) {
            writeJson(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of(
                    "success", false,
                    "message", "Invocation failed",
                    "invocationId", invocation.invocationId().toString()
            ));
            return;
        }
        // Defensive: the completion event fired but the durable read still shows a
        // non-terminal status (e.g. a duplicate/out-of-order notification races a
        // read). Treat it the same as a failure rather than guessing at a body.
        writeJson(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of(
                "success", false,
                "message", "Invocation did not reach a terminal state",
                "invocationId", invocation.invocationId().toString()
        ));
    }

    /**
     * Builds the client-facing HTTP response from the durable RESPONSE step
     * output persisted on the Invocation: {"status": &lt;int&gt;, "body": &lt;any&gt;}.
     */
    private void writeFinalResponse(ChannelHandlerContext context, Invocation invocation) {
        try {
            JsonNode responseNode = invocation.result() == null ? null : objectMapper.readTree(invocation.result());
            int status = responseNode != null && responseNode.hasNonNull("status")
                    ? responseNode.get("status").asInt(200)
                    : 200;
            JsonNode body = responseNode != null ? responseNode.get("body") : null;
            byte[] responseBody = objectMapper.writeValueAsBytes(body == null ? objectMapper.nullNode() : body);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(status),
                    Unpooled.wrappedBuffer(responseBody)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseBody.length);
            context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception exception) {
            writeText(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Failed to build final response: " + exception.getMessage());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        writeText(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Gateway error: " + cause.getMessage());
    }

    private GatewayRequestContext toRequestContext(FullHttpRequest request) {
        return new GatewayRequestContext(
                request.method().name(),
                normalizeHostname(request.headers().get(HttpHeaderNames.HOST)),
                sanitizePath(request.uri()),
                request.uri()
        );
    }

    private String sanitizePath(String uri) {
        int queryIndex = uri.indexOf('?');
        String path = queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
        return path == null || path.isBlank() ? "/" : path;
    }

    private String normalizeHostname(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return "";
        }

        String normalized = hostHeader.trim().toLowerCase();
        if (normalized.startsWith("[")) {
            int closingIndex = normalized.indexOf(']');
            return closingIndex >= 0 ? normalized.substring(0, closingIndex + 1) : normalized;
        }

        int colonIndex = normalized.indexOf(':');
        return colonIndex >= 0 ? normalized.substring(0, colonIndex) : normalized;
    }

    private String buildInvocationInput(FullHttpRequest request, GatewayRequestContext requestContext) throws Exception {
        String body = request.content().toString(StandardCharsets.UTF_8);
        return objectMapper.writeValueAsString(Map.of(
                "method", requestContext.method(),
                "hostname", requestContext.hostname(),
                "path", requestContext.path(),
                "rawUri", requestContext.rawUri(),
                "body", body
        ));
    }

    private void writeJson(ChannelHandlerContext context, HttpResponseStatus status, Object payload) {
        byte[] responseBody;
        try {
            responseBody = objectMapper.writeValueAsBytes(payload);
        } catch (Exception exception) {
            writeText(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Gateway failed to serialize response: " + exception.getMessage());
            return;
        }
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(responseBody)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseBody.length);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void writeText(ChannelHandlerContext context, HttpResponseStatus status, String body) {
        byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(responseBody)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, responseBody.length);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
