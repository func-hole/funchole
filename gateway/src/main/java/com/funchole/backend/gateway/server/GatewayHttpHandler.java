package com.funchole.backend.gateway.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.gateway.GatewayRegistry;
import com.funchole.backend.gateway.GatewayRequestContext;
import com.funchole.backend.gateway.GatewayRuntimeEntry;
import com.funchole.backend.gateway.flow.FlowResolver;
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

@ChannelHandler.Sharable
public final class GatewayHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final ObjectMapper objectMapper;
    private final GatewayRegistry gatewayRegistry;
    private final FlowResolver flowResolver;

    public GatewayHttpHandler(ObjectMapper objectMapper, GatewayRegistry gatewayRegistry, FlowResolver flowResolver) {
        this.objectMapper = objectMapper;
        this.gatewayRegistry = gatewayRegistry;
        this.flowResolver = flowResolver;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
        GatewayRequestContext requestContext = toRequestContext(request);

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
            writeJson(context, HttpResponseStatus.NOT_FOUND, Map.of(
                    "success", false,
                    "message", "Gateway host not found",
                    "host", requestContext.hostname(),
                    "path", requestContext.path()
            ));
            return;
        }

        if (flowResolver.resolve(gateway, requestContext).isEmpty()) {
            writeJson(context, HttpResponseStatus.OK, Map.of(
                    "success", true,
                    "message", "Gateway ready for taking request",
                    "data", Map.of(
                            "gatewayId", gateway.gatewayId().toString(),
                            "gatewayName", gateway.gatewayName(),
                            "gatewayKey", gateway.gatewayKey(),
                            "domainName", gateway.domainName(),
                            "hostname", gateway.hostname(),
                            "path", requestContext.path(),
                            "method", requestContext.method(),
                            "flowResolved", false
                    )
            ));
            return;
        }

        writeJson(context, HttpResponseStatus.NOT_FOUND, Map.of(
                "success", false,
                "message", "Route not found",
                "host", requestContext.hostname(),
                "path", requestContext.path()
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
