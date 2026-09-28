package com.example.shortlink.shortlink.service;

import com.example.shortlink.common.error.InvalidRequestException;
import com.example.shortlink.common.error.LinkNotFoundException;
import com.example.shortlink.shortlink.persistence.ShortLinkEntity;
import com.example.shortlink.shortlink.persistence.ShortLinkMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
public class ShortLinkService {

    private final ShortLinkMapper shortLinkMapper;
    private final ShortCodeGenerator shortCodeGenerator;
    private final Clock clock;

    public ShortLinkService(
            ShortLinkMapper shortLinkMapper,
            ShortCodeGenerator shortCodeGenerator,
            Clock clock) {
        this.shortLinkMapper = shortLinkMapper;
        this.shortCodeGenerator = shortCodeGenerator;
        this.clock = clock;
    }

    public String createPermanent(String originalUrl) {
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new InvalidRequestException("originalUrl is required.");
        }
        if (originalUrl.length() > 4096) {
            throw new InvalidRequestException("originalUrl exceeds 4096 characters.");
        }
        if (!originalUrl.equals(originalUrl.strip())) {
            throw new InvalidRequestException("originalUrl must not have leading or trailing whitespace.");
        }
        if (originalUrl.chars().anyMatch(character -> character > 0x7f)) {
            throw new InvalidRequestException("originalUrl must use ASCII URI characters.");
        }
        validateHttpUri(originalUrl);

        LocalDateTime createdAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
        ShortLinkEntity entity = new ShortLinkEntity();
        entity.setShortCode(shortCodeGenerator.generate());
        entity.setOriginalUrl(originalUrl);
        entity.setCreatedAt(createdAt);
        entity.setExpiresAt(null);
        entity.setEnabled(true);
        shortLinkMapper.insert(entity);

        return entity.getShortCode();
    }

    public String findOriginalUrl(String code) {
        if (code == null || !code.matches("[a-z0-9]{8}")) {
            throw new LinkNotFoundException();
        }

        ShortLinkEntity entity = shortLinkMapper.selectById(code);
        if (entity == null) {
            throw new LinkNotFoundException();
        }
        return entity.getOriginalUrl();
    }

    private void validateHttpUri(String originalUrl) {
        try {
            URI uri = new URI(originalUrl);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) {
                throw new InvalidRequestException("originalUrl must be an absolute HTTP or HTTPS URI with a host.");
            }
        } catch (URISyntaxException exception) {
            throw new InvalidRequestException("originalUrl is not a valid URI.");
        }
    }
}
