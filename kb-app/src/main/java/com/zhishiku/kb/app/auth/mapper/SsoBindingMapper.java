package com.zhishiku.kb.app.auth.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 复用 V8 唯一约束，冲突不把 PostgreSQL 当前事务置为 aborted。
 */
public interface SsoBindingMapper {
    @Insert("""
            INSERT INTO sys_user_identity(user_id, provider, subject, enabled)
            VALUES (#{user}, #{provider}, #{subject}, true)
            ON CONFLICT (provider, subject) DO NOTHING
            """)
    int insertIfAbsent(@Param("user") long user, @Param("provider") String provider, @Param("subject") String subject);
}
