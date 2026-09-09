package com.vaibhav.weave.board;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    @Value("${weave.redis.enabled:true}") private boolean redisEnabled;
    public HealthController(JdbcTemplate jdbc,StringRedisTemplate redis){this.jdbc=jdbc;this.redis=redis;}
    @GetMapping("/api/v1/health")
    public ResponseEntity<Map<String,String>> health(){
        try {
            jdbc.queryForObject("SELECT 1",Integer.class);
            if(redisEnabled)try(var connection=redis.getConnectionFactory().getConnection()){
                if(!"PONG".equals(connection.ping()))throw new IllegalStateException();
            }
            return ResponseEntity.ok(Map.of("status","ok"));
        }catch(Exception error){return ResponseEntity.status(503).body(Map.of("status","unavailable"));}
    }
}
