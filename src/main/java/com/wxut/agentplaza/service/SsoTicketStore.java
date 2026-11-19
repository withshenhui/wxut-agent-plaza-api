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

    public static class Entry {
        public final String username;
        public final String name;
        final long expiresAt;
        Entry(String username, String name, long expiresAt) {
            this.username = username;
            this.name = name;
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
        return create(username, null);
    }

    /** 带显示名创建（如 Dify 开户需要 CAS cn 姓名；xixi 等旧调用方不传） */
    public String create(String username, String name) {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        tickets.put(ticket, new Entry(username, name, System.currentTimeMillis() + ttlMs));
        cleanup();
        return ticket;
    }

    /** 一次性消费：有效返回票据条目（用户名 + 显示名），否则返回 null */
    public Entry consume(String ticket) {
        if (ticket == null || ticket.isEmpty()) {
            return null;
        }
        Entry e = tickets.remove(ticket);
        if (e == null || System.currentTimeMillis() >= e.expiresAt) {
            return null;
        }
        return e;
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
