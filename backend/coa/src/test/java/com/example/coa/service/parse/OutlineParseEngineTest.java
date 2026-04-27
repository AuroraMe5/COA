package com.example.coa.service.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import com.example.coa.service.parse.OutlineParseEngine.AssessItemDraftSuggestion;
import com.example.coa.service.parse.OutlineParseEngine.ObjectiveDraftSuggestion;
import com.example.coa.service.parse.OutlineParseEngine.ParsedOutlineDraft;

class OutlineParseEngineTest {

    private final OutlineParseEngine engine = new OutlineParseEngine();

    @Test
    void parsesSyllabusElementsNeededByAchievementReport() throws Exception {
        ParsedOutlineDraft parsed = engine.parse("信息系统开发技术-教学大纲.docx", buildRepresentativeSyllabus());

        assertEquals("1032006", parsed.courseInfo().courseCode());
        assertEquals("信息系统开发技术", parsed.courseInfo().courseNameZh());
        assertEquals("Development Technology of Information System", parsed.courseInfo().courseNameEn());
        assertEquals("专业核心课", parsed.courseInfo().courseType());
        assertEquals("信息管理与信息系统", parsed.courseInfo().targetStudents());
        assertEquals("计算机科学与工程学院", parsed.courseInfo().collegeName());
        assertEquals(64, parsed.courseInfo().hours());
        assertEquals(4.0D, parsed.courseInfo().credits());
        assertEquals("黄丽丰", parsed.courseInfo().courseOwner());

        List<ObjectiveDraftSuggestion> objectives = parsed.objectives();
        assertEquals(3, objectives.size());
        assertEquals(40.0D, objectives.get(0).weight());
        assertEquals("3.1", objectives.get(0).gradReqId());
        assertEquals("H", objectives.get(0).relationLevel());
        assertTrue(objectives.get(0).gradReqDesc().contains("程序设计相关方法与技术"));
        assertEquals(50.0D, objectives.get(1).weight());
        assertEquals("4.1", objectives.get(1).gradReqId());
        assertEquals(10.0D, objectives.get(2).weight());
        assertEquals("5.2", objectives.get(2).gradReqId());
        assertEquals("M", objectives.get(2).relationLevel());

        List<AssessItemDraftSuggestion> assessItems = parsed.assessItems();
        assertEquals(List.of("平时作业", "实验项目", "期末测试或综合报告"),
            assessItems.stream().map(AssessItemDraftSuggestion::itemName).toList());
        assertEquals(List.of(20.0D, 40.0D, 40.0D),
            assessItems.stream().map(AssessItemDraftSuggestion::weight).toList());

        assertNotNull(parsed.objAssessMatrix());
        assertEquals(List.of("平时作业", "实验项目", "期末测试或综合报告"), parsed.objAssessMatrix().methodNames());
        assertEquals(List.of(10.0D, 20.0D, 10.0D), parsed.objAssessMatrix().rows().get(0).proportions());
        assertEquals(40.0D, parsed.objAssessMatrix().rows().get(0).totalWeight());

        assertEquals(3, parsed.courseInfo().assessmentDetails().size());
        assertTrue(parsed.courseInfo().assessmentDetails().get(0).content().contains("课堂表现"));
        assertEquals("五级计分制", parsed.courseInfo().assessmentPolicy().scoreRecordMode());
        assertTrue(parsed.courseInfo().assessmentPolicy().finalGradeComposition().contains("平时作业(20%)"));
        assertEquals("考试 / 非标", parsed.courseInfo().assessmentPolicy().assessmentMode());
        assertEquals("否", parsed.courseInfo().assessmentPolicy().makeupExam());
        assertTrue(parsed.courseInfo().assessmentStandards().stream()
            .anyMatch(standard -> "平时作业".equals(standard.assessmentMethod())
                && "课程目标1".equals(standard.objective())
                && standard.excellent().contains("基本概念正确")
                && "50".equals(standard.scorePercent())));
        assertTrue(parsed.courseInfo().teachingContents().stream()
            .anyMatch(item -> item.title().startsWith("实验一")
                && item.requirements().contains("熟悉Web应用程序开发环境")
                && Integer.valueOf(2).equals(item.practiceHours())),
            () -> parsed.courseInfo().teachingContents().toString());
    }

    private byte[] buildRepresentativeSyllabus() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            paragraph(doc, "重庆理工大学本科理论课程教学大纲");
            paragraph(doc, "一、课程基本信息");
            table(doc, new String[][] {
                {"课程编号", "1032006", "开课单位", "计算机科学与工程学院", "学分", "4"},
                {"课程总学时", "64", "其中：讲授  40 学时；实验（上机） 24 学时。", "", "", ""},
                {"课程名称（中/英）", "信息系统开发技术/Development Technology of Information System", "", "", "", ""},
                {"课程类别", "通识教育课程□  学科基础课程□  专业核心课☑", "", "", "", ""},
                {"课程性质", "必修", "授课语言", "中文", "", ""},
                {"适用专业", "信息管理与信息系统", "开课学期", "第3学期", "", ""},
                {"先修课程", "无", "后续课程", "信息系统分析与设计", "", ""},
                {"制定人", "黄丽丰", "审核人", "李唯唯", "", ""}
            });

