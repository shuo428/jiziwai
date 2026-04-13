package springbootjni.service;

public interface JNICallbackService {

    public void onDataReceived(byte[] data);

    public void onImageCaptured(int index, byte[] data);
}
