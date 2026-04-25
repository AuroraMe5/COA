package com.example.coa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.example.coa.service.ReportDataAssembler;
import com.example.coa.service.ReportDataAssembler.ReportContext;
import com.example.coa.service.report.ReportWordBuilder;

@SpringBootTest
@EnabledIfSystemProperty(named = "coa.report.it", matches = "true")
class ReportIntegrationTest {

    @Autowired
    ReportDataAssembler assembler;

    @Autowired
    ReportWordBuilder builder;

    @Autowired
    NamedParameterJdbcTemplate jdbcTemplate;

    @Test
    void testGenerateReport() throws Exception {
        Long outlineId = jdbcTemplate.query("""
            SELECT o.id
            FROM outline_main o
            WHERE EXISTS (
                SELECT 1 FROM teach_objective t WHERE t.outline_id = o.id
            )
            ORDER BY o.id ASC
            LIMIT 1
            """, new MapSqlParameterSource(), rs -> rs.next() ? rs.getLong("id") : null);
        Assumptions.assumeTrue(outlineId != null, "需要至少一条含教学目标的大纲数据");

        Long calcRuleId = jdbcTemplate.query("""
            SELECT id
            FROM calc_rule
            WHERE status = 1
            ORDER BY is_default DESC, id DESC
            LIMIT 1
            """, new MapSqlParameterSource(), rs -> rs.next() ? rs.getLong("id") : null);

        ReportContext ctx = assembler.assemble(outlineId, calcRuleId);
        assertNotNull(ctx.courseInfo);
        assertNotNull(ctx.objectives);
        assertFalse(ctx.objectives.isEmpty());
        assertNotNull(ctx.gradeDistribution);

        byte[] docBytes = builder.build(ctx);
        assertTrue(docBytes.length > 1000);
        Files.createDirectories(Paths.get("target"));
        Files.write(Paths.get("target/test-report.docx"), docBytes);
        System.out.println("报告已生成：target/test-report.docx，请手动检查内容。");
    }
}
