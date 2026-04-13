package springbootjni.common; 

import lombok.Getter;

/**
 * 返回状态码枚举
 *
 * @author Literature Hub Team
 * @since 1.0.0
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    ERROR(500, "操作失败"),

    // 认证相关 2xx
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    TOKEN_EXPIRED(402, "Token已过期"),

    // 业务异常 4xx
    PARAM_ERROR(400, "参数错误"),
    NOT_FOUND(404, "资源不存在"),
    USER_NOT_FOUND(4001, "用户不存在"),
    PASSWORD_ERROR(4002, "密码错误"),
    USER_EXISTED(4003, "用户已存在"),

    // 系统异常 5xx
    SYSTEM_ERROR(500, "系统异常"),
    DATABASE_ERROR(5001, "数据库异常");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
