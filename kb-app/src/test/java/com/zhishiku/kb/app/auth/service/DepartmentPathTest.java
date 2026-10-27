package com.zhishiku.kb.app.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DepartmentPathTest {

    @Test
    void createsRootAndChildPaths() {
        assertThat(DepartmentPath.forRoot(1)).isEqualTo("/1/");
        assertThat(DepartmentPath.forChild("/1/2/", 9)).isEqualTo("/1/2/9/");
    }

    @Test
    void detectsSelfAndDescendantPaths() {
        assertThat(DepartmentPath.isSameOrDescendant("/1/2/", "/1/2/")).isTrue();
        assertThat(DepartmentPath.isSameOrDescendant("/1/2/9/", "/1/2/")).isTrue();
        assertThat(DepartmentPath.isSameOrDescendant("/1/3/", "/1/2/")).isFalse();
    }
}
