package springbootjni.dto;

import lombok.Data;

@Data
public class LoginResponse {
    private UserInfo userInfo;
    private String token;

    @Data
    public static class UserInfo {
        private Long id;
        private String username;
    }
}
