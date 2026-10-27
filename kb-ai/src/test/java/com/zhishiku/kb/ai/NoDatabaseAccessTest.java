package com.zhishiku.kb.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.fail;

/**
 * AI 服务不得具备直连数据库的能力。
 *
 * <p>这条约束写在 {@code kb-ai/pom.xml} 的注释里、写在 {@code KbAiApplication} 的
 * 类注释里、也写在 README 里——但注释拦不住任何人。
 * 半年后有人为了"顺手查一下文档标题"加一个 JDBC 依赖，
 * 所有文字说明都会安静地失效。
 *
 * <p>所以把它变成一个会失败的测试。
 *
 * <p>为什么这条边界值得如此对待：AI 服务若能直连数据库，
 * 它就能不带权限条件地读 {@code kb_doc_chunk}。
 * 那么 kb-app 里那条精心设计的权限下推 SQL 就被绕过了，
 * 每个用户都能通过 AI 问答读到全量语料。
 * 这是 RAG 系统最典型的越权路径，而且极难从外部发现——
 * 回答看起来是正常的，只是内容不该给这个人看。
 */
class NoDatabaseAccessTest {

    /**
     * 类路径上不得出现数据库访问能力。
     *
     * <p>检查的是"能力"而非"用法"：只要驱动和 JdbcTemplate 不在类路径上，
     * 任何直连数据库的代码就根本编译不过，无需依赖人的自觉。
     *
     * <p>注意不检查 {@code java.sql.*}——那是 JDK 自带的，永远存在，
     * 检查它只会得到一个永远失败的测试。真正的门槛是驱动实现。
     */
    @Test
    @DisplayName("类路径上不得有 JDBC 驱动或 Spring JDBC——AI 服务只能经 kb-app 取语料")
    void databaseAccessMustNotBeOnClasspath() {
        assertAbsent("org.postgresql.Driver",
                "PostgreSQL JDBC 驱动");
        assertAbsent("org.springframework.jdbc.core.JdbcTemplate",
                "Spring JDBC");
        assertAbsent("com.baomidou.mybatisplus.core.mapper.BaseMapper",
                "MyBatis-Plus");
        assertAbsent("com.zaxxer.hikari.HikariDataSource",
                "Hikari 连接池");
    }

    private static void assertAbsent(String className, String what) {
        try {
            Class.forName(className);
            fail("""
                    检测到 %s（%s）出现在 kb-ai 的类路径上。
                    
                    AI 服务不得具备直连数据库的能力。语料的唯一来源是
                    RetrievalClient 调用 kb-app 的授权检索接口，并透传调用者身份。
                    
                    若确实需要新增某个依赖，请先确认它没有间接引入数据库访问能力；
                    若是有意要给 AI 服务加数据库连接，那是在拆掉一道安全边界，
                    请先在《架构决策记录.md》里记录这个决定及其替代的越权防护措施，
                    再来改这个测试。
                    """.formatted(what, className));
        } catch (ClassNotFoundException expected) {
            // 正是期望的结果
        }
    }
}
