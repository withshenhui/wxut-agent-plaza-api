package com.wxut.agentplaza.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expiration;

    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                           @Value("${jwt.expiration}") long expiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    public String generateToken(Long userId, String username, String role) {
        return generateToken(userId, username, role, null, false);
    }

    /** 扩展签发（RBAC）：cid=学院id、chatAll=全校查询授权，供查询网关校验数据权限 */
    public String generateToken(Long userId, String username, String role, Integer collegeId, boolean chatAll) {
        Date now = new Date();
        JwtBuilder b = Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role);
        if (collegeId != null) b.claim("cid", collegeId);
        if (chatAll) b.claim("pem", "chat.all");
        return b.issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(key)
                .compact();
    }

    public Long getUserId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    public String getUsername(String token) {
        return getClaims(token).get("username", String.class);
    }

    public String getRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
