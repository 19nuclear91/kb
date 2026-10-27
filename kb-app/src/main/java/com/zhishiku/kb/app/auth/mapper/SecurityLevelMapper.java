package com.zhishiku.kb.app.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 密级字典的读取边界。
 *
 * <h2>为什么单独成一个 mapper</h2>
 *
 * <p>{@code sys_security_level} 不属于身份生命周期，也不属于权限解析：它是用户密级
 * （{@code sys_user.clearance_level}）与文档密级（{@code kb_document.security_level}）
 * <b>共同</b>的取值来源，两侧都有外键指向它。放进 {@code IdentityDepartmentMapper}
 * 会让文档上传路径为了一次取值校验而依赖身份生命周期的数据访问层。
 *
 * <h2>为什么只读</h2>
 *
 * <p>字典 CRUD 不在本能力范围内。增删级别目前是数据操作（直接改表），这正是
 * 字典驱动校验的用意：级数变了不必改代码。而删除仍被引用的级别会被外键
 * （默认 RESTRICT）直接拒绝，不会产生悬空取值。
 */
@Mapper
public interface SecurityLevelMapper {

    /**
     * 返回全部可用密级，升序。
     *
     * <p>返回<b>列表</b>而不是 {@code EXISTS} 判定，是为了让越界错误能直接告诉调用方
     * 哪些级别可用。否则校验失败只能给出"密级不可用"，而可用集合恰好是服务端
     * 独有的知识——客户端无从得知，只能靠猜或读文档，而文档正是会过期的那一份。
     */
    @Select("SELECT level FROM sys_security_level ORDER BY level")
    List<Short> listLevels();
}
