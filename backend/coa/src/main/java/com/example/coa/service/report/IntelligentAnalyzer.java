package com.example.coa.service.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.coa.service.ReportDataAssembler.ComponentStat;
import com.example.coa.service.ReportDataAssembler.GradReqAchievement;
import com.example.coa.service.ReportDataAssembler.ObjectiveAchievement;
import com.example.coa.service.ReportDataAssembler.ReportContext;

@Component
public class IntelligentAnalyzer {

    public String analyzeObjective(
        ObjectiveAchievement oa,
        List<ComponentStat> compStats,
        String objectiveDesc
    ) {
        ComponentStat weakest = weakestComponent(compStats);
        String verb = extractObjectiveVerbs(objectiveDesc);
        String weakestText = weakest == null
            ? "各考核环节表现较为均衡"
            : weakest.typeName + "环节得分表现相对薄弱";
        String suggestion = oa.achievement >= 0.7D
            ? "后续可继续保持现有教学组织方式，并适当增加综合性训练以巩固学习成效。"
            : "后续应围绕该目标对应的知识与能力要求加强过程性训练、反馈与针对性辅导。";

        return oa.objectiveCode + "整体达成度为" + format(oa.achievement)
            + "，达成情况" + levelDesc(oa.achievement) + "。结果表明学生在“"
            + trimObjective(objectiveDesc) + "”方面的学习基础"
            + (oa.achievement >= 0.7D ? "较为稳定" : "仍需进一步夯实")
            + "，其中" + weakestText + "。结合目标描述中的“" + verb
            + "”等行为要求，教学中应持续关注知识理解、实践应用与结果表达之间的衔接。"
            + suggestion;
    }

    public String generateConclusion(ReportContext ctx) {
        long passCount = ctx.objectiveAchievements.stream().filter(item -> item.achievement >= ctx.thresholdValue).count();
        ComponentStat weakest = weakestComponent(ctx.componentStats);
        double passRate = bucketPct(ctx.gradeDistribution, "优")
            + bucketPct(ctx.gradeDistribution, "良")
            + bucketPct(ctx.gradeDistribution, "中")
            + bucketPct(ctx.gradeDistribution, "及格");
        double excellentRate = bucketPct(ctx.gradeDistribution, "优") + bucketPct(ctx.gradeDistribution, "良");
        String gradReqText = ctx.gradReqAchievements.isEmpty()
            ? "毕业要求支撑关系尚需进一步补充完善"
            : "已形成对" + ctx.gradReqAchievements.size() + "项毕业要求指标点的支撑";
        String weakestText = weakest == null ? "未发现明显薄弱环节" : weakest.typeName + "环节相对薄弱";

        return "本次课程目标达成度评价显示，" + passCount + "个课程目标达到设定阈值，课程整体达成度为"
            + format(ctx.overallAchievement) + "。从成绩结构看，课程及格率为" + percent(passRate)
            + "，优良率为" + percent(excellentRate) + "，整体成绩分布能够反映学生学习差异。"
            + "从毕业要求支撑看，" + gradReqText + "，支撑链条总体清晰。"
            + "综合各考核方式统计，" + weakestText + "，后续教学应进一步强化该环节的评价反馈和学习支持。"
            + "总体来看，本课程目标评价数据来源较完整，能够为课程持续改进提供依据。";
    }

    public List<String> generateImprovements(ReportContext ctx, List<String> existingSuggestions) {
        List<String> result = new ArrayList<>();
        if (existingSuggestions != null) {
            existingSuggestions.stream()
                .filter(StringUtils::hasText)
                .limit(6)
                .forEach(result::add);
        }

        ObjectiveAchievement weakestObjective = ctx.objectiveAchievements.stream()
            .min(Comparator.comparingDouble(item -> item.achievement))
            .orElse(null);
        ComponentStat weakestComponent = weakestComponent(ctx.componentStats);

        if (result.size() < 4 && weakestObjective != null) {
            result.add("围绕" + weakestObjective.objectiveCode + "达成度相对偏低的问题，优化课堂讲解、案例训练和课后反馈安排，提升学生对关键目标的稳定达成水平。");
        }
        if (result.size() < 4 && weakestComponent != null) {
            result.add("针对" + weakestComponent.typeName + "考核环节得分率偏低的情况，复核考核任务难度、评分标准和训练覆盖度，增强评价与课程目标之间的一致性。");
        }
        if (result.size() < 4 && ctx.studentCount > 0 && ctx.weakStudents.size() > ctx.studentCount * 0.1D) {
            result.add("建立低达成度学生跟踪清单，结合课堂表现与作业反馈开展分层辅导，帮助学生补齐薄弱知识点和能力短板。");
        }
        if (result.size() < 4) {
            result.add("完善过程性考核记录与阶段反馈机制，及时识别学习困难并调整教学节奏，提升课程目标达成的可控性。");
        }
        if (result.size() < 5) {
            result.add("在后续课程建设中持续积累达成度评价数据，形成跨学期对比依据，为课程内容、考核方式和教学资源优化提供支撑。");
        }
        return result.stream().limit(6).toList();
    }

