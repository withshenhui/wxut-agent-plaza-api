package com.wxut.agentplaza.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSO 一次性票据：内存存储、60 秒 TTL、消费即作废。
 * 仅适用于单实例部署（当前 agent-pg-api 为单实例）。
 */
@Component
public class SsoTicketStore {

    private static final long DEFAULT_TTL_MS = 60_000L;

    private final long ttlMs;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tickets = new ConcurrentHashMap<>();

    private static class Entry {
        final String username;
        final long expiresAt;
        Entry(String username, long expiresAt) {
            this.username = username;
            this.expiresAt = expiresAt;
        }
    }

    public SsoTicketStore() {
        this(DEFAULT_TTL_MS);
    }

    SsoTicketStore(long ttlMs) { // 包级可见，供测试注入
        this.ttlMs = ttlMs;
    }

    public String create(String username) {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        tickets.put(ticket, new Entry(username, System.currentTimeMillis() + ttlMs));
        cleanup();
        return ticket;
    }

    /** 一次性消费：有效返回用户名，否则返回 null */
    public String consume(String ticket) {
        if (ticket == null || ticket.isEmpty()) {
            return null;
        }
        Entry e = tickets.remove(ticket);
        if (e == null || System.currentTimeMillis() >= e.expiresAt) {
            return null;
        }
        return e.username;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = tickets.entrySet().iterator();
        while (it.hasNext()) {
            if (now >= it.next().getValue().expiresAt) {
                it.remove();
            }
        }
    }
}
