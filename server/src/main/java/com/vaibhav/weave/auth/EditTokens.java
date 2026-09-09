package com.vaibhav.weave.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
public final class EditTokens {
    private final byte[] secret;
    @Value("${weave.token-lifetime-seconds:604800}") private long lifetime=604800;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    public EditTokens(@Value("${weave.token-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if(this.secret.length < 32) throw new IllegalArgumentException("WEAVE_TOKEN_SECRET must contain at least 32 bytes");
    }
    public String issue(UUID boardId) { return issue(boardId, Instant.now().plusSeconds(lifetime).getEpochSecond()); }
    public String issue(UUID boardId, long expires) {
        String payload = boardId + "." + expires;
        return payload + "." + ENCODER.encodeToString(sign(payload));
    }
    public long verify(UUID boardId, String token) {
        try {
            String[] parts = token.split("\\.");
            if(parts.length != 3 || !parts[0].equals(boardId.toString())) throw new IllegalArgumentException();
            long expiry = Long.parseLong(parts[1]);
            if(expiry <= Instant.now().getEpochSecond() || !MessageDigest.isEqual(sign(parts[0] + "." + parts[1]), Base64.getUrlDecoder().decode(parts[2]))) throw new IllegalArgumentException();
            return expiry;
        } catch(RuntimeException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired board link"); }
    }
    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public void authorize(UUID boardId, String authorization) {
        if(authorization == null || !authorization.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        verify(boardId, authorization.substring(7));
    }
}

