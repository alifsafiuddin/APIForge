package com.apiforge.project.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.apiforge.testcase.domain.TestCase.RiskLevel;

class ProjectServiceTest {

    @Test
    void searchPatternEscapesLikeWildcards() {
        assertThat(ProjectService.toLikePattern("100%_done\\")).isEqualTo("%100\\%\\_done\\\\%");
        assertThat(ProjectService.toLikePattern("  Pets ")).isEqualTo("%pets%");
        assertThat(ProjectService.toLikePattern("   ")).isNull();
        assertThat(ProjectService.toLikePattern(null)).isNull();
    }

    @Test
    void riskLevelFollowsHttpMethod() {
        assertThat(RiskLevel.forMethod("get")).isEqualTo(RiskLevel.READ_ONLY);
        assertThat(RiskLevel.forMethod("OPTIONS")).isEqualTo(RiskLevel.READ_ONLY);
        assertThat(RiskLevel.forMethod("POST")).isEqualTo(RiskLevel.MUTATING);
        assertThat(RiskLevel.forMethod("PATCH")).isEqualTo(RiskLevel.MUTATING);
        assertThat(RiskLevel.forMethod("DELETE")).isEqualTo(RiskLevel.DESTRUCTIVE);
    }
}
