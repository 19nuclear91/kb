package com.zhishiku.kb.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 首个管理员引导配置（设计 D10/D12，任务 5.1）。
 *
 * <p>开关是凭据：用户名与密码都非空白才启用（{@link #enabled()}），默认两者为空
 * ——开发与未配置引导的生产环境零动作。显示名、密级与部门带有设计默认值
 * （系统管理员 / 0 / HQ / 总部），它们不属于开关：运维只需提供两个环境变量就能
 * 完成引导，其余字段不设置也得到合法值。
 *
 * <p>规范化放在构造器里而不是调用方：Binder 在属性缺失时给包装类型传 null，
 * 散落在 runner 里的空值判断迟早会漏掉一处，而漏掉的那一处会变成一个空显示名
 * 或空 code 的管理员——那正是引导最不该产出的东西。
 *
 * @param admin      管理员账号配置
 * @param department 管理员归属部门配置
 */
@ConfigurationProperties(prefix = "kb.bootstrap")
public record BootstrapProperties(Admin admin, Department department) {

    public BootstrapProperties {
        if (admin == null) {
            admin = new Admin(null, null, null, null);
        }
        if (department == null) {
            department = new Department(null, null);
        }
    }

    /**
     * 引导是否启用：用户名与密码都非空白（设计 D10 步骤 1）。
     */
    public boolean enabled() {
        return !admin.username().isBlank() && !admin.password().isBlank();
    }

    /**
     * @param username       管理员用户名；默认空，为空则引导不启用
     * @param password       管理员初始密码；默认空，为空则引导不启用；不合强度时启动失败
     * @param displayName    显示名；默认"系统管理员"
     * @param clearanceLevel 初始密级；默认 0，必须存在于服务端密级字典
     */
    public record Admin(String username, String password, String displayName,
                        Short clearanceLevel) {
        public Admin {
            if (username == null) {
                username = "";
            }
            if (password == null) {
                password = "";
            }
            if (displayName == null || displayName.isBlank()) {
                displayName = "系统管理员";
            }
            if (clearanceLevel == null) {
                clearanceLevel = 0;
            }
        }
    }

    /**
     * @param code 部门编码；默认"HQ"
     * @param name 部门名称；默认"总部"
     */
    public record Department(String code, String name) {
        public Department {
            if (code == null || code.isBlank()) {
                code = "HQ";
            }
            if (name == null || name.isBlank()) {
                name = "总部";
            }
        }
    }
}
