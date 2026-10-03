package com.example.shortlink.stats.messaging;


import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("short-link.stats.rabbit")
public record VisitRabbitProperties(
        String host,
        Integer port,
        String username,
        String password,
        String virtualHost,
        Integer bufferCapacity,
        Integer unconfirmedLimit,
        Integer channelLimit,
        Long channelCheckoutMs,
        Integer connectionTimeoutMs,
        Integer handshakeTimeoutMs,
        Integer heartbeatSeconds,
        Boolean consumerEnabled) {
    public VisitRabbitProperties {
        host = host == null ? "localhost" : host;
        port = port == null ? 5672 : port;
        username = username == null ? "guest" : username;
        password = password == null ? "guest" : password;
        virtualHost = virtualHost == null ? "/" : virtualHost;
        bufferCapacity = bufferCapacity == null ? 256 : bufferCapacity;
        unconfirmedLimit = unconfirmedLimit == null ? 32 : unconfirmedLimit;
        channelLimit = channelLimit == null ? 16 : channelLimit;
        channelCheckoutMs = channelCheckoutMs == null ? 200L : channelCheckoutMs;
        connectionTimeoutMs = connectionTimeoutMs == null ? 500 : connectionTimeoutMs;
        handshakeTimeoutMs = handshakeTimeoutMs == null ? 1000 : handshakeTimeoutMs;
        heartbeatSeconds = heartbeatSeconds == null ? 10 : heartbeatSeconds;
        consumerEnabled = consumerEnabled == null ? true : consumerEnabled;
        if (port < 1
                || port > 65535
                || bufferCapacity < 1
                || unconfirmedLimit < 1
                || channelLimit < 1
                || channelCheckoutMs < 1
                || connectionTimeoutMs < 1
                || handshakeTimeoutMs < 1
                || heartbeatSeconds < 1)
            throw new IllegalArgumentException("Invalid visit RabbitMQ resource configuration.");
    }

    @Override
    public String toString() {
        return "VisitRabbitProperties[redacted]";
    }
}
