package springbootjni.service.impl;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import springbootjni.handler.WebSocketHandler;
import springbootjni.service.JNICallbackService;

@Service
@RequiredArgsConstructor
public class JNICallbackServiceImpl implements JNICallbackService {
    private final WebSocketHandler webSocketHandler;

    @Override
    public void onDataReceived(byte[] data) {
        webSocketHandler.broadcast(new String(data));
    }

    @Override
    public void onImageCaptured(int index, byte[] data) {
        webSocketHandler.broadcast(new String(data));
    }
    
}
