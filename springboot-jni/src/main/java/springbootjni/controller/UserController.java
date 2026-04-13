package springbootjni.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import springbootjni.dto.ApiResponse;
import springbootjni.dto.LoginResponse;
import springbootjni.entity.LoginRequest;
import springbootjni.entity.User;
import springbootjni.service.UserService;

import java.util.Map;

/**
 * 用户控制器
 */
@RestController
@RequestMapping("/api/user")
@CrossOrigin
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public ApiResponse<User> register(@RequestBody Map<String, String> params) {
        try {
            String username = params.get("username");
            String password = params.get("password");

            if (username == null || username.trim().isEmpty()) {
                return ApiResponse.error("用户名不能为空");
            }
            if (password == null || password.trim().isEmpty()) {
                return ApiResponse.error("密码不能为空");
            }

            User user = userService.register(username, password);
            return ApiResponse.success(user);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        try {
            String username = request.getUsername();
            String password = request.getPassword();

            if (username == null || username.trim().isEmpty()) {
                return ApiResponse.error("用户名不能为空");
            }
            if (password == null || password.trim().isEmpty()) {
                return ApiResponse.error("密码不能为空");
            }

            User user = userService.login(username, password);

            LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo();
            userInfo.setId(user.getId());
            userInfo.setUsername(user.getUsername());

            // 登录成功，生成 token
            StpUtil.login(user.getId());
            String token = StpUtil.getTokenValue();

            LoginResponse loginDto = new LoginResponse();
            loginDto.setToken(token);
            loginDto.setUserInfo(userInfo);

            return ApiResponse.success(loginDto);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public ApiResponse<String> logout() {
        try {
            StpUtil.logout();
            return ApiResponse.success("退出成功");
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/info")
    public ApiResponse<User> getUserInfo() {
        try {
            // 检查是否登录
            StpUtil.checkLogin();
            Long userId = StpUtil.getLoginIdAsLong();

            User user = userService.getUserById(userId);
            return ApiResponse.success(user);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 修改密码
     */
    @PostMapping("/changePassword")
    public ApiResponse<String> changePassword(@RequestBody Map<String, String> params) {
        try {
            // 检查是否登录
            StpUtil.checkLogin();
            Long userId = StpUtil.getLoginIdAsLong();

            String oldPassword = params.get("oldPassword");
            String newPassword = params.get("newPassword");

            if (oldPassword == null || oldPassword.trim().isEmpty()) {
                return ApiResponse.error("旧密码不能为空");
            }
            if (newPassword == null || newPassword.trim().isEmpty()) {
                return ApiResponse.error("新密码不能为空");
            }

            boolean success = userService.changePassword(userId, oldPassword, newPassword);
            if (success) {
                return ApiResponse.success("密码修改成功");
            } else {
                return ApiResponse.error("密码修改失败");
            }
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
