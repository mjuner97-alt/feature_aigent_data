package com.agentscopea2a.v2.skillManager.mapper;

import com.agentscopea2a.v2.config.SkillStorageProperties;
import com.agentscopea2a.v2.skillManager.service.FlowQueryService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillFlowExecutionQueryBindingTest {
    @Test
    void executionQueriesBindKeywordsWithAndWithoutSearch() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mybatis/mapper/gauss/SkillFlowMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        for (String keyword : Arrays.asList(null, "", "图表")) {
            for (String name : List.of("selectExecutions", "countExecutions")) {
                var method = Arrays.stream(SkillFlowMapper.class.getMethods())
                        .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
                Object[] args = name.equals("selectExecutions")
                        ? new Object[]{null, null, keyword, "skill_demo_u001", 0, 20}
                        : new Object[]{null, null, keyword, "skill_demo_u001"};
                Object parameters = new ParamNameResolver(configuration, method).getNamedParams(args);
                BoundSql sql = configuration.getMappedStatement(SkillFlowMapper.class.getName() + "." + name)
                        .getBoundSql(parameters);
                assertEquals(keyword != null && !keyword.isEmpty(),
                        sql.getSql().contains("LOWER("));
                for (var mapping : sql.getParameterMappings()) {
                    assertDoesNotThrow(() -> configuration.newMetaObject(parameters).getValue(mapping.getProperty()));
                }
            }
        }
    }

    @Test
    void unscopedExecutionListPassesKeywordToBothPageAndCount() {
        SkillFlowMapper mapper = mock(SkillFlowMapper.class);
        SkillStorageProperties storage = mock(SkillStorageProperties.class);
        when(storage.getJobReportDir()).thenReturn(System.getProperty("java.io.tmpdir"));
        when(mapper.selectExecutions(null, null, "图表", null, 0, 20)).thenReturn(List.of());
        when(mapper.countExecutions(null, null, "图表", null)).thenReturn(1L);
        FlowQueryService service = new FlowQueryService(mapper, null, null, storage, null, null, null);

        service.list(null, null, "图表", "skill_demo_u001", true, 1, 20, null);

        verify(mapper).selectExecutions(null, null, "图表", null, 0, 20);
        verify(mapper).countExecutions(null, null, "图表", null);
    }
}
