package springbootjni.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import springbootjni.entity.User;

/**
 * 用户 Mapper 接口
 */
@Mapper
public interface UserMapper {

    /**
     * 根据用户名查询用户
     * 
     * @param username 用户名
     * @return 用户信息
     */
    User selectByUsername(@Param("username") String username);

    /**
     * 插入用户
     * 
     * @param user 用户信息
     * @return 影响行数
     */
    int insert(User user);

    /**
     * 根据ID查询用户
     * 
     * @param id 用户ID
     * @return 用户信息
     */
    User selectById(@Param("id") Long id);

    /**
     * 更新用户信息
     * 
     * @param user 用户信息
     * @return 影响行数
     */
    int update(User user);

    /**
     * 逻辑删除用户
     * 
     * @param id 用户ID
     * @return 影响行数
     */
    int deleteById(@Param("id") Long id);
}
