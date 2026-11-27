package com.zhishiku.kb.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgePublicationSchemaCheckTest {
    @Test
    void missingOrIncompatibleProtocolRejectsWorkerBeforeAcceptingJobs() {
        var jdbc = mock(JdbcTemplate.class);
        var check = new KnowledgePublicationSchemaCheck(jdbc);
        assertThatThrownBy(check::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);
        assertThatThrownBy(check::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        check.afterPropertiesSet();
    }
}