            paragraph(doc, "三、课程目标及其与毕业要求的关系");
            table(doc, new String[][] {
                {"课程目标", "描述", "关联程度", "毕业要求", "描述"},
                {"课程目标1", "理解和掌握前端必要技术以及后端的程序开发相关技术；掌握简单的系统代码架构。", "H", "3.1", "掌握程序设计相关方法与技术，能够用于信息系统的设计及实现。"},
                {"课程目标2", "能够针对特定的问题，分析、提出解决方案，并开发出具有一定复杂度的软件程序。", "H", "4.1", "能够运用科学方法，对复杂管理工程问题进行需求、功能分析和解决方案分析。"},
                {"课程目标3", "能够选择与使用恰当的集成开发工具，解决具有一定复杂度的工程问题。", "M", "5.2", "能够根据复杂管理工程问题的设计需要，开发、选择与使用恰当的平台、技术、资源和现代工程工具。"}
            });

            paragraph(doc, "五、课程教学内容及基本要求");
            paragraph(doc, "（一）信息系统技术基本概要（2学时）");
            paragraph(doc, "（重点覆盖课程目标1）");
            paragraph(doc, "1. 基本要求");
            paragraph(doc, "（1）理解信息系统相关技术概要。");
            paragraph(doc, "2. 重点、难点");
            paragraph(doc, "信息系统基本概念。");
            paragraph(doc, "（二）课程实验");
            paragraph(doc, "实验一：Web应用程序开发环境熟悉（2学时）");
            paragraph(doc, "通过上机实验，让学生熟悉Web应用程序开发环境，掌握项目创建、运行和调试的基本要求。");
            paragraph(doc, "六、教学安排及教学方式");
            table(doc, new String[][] {
                {"序号", "课程内容", "讲授学时", "上机学时", "教学方式"},
                {"1", "（一）信息系统技术基本概要", "2", "", "讲授"},
                {"2", "实验一：Web应用程序开发环境熟悉", "", "2", "上机"},
                {"合计", "", "40", "24", ""}
            });

            paragraph(doc, "七、考核要求与成绩评定");
            paragraph(doc, "（一）课程目标考核及课程成绩评定方式");
            table(doc, new String[][] {
                {"课程目标", "支撑毕业要求", "考核方式及成绩比例（%）", "", "", "成绩比例（%）"},
                {"", "", "平时作业", "实验项目", "期末测试或综合报告", ""},
                {"课程目标1", "支撑毕业要求3.1", "10", "20", "10", "40"},
                {"课程目标2", "支撑毕业要求4.1", "10", "20", "20", "50"},
                {"课程目标3", "支撑毕业要求5.2", "", "", "10", "10"},
                {"合计", "", "20", "40", "40", "100"}
            });

            paragraph(doc, "（二）课程目标考核及课程成绩评定方式描述");
            table(doc, new String[][] {
                {"课程最终成绩记载方式", "五级计分制", "", ""},
                {"课程最终成绩组成", "课程最终成绩 = 平时作业(20%)+ 实验项目(40%)+ 综合实验报告(期末考试)成绩(40%)", "", ""},
                {"平时成绩评定", "权重", "平时成绩占课程最终成绩的20%。", ""},
                {"考核内容及方式", "平时成绩根据课堂表现、课外作业、课堂作业完成情况进行评定。", "", ""},
                {"评价办法", "课外作业采用全部检查评分。", "", ""},
                {"支撑", "支撑毕业要求3.1、支撑毕业要求4.1", "", ""},
                {"实验成绩评定", "权重", "实验成绩占课程最终成绩的40%", ""},
                {"考核内容及方式", "学生完成项目信息开发技术的实验内容，进行实验代码及演示评价。", "", ""},
                {"评价办法", "检查学生系统代码完成情况，给出5次成绩", "", ""},
                {"支撑", "支撑毕业要求3.1、支撑毕业要求4.1", "", ""},
                {"课程结业考核", "权重", "课程结业成绩占课程最终成绩的40%", ""},
                {"考核内容", "对贯穿课程的案例进行综合实验文档，考试形式以综合题为主要题型。", "", ""},
                {"评价办法", "学生提交纸质文档，进行检查评分", "", ""},
                {"支撑", "支撑毕业要求3.1、支撑毕业要求4.1、支撑毕业要求5.2", "", ""},
                {"考核方式", "考试 / 非标", "是否设置补考", "否"}
            });

            paragraph(doc, "（三）考核与评价标准");
            paragraph(doc, "表6 平时作业评价标准表");
            table(doc, new String[][] {
                {"考核方式", "课程目标", "优秀（0.9~1）", "良好（0.8~0.89）", "中等（0.7~0.79）", "合格（0.6~0.69）", "不合格（0~0.59）", "成绩比例（%）"},
                {"平时作业", "课程目标1", "按时交作业；基本概念正确。", "按时交作业；基本概念较正确。", "按时交作业；基本概念有错误。", "按时交作业；基本概念不清楚。", "不按时交作业或基本概念不正确。", "50"}
            });

            doc.write(out);
            return out.toByteArray();
        }
    }

    private void paragraph(XWPFDocument doc, String text) {
        doc.createParagraph().createRun().setText(text);
    }

    private void table(XWPFDocument doc, String[][] rows) {
        XWPFTable table = doc.createTable(rows.length, rows[0].length);
        for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
            for (int colIndex = 0; colIndex < rows[rowIndex].length; colIndex++) {
                table.getRow(rowIndex).getCell(colIndex).setText(rows[rowIndex][colIndex]);
            }
        }
    }
}
