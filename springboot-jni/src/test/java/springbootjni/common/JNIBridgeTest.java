package springbootjni.common;

import org.junit.jupiter.api.Test;

import springbootjni.jni.JNIBridge;

public class JNIBridgeTest {

    @Test
    public void test() throws InterruptedException {
        JNIBridge jniBridge = new JNIBridge();
        jniBridge.startListening();
        Thread.currentThread().join();
    }
}
