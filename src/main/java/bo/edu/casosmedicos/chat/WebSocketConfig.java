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

    private final ChatWebSocketHandler chatHandler;
    private final ChatHandshakeInterceptor chatInterceptor;
    private final AvisosWebSocketHandler avisosHandler;
    private final AvisosHandshakeInterceptor avisosInterceptor;

    public WebSocketConfig(ChatWebSocketHandler chatHandler,
                           ChatHandshakeInterceptor chatInterceptor,
                           AvisosWebSocketHandler avisosHandler,
                           AvisosHandshakeInterceptor avisosInterceptor) {
        this.chatHandler = chatHandler;
        this.chatInterceptor = chatInterceptor;
        this.avisosHandler = avisosHandler;
        this.avisosInterceptor = avisosInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Sin setAllowedOrigins: Spring solo acepta conexiones del mismo origen
        registry.addHandler(chatHandler, "/ws/chat").addInterceptors(chatInterceptor);
        registry.addHandler(avisosHandler, "/ws/avisos").addInterceptors(avisosInterceptor);
    }

    /** Límite de tamaño de mensaje entrante (8 KB). */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8192);
        return container;
    }
}
