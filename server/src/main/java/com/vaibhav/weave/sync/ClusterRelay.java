package com.vaibhav.weave.sync;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.*;
import org.springframework.stereotype.Component;

@Component
public class ClusterRelay {
    public record Event(String source,UUID boardId,String type,Map<String,Object> payload) {}
    private final String instanceId=UUID.randomUUID().toString();
    private final StringRedisTemplate redis; private final ObjectMapper json; private final String channel; private final boolean enabled;
    private final List<Consumer<Event>> consumers=new CopyOnWriteArrayList<>();
    public ClusterRelay(StringRedisTemplate redis,ObjectMapper json,@Value("${weave.redis.channel}") String channel,@Value("${weave.redis.enabled}") boolean enabled){this.redis=redis;this.json=json;this.channel=channel;this.enabled=enabled;}
    public String instanceId(){return instanceId;}
    public void subscribe(Consumer<Event> consumer){consumers.add(consumer);}
    public void publish(UUID board,String type,Map<String,Object> payload){
        if(!enabled)return;
        try{redis.convertAndSend(channel,json.writeValueAsString(new Event(instanceId,board,type,payload)));}
        catch(Exception failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Redis relay unavailable; database catch-up remains active");}
    }
    public void receive(byte[] message){
        try{var event=json.readValue(new String(message,StandardCharsets.UTF_8),Event.class);if(!instanceId.equals(event.source()))consumers.forEach(c->c.accept(event));}
        catch(Exception failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not process relay notification",failure);}
    }
    @Configuration
    static class RedisConfiguration {
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="weave.redis.enabled",havingValue="true")
        RedisMessageListenerContainer redisListener(RedisConnectionFactory factory,ClusterRelay relay,@Value("${weave.redis.channel}") String channel){
            var container=new RedisMessageListenerContainer();container.setConnectionFactory(factory);
            container.addMessageListener((message,pattern)->relay.receive(message.getBody()),new ChannelTopic(channel));return container;
        }
    }
}

