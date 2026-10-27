package com.zhishiku.kb.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.fail;

/**
 * AI 服务必须运行在响应式栈上。
 *
 * <p>这是一个回归测试，来自一个真实踩过的坑：
 * {@code kb-common} 当初声明了 {@code spring-boot-starter-web}，
 * Tomcat 便随依赖传递进 kb-ai 的类路径。Spring Boot 发现 servlet 栈存在，
 * 就把应用判定为 SERVLET 类型，启动日志里是
 * {@code Starting Servlet engine: Apache Tomcat}。
 *
 * <p>症状的隐蔽之处在于：<b>接口照样能用</b>。
 * Spring MVC 也支持返回 {@code Flux}，SSE 表面上一切正常，
 * 但"长连接不占容器线程"这个选 WebFlux 的唯一理由已经没了。
 * 问题要到并发对话数上来、容器线程池被占满时才会暴露，
 * 那时候表现是"系统卡住"，根本不会有人想到是一行依赖声明的问题。
 *
 * <p>所以把它钉死成测试。Tomcat 一旦再次出现在类路径上，这里立刻失败。
 */
class ReactiveStackTest {

    @Test
    @DisplayName("类路径上不得有 Tomcat——否则 kb-ai 会退化成 servlet 应用")
    void servletContainerMustNotBeOnClasspath() {
        assertAbsent("org.apache.catalina.startup.Tomcat", "Tomcat");
        assertAbsent("org.eclipse.jetty.server.Server", "Jetty");
        assertAbsent("org.springframework.web.servlet.DispatcherServlet", "Spring MVC");
    }

    @Test
    @DisplayName("响应式栈必须在位")
    void reactiveStackMustBePresent() {
        assertPresent("reactor.netty.http.server.HttpServer", "Reactor Netty");
        assertPresent("org.springframework.web.reactive.DispatcherHandler", "Spring WebFlux");
    }

    private static void assertAbsent(String className, String what) {
        try {
            Class.forName(className);
            fail("""
                    检测到 %s（%s）出现在 kb-ai 的类路径上。
                    
                    kb-ai 必须跑在响应式栈（Reactor Netty）上：它的工作形态是
                    等上游模型慢慢吐字，全程 IO 等待。若退化成 servlet 应用，
                    每个进行中的对话会占住一个容器线程，并发上来就会耗尽线程池。
                    
                    常见原因是某个依赖传递引入了 spring-boot-starter-web。
                    用 mvn -pl kb-ai dependency:tree 找出是谁带进来的，
                    在那个依赖上加 exclusion，而不是在这里放宽断言。
                    """.formatted(what, className));
        } catch (ClassNotFoundException expected) {
            // 正是期望的结果
        }
    }

    private static void assertPresent(String className, String what) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException e) {
            fail("%s（%s）不在类路径上，kb-ai 无法以响应式栈运行".formatted(what, className));
        }
    }
}
