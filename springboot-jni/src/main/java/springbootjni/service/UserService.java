package springbootjni.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import springbootjni.common.ResultCode;
import springbootjni.entity.User;
import springbootjni.exception.BusinessException;
import springbootjni.mapper.UserMapper;
import springbootjni.util.PasswordUtil;

/**
 * 用户服务类
 */
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    /**
     * 用户注册
     * 
     * @param username    用户名
     * @param rawPassword 原始密码
     * @return 注册成功的用户信息
     */
    public User register(String username, String rawPassword) {
        // 检查用户名是否已存在
        User existUser = userMapper.selectByUsername(username);
        if (existUser != null) {
            throw new RuntimeException("用户名已存在");
        }

        // 创建新用户
        User user = new User();
        user.setUsername(username);
        // 加密密码
        user.setPassword(PasswordUtil.encode(rawPassword));

        // 插入数据库
        int result = userMapper.insert(user);
        if (result > 0) {
            return user;
        }
        throw new RuntimeException("注册失败");
    }

    /**
     * 用户登录
     * 
     * @param username    用户名
     * @param rawPassword 原始密码
     * @return 登录成功的用户信息(密码字段会被清空)
     */
    public User login(String username, String rawPassword) {
        // 根据用户名查询用户
        User user = userMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "用户不存在");
        }

        // 验证密码
        if (!PasswordUtil.matches(rawPassword, user.getPassword())) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR, "密码错误");
        }

        // 清空密码字段，不返回给前端
        user.setPassword(null);
        return user;
    }

    /**
     * 根据用户名查询用户
     * 
     * @param username 用户名
     * @return 用户信息
     */
    public User getUserByUsername(String username) {
        User user = userMapper.selectByUsername(username);
        if (user != null) {
            // 清空密码字段
            user.setPassword(null);
        }
        return user;
    }

    /**
     * 根据ID查询用户
     * 
     * @param id 用户ID
     * @return 用户信息
     */
    public User getUserById(Long id) {
        User user = userMapper.selectById(id);
        if (user != null) {
            // 清空密码字段
            user.setPassword(null);
        }
        return user;
    }

    /**
     * 修改密码
     * 
     * @param userId      用户ID
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return 是否成功
     */
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "用户不存在");
        }

        // 验证旧密码
        if (!PasswordUtil.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(ResultCode.PASSWORD_ERROR, "旧密码错误");
        }

        // 更新密码
        user.setPassword(PasswordUtil.encode(newPassword));
        int result = userMapper.update(user);
        return result > 0;
    }

    /**
     * 删除用户(逻辑删除)
     * 
     * @param userId 用户ID
     * @return 是否成功
     */
    public boolean deleteUser(Long userId) {
        int result = userMapper.deleteById(userId);
        return result > 0;
    }
}
