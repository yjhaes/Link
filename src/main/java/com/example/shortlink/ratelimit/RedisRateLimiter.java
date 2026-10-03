package com.example.shortlink.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.net.InetAddress;
@Component
public class RedisRateLimiter implements RateLimiter, DisposableBean {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
        local capacity = tonumber(ARGV[1])
        local interval = tonumber(ARGV[2])
        local time = redis.call('TIME')
        local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
        local state = redis.call('HMGET', KEYS[1], 'tokens', 'at')
        local tokens = capacity
        local at = now
        if state[1] or state[2] then
          tokens = tonumber(state[1])
          at = tonumber(state[2])
          if not tokens or not at or tokens ~= tokens or at ~= at
             or tokens < 0 or tokens > capacity or at < 0 or at > 9007199254740991 then return -1 end
          if at > now then return -1 end
          tokens = math.min(capacity, tokens + (now - at) / interval)
        end
        local wait = 0
        if tokens >= 1 then tokens = tokens - 1
        else wait = math.max(1, math.ceil((1 - tokens) * interval)) end
        redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'at', tostring(now))
        redis.call('PEXPIRE', KEYS[1], capacity * interval)
        return wait
        """, Long.class);
    private final RedisClient client;
    private final RateLimitProperties properties;

    public RedisRateLimiter(RedisProperties redis, RateLimitProperties properties) {
        this.properties = properties;
        RedisURI uri;
        if (redis.getUrl() != null && !redis.getUrl().isBlank()) {
            uri = RedisURI.create(redis.getUrl());
        } else {
            var builder = RedisURI.Builder.redis(redis.getHost(), redis.getPort())
                    .withSsl(redis.getSsl().isEnabled());
            if (redis.getPassword() != null && !redis.getPassword().isEmpty()) {
                if (redis.getUsername() == null) builder.withPassword(redis.getPassword().toCharArray());
                else builder.withAuthentication(redis.getUsername(), redis.getPassword().toCharArray());
            }
            uri = builder.build();
        }
        uri.setDatabase(redis.getDatabase());
        uri.setTimeout(redis.getTimeout() == null ? Duration.ofMillis(200) : redis.getTimeout());
        client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(8)
                .socketOptions(SocketOptions.builder().connectTimeout(
                        redis.getConnectTimeout() == null ? Duration.ofMillis(200) : redis.getConnectTimeout()).build())
                .build());
    }

    @Override
    public Decision admitCreate(String peerAddress) {
        try {
            if (peerAddress == null || !peerAddress.matches("[0-9a-fA-F:.%]+")) {
                return Decision.unavailable();
            }
            String normalized = InetAddress.getByName(peerAddress).getHostAddress();
            String[] keys = {"shortlink:rate-limit:v1:create:" + normalized};
            String[] args = {Integer.toString(properties.createCapacity()), Long.toString(properties.refillMillis())};
            // Each admission gets a fresh bounded connection. No offline queue or reconnect replay;
            // a later HTTP request can reconnect safely after an unavailable decision.
            try (var connection = client.connect()) {
                var commands = connection.sync();
                Long wait;
                try {
                    wait = commands.evalsha(SCRIPT.getSha1(), ScriptOutputType.INTEGER, keys, args);
                } catch (RedisNoScriptException missingScript) {
                    // NOSCRIPT proves this command did not execute. This is the only safe retry.
                    wait = commands.eval(SCRIPT.getScriptAsString(), ScriptOutputType.INTEGER, keys, args);
                }
                if (wait == null || wait < 0) {
                    return Decision.unavailable();
                }
                return wait == 0 ? Decision.allowed() : Decision.rejected(wait);
            }
        } catch (Exception exception) {
            // A timeout may already have spent a token. Never retry an uncertain command here.
            return Decision.unavailable();
        }
    }

    @Override
    public void destroy() {
        client.shutdown();
    }
}
