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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final ExecutorService invocationExecutor;

    public GatewayHttpHandler(
            ObjectMapper objectMapper,
            GatewayRegistry gatewayRegistry,
            FlowResolver flowResolver,
            InvocationRegistry invocationRegistry,
            PendingInvocationResponseRegistry pendingResponseRegistry,
            ExecutorService invocationExecutor
    ) {
        this.objectMapper = objectMapper;
        this.gatewayRegistry = gatewayRegistry;
        this.flowResolver = flowResolver;
        this.invocationRegistry = invocationRegistry;
        this.pendingResponseRegistry = pendingResponseRegistry;
        this.invocationExecutor = invocationExecutor;
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
        // FullHttpRequest buffers must only be touched on the event loop:
        // extract the request payload here, then offload the blocking
        // Invocation Registry work to the dedicated executor.
        String inputPayload = buildInvocationInput(request, requestContext);
        logger.info(
                "Gateway method resolved: flowKey={}, delegating invocation creation to executor, path={}",
                flow.flowKey(),
                requestContext.path()
        );
        createInvocationOnExecutor(context, requestContext, flow, inputPayload);
    }

    /**
     * Runs the blocking Invocation Registry work on the dedicated recursive
     * executor, never on the Netty event-loop thread. Netty channel state
     * (close-future listeners, pending-response registration, HTTP writes)
     * is touched only on the channel's own event loop.
     */
    private void createInvocationOnExecutor(
            ChannelHandlerContext context,
            GatewayRequestContext requestContext,
            FlowResolution flow,
            String inputPayload
    ) {
        invocationExecutor.execute(() -> {
            Invocation invocation;
            try {
                invocation = invocationRegistry.create(new CreateInvocationRequest(
                        flow.flowId(),
                        flow.flowKey(),
                        flow.flowVersionId(),
                        inputPayload
                ));
            } catch (RuntimeException failure) {
                logger.warn(
                        "Gateway invocation creation failed: host={}, path={}, message={}",
                        requestContext.hostname(),
                        requestContext.path(),
                        failure.getMessage()
                );
                runOnEventLoop(context, () -> writeText(
                        context,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "Gateway failed to create invocation: " + failure.getMessage()));
                return;
            }

            logger.info(
                    "Gateway invocation created: invocationId={}, flowKey={}, flowVersionId={}, host={}, method={}, path={}",
                    invocation.invocationId(),
                    invocation.flowKey(),
                    invocation.flowVersionId(),
                    requestContext.hostname(),
                    requestContext.method(),
                    requestContext.path()
            );

            registerPendingResponseOnEventLoop(
                    context,
                    invocation.invocationId(),
                    // Lost-wakeup reconciliation performs its own blocking
                    // durable read; it is chained AFTER the pending-response
                    // registration completes on the event loop, and runs on
                    // the executor thread - never the event loop.
                    () -> invocationExecutor.execute(() ->
                            reconcileWithDurableState(context, invocation.invocationId())));
        });
    }

    /**
     * Registers the pending HTTP correlation and the client-disconnect
     * cleanup listener on the channel event loop. {@code onRegistered} runs
     * on the event loop right after registration succeeds, so callers can
     * chain work that must strictly follow registration (they are then
     * responsible for offloading blocking work back off the event loop).
     */
    private void registerPendingResponseOnEventLoop(
            ChannelHandlerContext context,
            UUID invocationId,
            Runnable onRegistered
    ) {
        runOnEventLoop(context, () -> {
            context.channel().closeFuture().addListener(future -> pendingResponseRegistry.cancel(invocationId));
            pendingResponseRegistry.register(invocationId, outcome -> {
                switch (outcome) {
                    case COMPLETED -> completeInvocationResponse(context, invocationId);
                    case TIMED_OUT -> writeTimeoutResponse(context, invocationId);
                }
            });
            onRegistered.run();
        });
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
     * Terminal-response handling: the blocking durable Invocation lookup runs
     * on the executor; only the HTTP write touches the channel event loop.
     * Runs on whatever thread resolved the pending entry (NATS listener
     * thread or the timeout executor), so everything here is thread-safe.
     */
    private void completeInvocationResponse(ChannelHandlerContext context, UUID invocationId) {
        invocationExecutor.execute(() -> {
            Optional<Invocation> invocation;
            try {
                invocation = invocationRegistry.findById(invocationId);
            } catch (RuntimeException failure) {
                logger.warn(
                        "Gateway failed to read terminal Invocation: invocationId={}, message={}",
                        invocationId,
                        failure.getMessage()
                );
                runOnEventLoop(context, () -> writeText(
                        context,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "Gateway failed to read invocation completion: " + failure.getMessage()));
                return;
            }
            runOnEventLoop(context, () -> {
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
        });
    }

    private void runOnEventLoop(ChannelHandlerContext context, Runnable action) {
        context.channel().eventLoop().execute(action);
    }

    /**
     * Shuts down the dedicated Invocation executor with the Gateway
     * lifecycle. Blocks briefly so in-flight blocking registry work can
     * finish before the process exits.
     */
    public void close() {
        invocationExecutor.shutdown();
        try {
            if (!invocationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                invocationExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            invocationExecutor.shutdownNow();
        }
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
