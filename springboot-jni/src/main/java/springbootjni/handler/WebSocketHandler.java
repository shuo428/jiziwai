package springbootjni.handler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class WebSocketHandler extends TextWebSocketHandler{
    /**
     * 保存所有已连接的 WebSocket 会话
     * CopyOnWriteArraySet 是线程安全的
     */
    private static final Set<WebSocketSession> sessions =
            new CopyOnWriteArraySet<>();

    /**
     * 连接建立成功后调用
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        System.out.println("客户端已连接，sessionId=" + session.getId());
    }

    /**
     * 接收到客户端文本消息时调用
     */
    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message
    ) throws Exception {

        String payload = message.getPayload();
        System.out.println("收到客户端消息：" + payload);

        // 示例：回一条消息
        session.sendMessage(new TextMessage("服务端已收到：" + payload));
    }

    /**
     * 连接关闭时调用
     */
    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            CloseStatus status
    ) {
        sessions.remove(session);
        System.out.println("连接关闭：" + session.getId());
    }

    /**
     * 异常发生时调用
     */
    @Override
    public void handleTransportError(
            WebSocketSession session,
            Throwable exception
    ) throws Exception {

        System.err.println("WebSocket异常：" + exception.getMessage());
    }

    /**
     * 对外暴露：主动向所有客户端推送消息
     */
    public void broadcast(String msg) {

        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(msg));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
