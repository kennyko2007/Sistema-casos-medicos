package bo.edu.casosmedicos.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final ChatHandshakeInterceptor interceptor;

    public WebSocketConfig(ChatWebSocketHandler handler, ChatHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Sin setAllowedOrigins: Spring solo acepta conexiones del mismo origen (http://IP:8080 -> ws://IP:8080)
        registry.addHandler(handler, "/ws/chat").addInterceptors(interceptor);
    }

    /** Límite de tamaño de mensaje entrante (8 KB). */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8192);
        return container;
    }
}