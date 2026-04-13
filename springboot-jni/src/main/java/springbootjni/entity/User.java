package springbootjni.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

/**
 * 用户实体类
 */
@Data
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long id;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码(BCrypt加密存储)
     */
    private String password;

    /**
     * 创建时间
     */
    private Date createdAt;

    /**
     * 更新时间
     */
    private Date updatedAt;

    /**
     * 逻辑删除标记(false:未删除, true:已删除)
     */
    private Boolean deleted;
}
