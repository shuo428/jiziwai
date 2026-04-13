package springbootjni.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码加密工具类
 * 使用 BCrypt 算法进行密码加密
 */
public class PasswordUtil {

    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 加密密码
     * 
     * @param rawPassword 原始密码
     * @return 加密后的密码
     */
    public static String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 验证密码
     * 
     * @param rawPassword     原始密码
     * @param encodedPassword 加密后的密码
     * @return 是否匹配
     */
    public static boolean matches(String rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }

    /**
     * 测试示例
     */
    public static void main(String[] args) {
        String rawPassword = "123456";

        // 加密
        String encodedPassword = encode(rawPassword);
        System.out.println("原始密码: " + rawPassword);
        System.out.println("加密后: " + encodedPassword);

        // 验证
        boolean isMatch = matches(rawPassword, encodedPassword);
        System.out.println("验证结果: " + isMatch);

        // 每次加密结果都不同，但都可以验证成功
        String encodedPassword2 = encode(rawPassword);
        System.out.println("再次加密: " + encodedPassword2);
        System.out.println("验证结果: " + matches(rawPassword, encodedPassword2));
    }
}
