package com.funchole.backend.gateway.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.gateway.GatewayRegistry;
import com.funchole.backend.gateway.GatewayRequestContext;
import com.funchole.backend.gateway.GatewayRuntimeEntry;
import com.funchole.backend.gateway.flow.FlowResolution;
import com.funchole.backend.gateway.flow.FlowResolver;
import com.funchole.backend.invocation.CreateInvocationRequest;
import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationRegistry;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public final class GatewayHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger logger = LoggerFactory.getLogger(GatewayHttpHandler.class);

    private final ObjectMapper objectMapper;
    private final GatewayRegistry gatewayRegistry;
    private final FlowResolver flowResolver;
    private final InvocationRegistry invocationRegistry;

    public GatewayHttpHandler(
            ObjectMapper objectMapper,
            GatewayRegistry gatewayRegistry,
            FlowResolver flowResolver,
            InvocationRegistry invocationRegistry
    ) {
        this.objectMapper = objectMapper;
        this.gatewayRegistry = gatewayRegistry;
        this.flowResolver = flowResolver;
        this.invocationRegistry = invocationRegistry;
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

        writeJson(context, HttpResponseStatus.ACCEPTED, Map.of(
                "success", true,
                "message", "Invocation created",
                "data", Map.of(
                        "invocationId", invocation.invocationId().toString(),
                        "flowKey", invocation.flowKey(),
                        "flowVersionId", invocation.flowVersionId().toString(),
                        "status", invocation.status().name()
                )
        ));
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

    private void writeJson(ChannelHandlerContext context, HttpResponseStatus status, Object payload) throws Exception {
        byte[] responseBody = objectMapper.writeValueAsBytes(payload);
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
