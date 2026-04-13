package springbootjni.exception;
import cn.dev33.satoken.exception.NotLoginException;
import springbootjni.common.ResultCode;
import springbootjni.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<?> handleBusinessException(BusinessException e) {
        System.out.println("业务异常: " + e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理Sa-Token未登录异常
     */
    @ExceptionHandler(NotLoginException.class)
    public ApiResponse<?> handleNotLoginException(NotLoginException e) {
        System.out.println("未登录异常: " + e.getMessage());
        return ApiResponse.error(ResultCode.UNAUTHORIZED);
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(BindException.class)
    public ApiResponse<?> handleBindException(BindException e) {
        System.out.println("参数校验异常: " + e.getMessage());
        String message = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : "参数校验失败";
        return ApiResponse.error(ResultCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 处理其他异常
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<?> handleException(Exception e) {
        System.out.println("系统异常: " + e.getMessage());
        return ApiResponse.error(ResultCode.SYSTEM_ERROR);
    }
}
