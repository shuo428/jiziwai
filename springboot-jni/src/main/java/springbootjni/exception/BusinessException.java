package springbootjni.exception;
import lombok.Getter;
import springbootjni.common.ResultCode;

/**
 * 业务异常
 *
 * @author Literature Hub Team
 * @since 1.0.0
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }
}
