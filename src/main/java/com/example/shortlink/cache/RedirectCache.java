package com.example.shortlink.cache;


public interface RedirectCache {

    RedirectCacheRead find(String shortCode);

    boolean storeIfVersion(
            String shortCode,
            String generation,
            RedirectCacheRead.Status status,
            RedirectCacheEntry entry);

    String replaceVersion(String shortCode);
}