    public String generateExamAnalysis(ReportContext ctx) {
        String composition = ctx.assessItems.stream()
            .map(item -> item.name + "（" + String.format("%.0f%%", item.weight) + "）")
            .reduce((left, right) -> left + " + " + right)
            .orElse("平时作业、实验项目和期末考核");
        String objectiveText = ctx.objectives.stream()
            .map(item -> item.code)
            .reduce((left, right) -> left + "、" + right)
            .orElse("课程目标");
        return "【考核方式】本课程严格按照课程大纲要求授课和考核，课程最终成绩由" + composition + "组成。"
            + "【考核内容分析】考核内容围绕" + objectiveText + "展开，覆盖前端必要技术、后端程序开发、用户界面设计、数据模型、系统开发工具使用和综合案例实现等大纲重点内容。"
            + "【考核目标】平时作业侧重基础知识理解与资料查阅，实验项目侧重系统开发过程、代码实现和演示结果，期末考核侧重综合分析、方案设计与技术表达。"
            + "【难度及题量分析】考核范围与课程大纲保持一致，过程性任务与终结性考核共同覆盖课程目标，难度结构能够区分不同学习水平学生的掌握程度。";
    }

    public String generateScoreAnalysis(ReportContext ctx) {
        double excellentRate = bucketPct(ctx.gradeDistribution, "优");
        double goodRate = bucketPct(ctx.gradeDistribution, "良");
        double failRate = bucketPct(ctx.gradeDistribution, "不及格");
        ComponentStat weakest = weakestComponent(ctx.componentStats);
        String weakestText = weakest == null
            ? "各考核环节表现较为均衡"
            : weakest.typeName + "环节得分率相对较低";
        return "本次优秀率为" + percent(excellentRate) + "，良好率为" + percent(goodRate)
            + "，不及格率为" + percent(failRate) + "，总成绩分布能够反映学生学习差异。"
            + weakestText + "，后续应结合课程目标和考核项进一步分析学生在知识理解、实践开发和综合表达方面的薄弱点。";
    }

    public String generateThresholdSummary(ReportContext ctx) {
        String details = ctx.objectiveAchievements.stream()
            .map(item -> item.objectiveCode + "达成情况：" + percent(item.achievement)
                + (item.achievement >= ctx.thresholdValue ? "，达到阈值" : "，低于阈值"))
            .reduce((left, right) -> left + "；" + right)
            .orElse("暂无课程目标达成数据");
        return "本课程以" + format(ctx.thresholdValue) + "作为课程目标达成情况评价阈值。" + details
            + "。总体来看，课程目标达成情况能够为下一轮教学内容、考核方式和评价标准改进提供依据。";
    }

    public String generateAssessmentRationalityIntro(ReportContext ctx) {
        long achievedCount = ctx.objectiveAchievements.stream()
            .filter(item -> item.achievement >= ctx.thresholdValue)
            .count();
        return "根据最终学生达成情况分析，" + achievedCount + "个课程目标达到设定阈值。综合成绩分布、课程目标达成情况以及课程大纲中的考核要求，本课程考核方式总体合理。";
    }

    public String generateAchievementSummary(ReportContext ctx) {
        ObjectiveAchievement weakest = weakestObjective(ctx);
        String weakestText = weakest == null
            ? "各课程目标暂无可比较的薄弱项。"
            : weakest.objectiveCode + "达成情况相对最低，为" + percent(weakest.achievement) + "，应作为下一轮持续改进重点。";
        return "课程成绩客观反映了学生学习本课程的情况；各项课程目标达成情况已完成量化评价；课程考核方式与教学大纲、课程目标和成绩数据之间具有对应关系；" + weakestText;
    }

    private String levelDesc(double achievement) {
        if (achievement >= 0.9D) {
            return "较好";
        }
        if (achievement >= 0.7D) {
            return "良好";
        }
        if (achievement >= 0.6D) {
            return "基本达成";
        }
        return "未达成";
    }

    private ComponentStat weakestComponent(List<ComponentStat> stats) {
        if (stats == null || stats.isEmpty()) {
            return null;
        }
        return stats.stream()
            .min(Comparator.comparingDouble(item -> item.weight <= 0D ? item.avgScore : item.avgScore / item.weight))
            .orElse(null);
    }

    private ObjectiveAchievement weakestObjective(ReportContext ctx) {
        return ctx.objectiveAchievements.stream()
            .min(Comparator.comparingDouble(item -> item.achievement))
            .orElse(null);
    }

    private String extractObjectiveVerbs(String desc) {
        if (!StringUtils.hasText(desc)) {
            return "掌握、理解、应用";
        }
        List<String> verbs = List.of("掌握", "理解", "能够", "应用", "分析", "设计", "实现", "解决", "培养", "形成");
        List<String> matched = verbs.stream().filter(desc::contains).limit(3).toList();
        return matched.isEmpty() ? "掌握、理解、应用" : String.join("、", matched);
    }

    private String trimObjective(String text) {
        if (!StringUtils.hasText(text)) {
            return "相关课程目标";
        }
        String normalized = text.replaceAll("\\s+", "");
        return normalized.length() <= 45 ? normalized : normalized.substring(0, 45) + "…";
    }

    private double bucketPct(com.example.coa.service.ReportDataAssembler.GradeDistribution distribution, String key) {
        if (distribution == null || distribution.buckets.get(key) == null) {
            return 0D;
        }
        return distribution.buckets.get(key).pct;
    }

    private String percent(double value) {
        return String.format("%.1f%%", value * 100D);
    }

    private String format(double value) {
        return String.format("%.3f", value);
    }
}
