package com.vaibhav.weave.websocket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final WeaveWebSocketHandler handler;
    private final String[] origins;
    public WebSocketConfiguration(WeaveWebSocketHandler handler,@Value("${weave.allowed-origins}") String[] origins){this.handler=handler;this.origins=origins;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(handler,"/ws").setAllowedOrigins(origins);}
}
