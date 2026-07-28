package com.finance.platform.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finance.platform.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户数据访问层
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 查询已逻辑删除的用户（绕过 MyBatis-Plus 逻辑删除过滤）。
     * <p>
     * 用于"已删除用户列表"页面，提供恢复入口。
     *
     * @param keyword 关键词（同时匹配用户名和真实姓名，可为空）
     * @return 已删除用户列表（deleted=1）
     */
    @Select("""
            <script>
            SELECT * FROM sys_user
            WHERE deleted = 1
            <if test="keyword != null and keyword != ''">
              AND (username LIKE CONCAT('%', #{keyword}, '%')
                   OR real_name LIKE CONCAT('%', #{keyword}, '%')
                   OR employee_no LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY id DESC
            </script>
            """)
    List<SysUser> selectDeleted(@Param("keyword") String keyword);

    /**
     * 根据 ID 查询已逻辑删除的用户（绕过逻辑删除过滤）。
     */
    @Select("SELECT * FROM sys_user WHERE id = #{id} AND deleted = 1")
    SysUser selectDeletedById(@Param("id") Long id);
}
