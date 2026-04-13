package springbootjni.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import lombok.RequiredArgsConstructor;
import springbootjni.handler.WebSocketHandler;

@Configuration              // 声明这是一个配置类
@EnableWebSocket             // 启用 Spring WebSocket 功能
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {


    private final WebSocketHandler webSocketHandler;

    /**
     * 注册 WebSocket 处理器
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {

        registry
            // 指定 WebSocket 处理器
            .addHandler(webSocketHandler, "/ws")

            // 允许跨域（前端一般单独部署，必须加）
            .setAllowedOrigins("*");
    }

}
