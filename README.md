# COA 教学目标达成系统说明文档

本项目是一个面向课程教学目标达成度管理的教师端系统，覆盖“课程大纲与教学目标维护、智能解析导入、学生信息与成绩数据采集、达成度核算、达成度报告导出”等核心流程。

中期检查时可以用一句话概括本系统：

> 系统以课程大纲为入口，自动或手动维护课程目标、考核项和目标-考核映射；再导入学生信息和成绩数据；最后按配置规则核算课程目标达成度，并生成《课程目标达成情况评价及总结报告》。

## 目录结构

```text
COA/
├── backend/
│   └── coa/
│       ├── pom.xml
│       └── src/main/
│           ├── java/com/example/coa/
│           │   ├── CoaApplication.java
│           │   ├── common/
│           │   ├── config/
│           │   ├── controller/
│           │   ├── security/
│           │   └── service/
│           │       ├── InMemoryCoaService.java
│           │       └── parse/OutlineParseEngine.java
│           └── resources/application.properties
├── frontend/
│   ├── package.json
│   └── src/
│       ├── api/index.js
│       ├── router/index.js
│       ├── stores/auth.js
│       ├── components/
│       └── views/
├── init.sql
└── README.md
```

## 技术栈

### 后端

- Java 21
- Spring Boot 4.0.5
- Spring Web MVC
- Spring JDBC
- MySQL
- Apache POI
  - `poi`：xls/doc 相关解析
  - `poi-ooxml`：xlsx/docx 相关解析
  - `poi-scratchpad`：老版 doc 解析
- Apache PDFBox：pdf 文本抽取
- Jackson：JSON 序列化和反序列化
- Spring Security Crypto：密码哈希校验

### 前端

- Vue 3
- Vue Router
- Pinia
- Axios
- ECharts
- Vue CLI

## 运行方式

### 1. 初始化数据库

项目根目录提供了 `init.sql`。先在 MySQL 中创建数据库并执行脚本。

```sql
CREATE DATABASE coa DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE coa;
SOURCE D:/COA/init.sql;
```

`init.sql` 会创建基础表、业务表和演示基础数据。主要表包括：

- 基础数据：`base_college`、`base_major`、`base_semester`、`base_course`
- 用户与登录：`sys_user`、`sys_role`、`sys_user_role`、`sys_login_session`
- 班级与学生：`base_class`、`base_student`、`class_course`
- 大纲与目标：`outline_main`、`course_teaching_content`、`teach_objective`、`obj_decompose`
- 考核配置：`assess_item`、`obj_assess_map`
- 智能解析：`parse_task`、`parse_objective_draft`、`parse_assess_item_draft`
- 成绩采集：`grade_import_batch`、`student_grade`
- 达成核算：`calc_rule`、`achieve_result`、`achieve_result_detail`
- 评价与改进：`student_eval`、`student_eval_dimension`、`teacher_reflection`、`supervisor_eval`、`suggestion_rule`、`intelligent_suggestion`、`improve_suggestion`、`improve_measure`

当前 `init.sql` 是标准初始化结构。后端启动时仍保留轻量兼容迁移逻辑，用于给旧库补齐 `parse_task` 解析 JSON 字段、班级/学生采集字段、教学内容表、毕业要求字段和智能建议来源字段；新库直接执行 `init.sql` 即可得到完整表结构。

### 2. 配置后端数据库连接

后端配置文件在：

```text
backend/coa/src/main/resources/application.properties
```

当前配置支持环境变量覆盖：

```properties
server.port=8081
spring.datasource.url=${COA_DATASOURCE_URL:jdbc:mysql://127.0.0.1:3306/coa?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}
spring.datasource.username=${COA_DATASOURCE_USERNAME:root}
spring.datasource.password=${COA_DATASOURCE_PASSWORD:qwq233}
```

如果本地数据库账号不同，可以直接改 `application.properties`，也可以设置环境变量。

### 3. 启动后端

```powershell
cd backend/coa
mvn spring-boot:run
```

后端默认运行在：

```text
http://localhost:8081
```

### 4. 启动前端

```powershell
cd frontend
npm install
npm run serve
```

前端默认运行在：

```text
http://localhost:8080
```

前端接口统一使用 `/api/v1` 前缀，开发环境一般通过 Vue CLI 代理转发到后端。

### 5. 构建与测试

后端测试：

```powershell
cd backend/coa
mvn -q test
```

前端构建：

```powershell
cd frontend
npm run build
```

## 系统整体业务闭环

系统的业务主线可以分为五步：

1. 课程大纲维护
   - 建立课程、学期、大纲版本。
   - 发布前校验课程目标权重、考核项权重、目标考核映射。

2. 教学目标维护
   - 手工新增或编辑课程目标。
   - 管理目标分解点。
   - 调整目标权重。

3. 智能解析导入
   - 上传 doc、docx、pdf 课程大纲。
   - 后端解析课程信息、课程目标、考核项、目标考核映射。
   - 前端展示可复核草稿。
   - 教师确认后写入正式课程、目标、考核项和映射表。

4. 数据采集
   - 创建班级并导入学生信息。
   - 将班级与课程、学期、任课教师建立关系。
   - 成绩批量导入 xls、xlsx、csv 文件。
   - 系统会在多工作表 Excel 中自动识别成绩表，并按课程考核项匹配成绩列。
   - 预览校验后确认导入有效成绩。
   - 学生成绩管理页面支持横向查看、筛选、新增、编辑、删除成绩。

5. 达成度核算与报告
   - 根据成绩、考核项、目标考核映射计算每个课程目标达成度。
   - 生成课程整体达成度。
   - 输出《课程目标达成情况评价及总结报告》，覆盖成绩统计、试题分析、课程目标达成情况、考核合理性、改进举措和应用方法。

## 前端架构与页面逻辑

前端入口：

```text
frontend/src/main.js
frontend/src/App.vue
frontend/src/router/index.js
```

### 路由与登录控制

路由由 `frontend/src/router/index.js` 管理。根路由使用 `AppShell` 作为登录后的主框架。

主要路由包括：

```text
/login                         登录页
/dashboard                     首页概览
/objectives/outlines           课程管理
/objectives/list               教学目标列表
/objectives/edit/:id?          教学目标编辑
/objectives/weights            目标权重管理
/objectives/parse-import       智能解析导入
/objectives/mapping            目标考核映射
/collect/classes               班级与学生管理
/collect/grades                成绩批量导入
/collect/grades/manage         学生成绩管理
/analysis/calculation          达成度核算与报告导出
```

`router.beforeEach` 中会读取 Pinia 登录状态：

- 如果本地有 token，则调用后端确认会话是否有效。
- 需要登录的页面未登录时跳转到 `/login`。
- 已登录用户访问登录页会回到 `/dashboard`。

登录状态保存在：

```text
frontend/src/stores/auth.js
```

接口封装在：

```text
frontend/src/api/index.js
```

其中 Axios 实例统一设置：

- `baseURL: /api/v1`
- 请求自动携带 Bearer Token
- 401 时清理本地登录状态并跳回登录页
- 统一把后端错误消息转换成 `Error`

### 页面布局

主布局：

```text
frontend/src/components/layout/AppShell.vue
frontend/src/components/layout/AppSidebar.vue
frontend/src/components/layout/AppHeader.vue
```

通用组件：

- `PanelCard.vue`：页面卡片容器
- `ModuleHeader.vue`：模块标题和页签区域
- `StatusBadge.vue`：状态标记
- `StatCard.vue`：统计卡片
- `ChartPanel.vue`：图表容器
- `EmptyState.vue`：空状态

### 课程与目标管理页面

相关页面：

```text
frontend/src/views/objectives/OutlineManage.vue
frontend/src/views/objectives/ObjectiveList.vue
frontend/src/views/objectives/ObjectiveEdit.vue
frontend/src/views/objectives/ObjectiveWeights.vue
frontend/src/views/objectives/ObjectiveMapping.vue
frontend/src/views/objectives/ParseImport.vue
```

主要逻辑：

- `OutlineManage.vue`
  - 作为“课程管理”入口，按课程和学期查看课程详情。
  - 维护课程基础信息和课程简介。
  - 查看结构化教学内容表。
  - 通过弹窗维护教学目标、目标分解与权重、目标考核映射和教学内容。
  - 发布大纲时触发后端校验。

- `ObjectiveList.vue`
  - 按课程和学期查看目标。
  - 跳转新增、编辑、权重管理、智能解析。

- `ObjectiveEdit.vue`
  - 编辑课程目标基本信息。
  - 编辑目标分解点。
  - 保存后写入 `teach_objective` 和 `obj_decompose`。

- `ObjectiveWeights.vue`
  - 批量调整目标权重。
  - 后端保存到 `teach_objective.weight`。

- `ObjectiveMapping.vue`
  - 维护目标与考核项之间的贡献权重。
  - 写入 `obj_assess_map`。

- `ParseImport.vue`
  - 上传课程大纲。
  - 展示课程信息复核区。
  - 展示目标草稿复核区。
  - 展示考核项草稿复核区。
  - 展示目标考核映射草稿复核区。
  - 确认后写入正式数据表。

### 数据采集页面

相关页面：

```text
frontend/src/views/collect/ClassCollectView.vue
frontend/src/views/collect/GradeImportView.vue
frontend/src/views/collect/GradeManageView.vue
```

#### 班级与学生管理

页面：`ClassCollectView.vue`

主要功能：

- 创建和编辑班级基础信息，写入 `base_class`。
- 为班级导入学生名单，写入 `base_student`。
- 支持手动新增、编辑、停用学生信息。
- 将班级与课程、学期、任课教师建立开课关系，写入 `class_course`。
- 成绩导入时可选择班级，导入批次和成绩记录会落到 `grade_import_batch.class_id`、`student_grade.class_id`、`student_grade.student_id`。

#### 成绩批量导入

页面：`GradeImportView.vue`

支持格式：

- xls
- xlsx
- csv

前端流程：

1. 选择课程、学期和班级。
2. 根据课程与学期展示当前考核项。
3. 上传成绩文件。
4. 调用后端 `/collect/grades/upload`。
5. 获取批次预览 `/collect/grades/batches/{batchId}/preview`。
6. 以横向表格展示学生成绩：
   - 固定列：行号、学号、姓名
   - 动态列：每个考核项
   - 状态列：异常行只显示“异常”
   - 每个成绩格保留具体错误提示
7. 教师可手动修改预览行。
8. 确认导入有效数据。

#### 学生成绩管理

页面：`GradeManageView.vue`

主要功能：

- 按课程、学期、考核项、学生关键字查询成绩。
- 不再进入页面就自动查全部，必须选择课程和学期后查询。
- 按学生横向展示各考核项成绩。
- 支持新增学生成绩。
- 支持编辑学生成绩。
- 支持删除学生成绩。
- 如果筛选了某个考核项，新增和删除只作用于该考核项。

后端对应：

- `GET /api/v1/collect/classes`
- `POST /api/v1/collect/classes`
- `PUT /api/v1/collect/classes/{id}`
- `GET /api/v1/collect/classes/{id}/students`
- `POST /api/v1/collect/classes/{id}/students/upload`
- `POST /api/v1/collect/students`
- `PUT /api/v1/collect/students/{id}`
- `DELETE /api/v1/collect/students/{id}`
- `GET /api/v1/collect/class-courses`
- `POST /api/v1/collect/class-courses`
- `DELETE /api/v1/collect/class-courses/{id}`
- `GET /api/v1/collect/grades`
- `POST /api/v1/collect/grades/rows`
- `DELETE /api/v1/collect/grades/rows`

### 结果分析与教学改进页面

相关页面：

```text
frontend/src/views/analysis/AchievementCalculation.vue
```

#### 达成度核算

页面：`AchievementCalculation.vue`

主要功能：

- 选择课程和学期。
- 查看已有核算结果。
- 设置核算方法和阈值。
- 运行核算。
- 查看每个目标的达成度、是否达成、成绩项贡献明细。
- 查看成绩数据覆盖情况。
- 查看智能分析结论，包括整体风险、关键发现、薄弱目标、薄弱考核项和改进动作。
- 查看课程目标达成度、考核项平均得分率、考核维度贡献和成绩确认情况图表。
- 下载课程目标达成情况评价及总结报告。

## 后端架构与接口逻辑

后端主入口：

```text
backend/coa/src/main/java/com/example/coa/CoaApplication.java
```

后端采用“Controller + Service + JDBC”的轻量结构：

- Controller 层只负责 HTTP 参数接收和调用服务。
- `InMemoryCoaService` 承担主要业务逻辑、SQL 查询、数据转换。
- `OutlineParseEngine` 专门负责课程大纲智能解析。
- `SessionService` 和 `AuthInterceptor` 负责登录会话和请求鉴权。
- `GlobalExceptionHandler` 统一处理异常输出。

### Controller 分工

```text
AuthController              登录、登出、当前用户
CatalogController           课程、学期、考核项目录
DashboardController         首页概览
OutlineController           课程管理、大纲 CRUD 与发布
ObjectiveController         教学目标 CRUD、批量权重
MappingController           目标考核映射
ParseController             智能解析任务、草稿复核、确认写入
CollectController           班级、学生、班级开课、成绩上传、预览、确认导入与成绩维护
AchievementController       达成度查询与核算任务
ReportController            达成度报告预览与 Word 导出
```

### 主要接口

#### 登录与基础数据

```text
POST /api/v1/auth/login
POST /api/v1/auth/logout
GET  /api/v1/auth/me
GET  /api/v1/reference/catalogs
```

#### 课程大纲

```text
GET   /api/v1/outlines
POST  /api/v1/outlines
PUT   /api/v1/outlines/{id}
PATCH /api/v1/outlines/{id}/publish
POST  /api/v1/outlines/{id}/publish
```

发布大纲时后端会校验：

- 当前大纲下课程目标权重合计必须为 100。
- 当前大纲下考核项权重合计必须为 100。
- 必须存在目标考核映射。

#### 教学目标

```text
GET  /api/v1/objectives
GET  /api/v1/objectives/{id}
POST /api/v1/objectives
PUT  /api/v1/objectives/{id}
PUT  /api/v1/objectives/batch-weights
POST /api/v1/objectives/batch
```

#### 目标考核映射

```text
GET /api/v1/obj-assess-maps
PUT /api/v1/obj-assess-maps
```

#### 智能解析导入

```text
POST   /api/v1/parse/upload
GET    /api/v1/parse/tasks/{taskId}
PUT    /api/v1/parse/drafts/objectives/{id}
POST   /api/v1/parse/tasks/{taskId}/drafts/objectives
DELETE /api/v1/parse/drafts/objectives/{id}
PUT    /api/v1/parse/drafts/assess-items/{id}
POST   /api/v1/parse/tasks/{taskId}/drafts/assess-items
DELETE /api/v1/parse/drafts/assess-items/{id}
PUT    /api/v1/parse/tasks/{taskId}/mapping
POST   /api/v1/parse/tasks/{taskId}/confirm
```

#### 成绩采集

```text
GET    /api/v1/collect/classes
POST   /api/v1/collect/classes
PUT    /api/v1/collect/classes/{id}
GET    /api/v1/collect/classes/{id}/students
POST   /api/v1/collect/classes/{id}/students/upload
POST   /api/v1/collect/students
PUT    /api/v1/collect/students/{id}
DELETE /api/v1/collect/students/{id}
GET    /api/v1/collect/class-courses
POST   /api/v1/collect/class-courses
DELETE /api/v1/collect/class-courses/{id}
POST   /api/v1/collect/grades/upload
GET    /api/v1/collect/grades/batches/{batchId}/preview
PUT    /api/v1/collect/grades/batches/{batchId}/preview-rows
POST   /api/v1/collect/grades/batches/{batchId}/confirm
GET    /api/v1/collect/grades
POST   /api/v1/collect/grades/rows
DELETE /api/v1/collect/grades/rows
```

#### 评价与反思

```text
GET  /api/v1/collect/student-evals
POST /api/v1/collect/student-evals/batch
GET  /api/v1/collect/supervisor-evals
GET  /api/v1/collect/teacher-reflections
POST /api/v1/collect/teacher-reflections
```

#### 达成度与分析

```text
GET  /api/v1/achieve/results
POST /api/v1/achieve/tasks
GET  /api/v1/analysis/course-overview
GET  /api/v1/analysis/trend
```

#### 建议与改进

```text
GET   /api/v1/intelligent-suggestions
GET   /api/v1/intelligent-suggestions/{id}
PATCH /api/v1/intelligent-suggestions/{id}/read
PATCH /api/v1/intelligent-suggestions/{id}/dismiss
POST  /api/v1/intelligent-suggestions/{id}/create-measure
GET   /api/v1/improve/measures
POST  /api/v1/improve/measures
PUT   /api/v1/improve/measures/{id}
PUT   /api/v1/improve/measures/{id}/effect
```

## 数据库核心关系

### 课程大纲与目标

```text
base_course 1 --- n outline_main
base_course + base_semester 1 --- n course_teaching_content
outline_main 1 --- n teach_objective
teach_objective 1 --- n obj_decompose
outline_main 1 --- n assess_item
teach_objective n --- n assess_item，通过 obj_assess_map 连接
```

含义：

- 一门课程可以有多个学期或多个版本的大纲。
- 教学内容表按课程和学期存储，来源可以是智能解析或人工维护。
- 一个大纲下有多个教学目标。
- 一个教学目标可以拆分为多个分解点。
- 一个大纲下有多个考核项。
- 一个教学目标由多个考核项支撑，贡献比例存储在 `obj_assess_map.contribution_weight`。

### 智能解析

```text
parse_task 1 --- n parse_objective_draft
parse_task 1 --- n parse_assess_item_draft
parse_task.parsed_course_json
parse_task.parsed_mapping_json
parse_task.obj_assess_matrix_json
```

含义：

- 上传大纲后创建一条解析任务。
- 解析出的目标先进入目标草稿表。
- 解析出的考核项先进入考核项草稿表。
- 课程基本信息、映射建议、映射矩阵以 JSON 存在 `parse_task` 中。
- 教学内容解析结果最终会落到 `course_teaching_content`，不再只停留在 JSON 中。
- 教师复核后再写入正式表。

### 班级与学生

```text
base_major 1 --- n base_class
base_class 1 --- n base_student
base_class n --- n base_course，通过 class_course 连接并区分 semester
```

含义：

- 班级基础信息存储在 `base_class`。
- 学生基础信息存储在 `base_student`，可按班级导入或手动维护。
- `class_course` 表示某个班级在某个学期修读某门课程，并可关联任课教师。

### 成绩采集

```text
grade_import_batch 1 --- n student_grade
grade_import_batch n --- 1 base_class
student_grade n --- 1 base_student
student_grade n --- 1 base_class
student_grade n --- 1 assess_item
student_grade n --- 1 base_course
student_grade n --- 1 base_semester
```

含义：

- 每次上传成绩文件生成一个导入批次。
- 每个学生的每个考核项成绩是一条 `student_grade`。
- 成绩批次和成绩明细可以关联到班级与学生基础信息，便于后续按班级分析。
- 只有 `grade_import_batch.status = 'CONFIRMED'` 且 `student_grade.valid_flag = 1` 的成绩参与达成度核算。

### 达成度核算

```text
calc_rule 1 --- n achieve_result
achieve_result 1 --- n achieve_result_detail
achieve_result.objective_id = NULL 表示课程整体达成度
```

含义：

- 每次核算保存一组结果。
- 每个课程目标有一条 `achieve_result`。
- 每条目标结果下有若干 `achieve_result_detail`，记录各考核项贡献。
- 课程整体达成度作为 `objective_id = NULL` 的汇总记录保存。

## 智能解析算法：OutlineParseEngine

核心文件：

```text
backend/coa/src/main/java/com/example/coa/service/parse/OutlineParseEngine.java
```

这是系统目前最重要的算法模块，负责把非结构化或半结构化课程大纲转换成可复核的结构化草稿。

### 输入与输出

输入：

```java
parse(String fileName, byte[] fileBytes)
```

支持文件：

- doc
- docx
- pdf

输出：

```java
ParsedOutlineDraft
```

包含：

- `objectives`：课程目标草稿
- `assessItems`：考核项草稿
- `mappings`：目标-考核项映射建议
- `objAssessMatrix`：目标考核映射矩阵
- `segments`：原文片段
- `courseInfo`：课程基本信息

### 总体流程

`parse` 方法的主流程可以拆成八步：

1. 判断文件扩展名。
2. 根据文件类型抽取文本片段。
3. 提取课程基本信息。
4. 提取课程目标候选。
5. 为课程目标分配权重。
6. 生成目标草稿建议。
7. 提取考核项并规范化权重。
8. 提取目标考核映射矩阵并转换成映射建议。

伪代码：

```text
parse(fileName, bytes):
  extension = fileExtension(fileName)
  segments = extractFromDoc / extractFromDocx / extractFromPdf
  courseInfo = extractCourseInfo(segments, fileName)
  objectiveCandidates = extractObjectiveCandidates(segments)
  assignObjectiveWeights(objectiveCandidates)
  objectives = buildObjectiveSuggestions(objectiveCandidates)
  assessCandidates = extractAssessCandidates(segments)
  completeAssessTypes(assessCandidates)
  normalizeAssessWeights(assessCandidates)
  assessItems = assessCandidates -> AssessItemDraftSuggestion
  matrix = buildObjAssessMatrix(segments)
  mappings = matrixToMappings(matrix, assessItems)
  return ParsedOutlineDraft(...)
```

### 文本抽取策略

#### doc

使用：

```java
HWPFDocument
WordExtractor
```

处理方式：

- 逐段读取 Word 段落。
- 调用 `sanitizeText` 清理空白字符。
- 保存为 `SourceSegment`。

#### docx

使用：

```java
XWPFDocument
XWPFParagraph
XWPFTable
```

处理方式：

- 遍历文档 body 元素。
- 普通段落直接取文本。
- 表格通过 `flattenTable` 拉平成一行行文本。
- 表格行会保留单元格边界，方便后续识别目标表、成绩表、映射表。

#### pdf

使用：

```java
PDFBox Loader
PDFTextStripper
```

处理方式：

- 设置 `setSortByPosition(true)`，尽量按版面顺序抽取文本。
- 按行拆分。
- 如果提取不到文本，认为是扫描版 PDF，不进入 OCR 流程。

目前 PDF 支持的是“可复制文本 PDF”，不支持纯扫描图像 PDF。

### 课程基本信息提取

关键方法：

```java
extractCourseInfo
collectCourseInfoFromText
matchLabeledValue
collectSplitHoursCredits
deriveNameFromFile
```

识别字段包括：

- 课程代码
- 中文课程名
- 英文课程名
- 课程类型
- 授课对象
- 教学语言
- 开课院系
- 学时
- 学分
- 先修课程
- 课程负责人

核心策略：

1. 建立字段别名表 `COURSE_FIELD_ALIASES`。
2. 扫描所有文本片段。
3. 识别“字段名：字段值”“字段名 字段值”等形式。
4. 对学时、学分做额外数字解析。
5. 如果课程名没有识别到，则从文件名推断。

### 课程目标识别

关键方法：

```java
extractObjectiveCandidates
processObjectiveLine
matchObjectiveFromCells
matchGradeTableText
matchObjectiveLine
matchObjectiveHeading
shouldKeepObjective
shouldSkipObjectiveLine
```

目标识别面向两类大纲：

1. 段落型大纲
   - “课程目标1：……”
   - “目标 1 ……”
   - “Objective 1 ……”

2. 表格型大纲
   - 单元格中包含目标编号和目标内容。
   - 成绩构成表中某行同时包含目标内容和占比。

主要规则：

- 使用正则识别显式目标：
  - `EXPLICIT_OBJECTIVE_PATTERN`
  - `NUMBERED_OBJECTIVE_PATTERN`
  - `ENGLISH_NUMBERED_OBJECTIVE_PATTERN`
- 使用章节关键词判断是否进入目标区域：
  - 课程目标
  - 教学目标
  - 学习目标
  - course objectives
- 使用停止关键词判断目标区域结束：
  - 教学内容
  - 考核方式
  - 成绩构成
  - 教材
  - assessment
  - grading
- 使用噪声关键词过滤非目标内容。
- 使用动作关键词判断一句话是否像目标：
  - 掌握、理解、熟悉、能够、运用、分析、设计、解决、形成、培养等。

### 目标合并策略

有些大纲中一个课程目标会跨多行书写。例如：

```text
课程目标1：
掌握环境监测的基本原理，
能够完成常规监测方案设计。
```

解析器用 `ObjectiveAccumulator` 做临时累积：

- 识别到新目标时开启 accumulator。
- 后续行如果不像新目标但像目标延续内容，则追加到当前目标。
- 遇到新目标或停止区域时 flush。

这样可以减少目标被拆成多条的问题。

### 目标权重识别

关键方法：

```java
extractWeight
assignObjectiveWeights
balanceWeights
```

策略：

1. 优先识别目标文本中的显式百分比。
2. 如果目标考核映射表中给出了“占总成绩比例”，则使用表格中的目标总权重。
3. 如果所有目标均没有可靠权重，则按目标数量平均分配。
4. 平均分配时做四舍五入修正，保证合计接近 100。

例如 3 个目标无权重：

```text
33.33, 33.33, 33.34
```

### 目标类型分类

关键方法：

```java
classifyObjectiveType
computeObjectiveConfidence
confidenceLevel
```

目标类型分为：

```text
1 = 知识
2 = 能力
3 = 素养
```

分类依据是关键词打分：

- 知识类关键词：
  - 掌握、了解、熟悉、理解、认识、原理、概念、理论、方法
- 能力类关键词：
  - 能够、运用、应用、分析、设计、实现、解决、处理、判断
- 素养类关键词：
  - 培养、形成、树立、增强、提升、责任、态度、团队、职业

置信度由以下因素共同决定：

- 是否有明确目标编号。
- 是否在目标章节内。
- 是否包含动作关键词。
- 是否识别到类型关键词。
- 文本长度是否合理。
- 是否包含显式权重。

最终转换为：

- HIGH
- MEDIUM
- LOW

### 考核项识别

关键方法：

```java
extractAssessCandidates
collectAssessItemsFromCells
collectGenericAssessItems
extractAssessWeight
completeAssessTypes
normalizeAssessWeights
```

支持识别的考核项类型：

```text
normal   平时
mid      期中
final    期末
practice 实践
report   报告
```

识别策略：

1. 在“考核方式、成绩构成、评分标准”等区域内扫描。
2. 对表格单元格按行和按列解析。
3. 用 `ASSESS_KEYWORD_RULES` 匹配考核项名称和类型。
4. 从同一行或附近文本中提取百分比。
5. 如果只出现“30%、70%”这类数值，会结合列标题或常见顺序推断。
6. 如果考核项权重合计不为 100，会归一化处理。

示例：

```text
平时成绩 30%
期末成绩 70%
```

输出：

```text
平时成绩 normal 30
期末成绩 final 70
```

### 目标考核映射矩阵识别

关键方法：

```java
buildObjAssessMatrix
isMappingTableHeader
detectMappingColumnHeaders
isAssessWeightFooter
extractAssessWeightsFromFooter
matrixToMappings
findAssessItemForHeader
assessTypeForHeader
```

该部分用于识别大纲中表格形式的“课程目标与考核方式关系”。

典型表格：

```text
课程目标 | 平时成绩 | 期末成绩 | 占总成绩比例
目标1   | 50%      | 20%      | 29%
目标2   | 50%      | 30%      | 36%
目标3   | 0%       | 50%      | 35%
考核方式占总成绩比例 | 30% | 70% | 100%
```

解析逻辑：

1. 识别表头是否包含课程目标、考核方式、占总成绩比例等关键词。
2. 从表头中提取考核方式列名。
3. 对每一行识别目标编号、目标内容、各列比例、目标总比例。
4. 识别页脚中的“考核方式占总成绩比例”。
5. 将矩阵转换为映射建议。

这里需要区分两个概念：

- 表格单元格里的比例：某个目标在某种考核方式中的分配比例。
- 数据库 `obj_assess_map.contribution_weight`：某个考核项对某个目标达成度计算时的行内贡献权重。

后端确认写入时会结合考核项总成绩权重进行换算：

```text
weightedPart = 映射表单元格比例 * 考核项总权重
contributionWeight = weightedPart / 当前目标所有 weightedPart 之和 * 100
```

这样能够把大纲中的二维成绩构成表转换成达成度核算需要的目标内部贡献权重。

## 智能解析导入写入逻辑

核心后端方法：

```java
uploadParseFile
getParseTaskDetail
updateParseDraft
updateParseAssessDraft
updateParseMappingMatrix
confirmParseTask
```

### 上传解析

前端上传：

```text
POST /api/v1/parse/upload
```

后端动作：

1. 校验文件是否为空、大小是否超过 20MB、格式是否为 doc/docx/pdf。
2. 创建 `parse_task`。
3. 调用 `OutlineParseEngine.parse`。
4. 将课程信息保存到 `parse_task.parsed_course_json`。
5. 将目标草稿保存到 `parse_objective_draft`。
6. 将考核项草稿保存到 `parse_assess_item_draft`。
7. 将映射建议保存到 `parse_task.parsed_mapping_json`。
8. 将映射矩阵保存到 `parse_task.obj_assess_matrix_json`。
9. 更新任务状态为 `DONE` 或 `FAILED`。

### 复核草稿

前端 `ParseImport.vue` 允许教师修改：

- 课程信息
- 目标编号
- 目标内容
- 目标类型
- 目标权重
- 目标状态：待定、已确认、已忽略
- 考核项名称
- 考核项类型
- 考核项权重
- 考核项状态
- 教学内容、涉及目标、基本要求和教学方式
- 目标考核映射矩阵
- 各板块整体确认状态和选择性覆盖项

如果目标权重来自平均默认值，删除目标后会重新平均分配权重。

### 确认写入

确认写入调用：

```text
POST /api/v1/parse/tasks/{taskId}/confirm
```

写入逻辑：

1. 校验已确认目标权重合计为 100。
2. 校验已确认考核项权重合计为 100。
3. 根据课程信息复核区决定：
   - 新建课程
   - 或覆盖已有课程字段
4. 创建或定位 `outline_main`。
5. 如选择覆盖已有数据，则清理原有目标、分解点、考核项、映射。
6. 将草稿目标写入 `teach_objective`。
7. 为每个目标生成默认分解点写入 `obj_decompose`。
8. 将考核项写入 `assess_item`。
9. 将映射矩阵或映射建议写入 `obj_assess_map`。
10. 将教学内容表写入 `course_teaching_content`。
11. 将 `parse_task.status` 更新为 `CONFIRMED`，并在 `parsed_course_json` 中保留复核确认和覆盖选择。

## 成绩导入与成绩管理算法

核心方法：

```java
uploadGradeFile
parseGradeFile
readWorkbookRows
readCsvRows
resolveGradeColumns
matchAssessItemByHeader
parseScore
markDuplicateGradeRows
confirmGradeBatch
getImportedGrades
saveImportedGradeRow
deleteImportedGradeRow
```

### 批量导入流程

1. 前端选择课程和学期。
2. 后端根据课程和学期获取当前大纲下的考核项。
3. 上传 xls、xlsx、csv。
4. 后端读取第一行作为表头。
5. 识别哪些列是考核项成绩列。
6. 从每一行读取学生学号、姓名和各考核项成绩。
7. 逐单元格生成 `student_grade`。
8. 标记异常数据：
   - 学号为空
   - 姓名为空
   - 成绩为空
   - 成绩不是数字
   - 成绩超出 0 到满分
   - 与已有确认成绩重复
   - 批次内重复
9. 前端展示预览表。
10. 教师修正异常行。
11. 确认后将批次状态改为 `CONFIRMED`。

### 成绩列匹配

`resolveGradeColumns` 会把表头和当前课程考核项匹配。

匹配优先级：

1. 表头包含考核项名称。
2. 表头匹配考核项类型关键词。

例如：

```text
平时成绩、课堂、作业、regular、assignment -> normal
期中、midterm -> mid
期末、考试、final、exam -> final
实验、实践、project、lab -> practice
报告、report、presentation -> report
```

表头也可以写满分：

```text
期末成绩（100分）
实验成绩（50分）
```

系统会通过 `extractMaxScore` 提取满分。

### 成绩管理

学生成绩管理页使用 `getImportedGrades` 查询已经确认导入的有效成绩。

查询条件：

- 课程
- 学期
- 考核项
- 学号或姓名关键字

返回结构：

```text
columns: 考核项列
rows:    按学生聚合后的横向成绩行
items:   原始成绩记录
total:   学生行数
```

新增和编辑时会写入 `student_grade`。如果不存在手工录入批次，会自动创建一个 `grade_import_batch.import_mode = 'manual'` 的确认批次。

## 达成度核算重点算法

核心方法：

```java
runAchievementCalculation
perStudentItemRates
computeAvgRates
computeStudentObjectiveValues
mappingWeights
defaultMappingWeights
achievementResults
achievementResultDetails
achievementOverall
generateSuggestions
```

### 数据前提

核算依赖以下数据：

1. 当前课程当前学期的大纲。
2. 大纲下的课程目标。
3. 大纲下的考核项。
4. 目标-考核项映射。
5. 已确认且有效的学生成绩。

如果没有目标-考核项映射，系统会使用 fallback：

```text
按考核项总成绩权重临时分配到目标。
```

### 单个考核项得分率

每条成绩先换算为得分率：

```text
scoreRate = score / maxScore
```

例如：

```text
score = 80
maxScore = 100
scoreRate = 0.8
```

如果允许补考或重修成绩，系统可以对同一学生同一考核项取更优记录。

### 单个学生对某目标的达成值

某个目标由多个考核项支撑：

```text
studentObjectiveValue =
  Σ(学生在考核项上的得分率 × 该考核项对该目标的贡献权重)
```

其中贡献权重使用百分制，需要除以 100：

```text
value += scoreRate * contributionWeight / 100
```

### 目标达成度

当前实现支持多种计算方式：

#### weighted_avg

默认方式。

```text
目标达成度 = 所有学生在该目标上的达成值平均数
```

是否达成：

```text
目标达成度 >= thresholdValue
```

#### threshold

阈值通过率方式。

```text
学生目标达成值 >= passThreshold 视为该学生达成
目标达成度 = 达成学生数 / 总学生数
```

是否达成：

```text
目标达成度 >= thresholdValue
```

#### custom

自定义目标阈值方式。

每个目标可以设置自己的阈值：

```text
目标达成度 >= customThresholds[objectiveId]
```

如果某个目标未设置自定义阈值，则使用全局 `thresholdValue`。

### 课程整体达成度

课程整体达成度按目标权重加权：

```text
overallAchievement =
  Σ(目标达成度 × 目标权重 / 100)
```

最终汇总结果写入：

```text
achieve_result.objective_id = NULL
```

### 结果明细

每个目标结果写入：

```text
achieve_result
```

每个考核项贡献写入：

```text
achieve_result_detail
```

明细字段包括：

- 考核项
- 得分率
- 贡献权重
- 对目标达成值的贡献

### 页面智能分析与图表

达成度页面在返回核算结果时同步生成 `smartAnalysis` 与 `chartData`。

`smartAnalysis` 包括：

- 整体结论和风险等级。
- 已达成目标数和目标总数。
- 关键发现。
- 薄弱课程目标。
- 薄弱考核项。
- 建议改进动作。

`chartData` 包括：

- `objectiveBars`：课程目标达成度柱状图数据。
- `assessRates`：考核项平均得分率图表数据。
- `componentBars`：平时、期中、期末等维度贡献数据。
- `gradeCoverage`：已确认和待确认成绩记录分布。

### 达成度报告生成

核算完成后，`ReportController` 通过 `ReportDataAssembler` 汇总大纲、目标、考核项、成绩和达成度结果，再由 `ReportWordBuilder` 生成 Word 文档。

报告生成重点：

- 课程概况和课程目标对毕业要求的支撑关系。
- 课程目标考核及成绩评定方式。
- 成绩统计、试题分析和成绩分析。
- 课程目标达成情况和分数段达成情况。
- 课程目标达成情况报表、考核方式合理性、改进举措和应用方法。

## 关键设计点

### 1. 草稿复核而不是直接入库

智能解析不直接写入正式目标和考核项，而是先生成草稿。

优点：

- 教师可以修改错误解析结果。
- 可以标记待定、确认、忽略。
- 可以补充系统未识别内容。
- 降低错误数据污染正式业务表的风险。

### 2. 支持多种文件格式

智能解析支持：

- doc
- docx
- pdf

成绩导入支持：

- xls
- xlsx
- csv

这样能覆盖老师常见的课程大纲和成绩表格式。

### 3. 表格型数据优先

课程大纲中的目标、考核项、映射关系经常以表格形式出现。系统对 docx 表格做了拉平处理，并专门识别：

- 目标表
- 成绩构成表
- 目标考核映射表

相比只按段落解析，这能显著提高结构化准确率。

### 4. 权重自动补全

目标权重和考核项权重有时不在文档中明确给出。

当前策略：

- 能识别显式权重就使用显式权重。
- 识别不到目标权重时按目标数量平均分配。
- 删除未知权重目标后，剩余目标自动重新平均。
- 考核项权重会归一化到 100。

### 5. 达成度核算只使用确认数据

成绩数据必须满足：

```text
grade_import_batch.status = 'CONFIRMED'
student_grade.valid_flag = 1
```

这样可以避免未校验或异常成绩参与核算。

### 6. 报告闭环

系统不是只算达成度，还会把核算结果落到达成度报告中：

- 识别低达成课程目标。
- 生成成绩统计、试题分析和成绩分析文本。
- 形成考核方式合理性判断。
- 给出改进举措和应用方法。

这体现了“评价-报告-改进”的教学质量闭环。

## 中期检查演示建议

推荐演示顺序：

1. 登录系统，进入首页概览。
2. 打开课程管理，展示课程详情、课程简介、教学内容表和弹窗式目标维护。
3. 进入智能解析导入，上传课程大纲文件。
4. 展示解析出的课程信息、课程目标、考核项、目标考核映射。
5. 修改一条目标权重，说明映射总比例会跟随目标权重联动。
6. 确认写入正式数据。
7. 在课程管理弹窗中展示正式目标、权重和目标考核映射矩阵。
8. 进入班级与学生管理，创建班级、导入学生，并绑定班级课程。
9. 进入成绩批量导入，选择班级并上传成绩表。
10. 展示横向预览、异常提示和手动修正。
11. 确认导入成绩。
12. 打开学生成绩管理，展示按学生横向成绩表和增删改查能力。
13. 进入达成度核算，设置阈值并运行核算。
14. 展示每个目标达成度、考核项贡献明细、智能分析和图表看板。
15. 下载达成度报告，检查成绩统计、课程目标达成情况、考核合理性和改进举措。

## 常见问题说明

### 为什么智能解析结果需要教师确认？

课程大纲格式不统一，有些大纲是表格，有些是段落，有些 PDF 会丢失表格结构。算法可以提高录入效率，但不能完全替代教师判断，因此系统采用“解析草稿 + 人工复核 + 确认写入”的方式。

### PDF 为什么有时解析不理想？

当前 PDF 解析基于文本抽取，不做 OCR。如果 PDF 是扫描图片，系统无法获取真实文本。建议使用 Word 版大纲或可复制文本的 PDF。

### 目标考核映射为什么要换算？

大纲表格中的比例常常表示“某个考核方式如何分配给各目标”，而达成度计算需要的是“某个目标由哪些考核项贡献”。两者方向不同，所以确认写入时要结合考核项总成绩权重做一次换算。

### 为什么成绩导入后还要确认？

导入文件可能存在空学号、空姓名、非数字成绩、超满分、重复成绩等问题。确认前先进入预览校验，可以避免异常数据进入正式核算。

### 为什么达成度核算结果会保存历史？

每次运行核算都会生成批次号，并把旧的当前结果设为非当前状态。这样便于后续扩展历史追踪和趋势分析。

## 当前已实现重点

- 用户登录与会话校验。
- 课程管理。
- 教学目标管理。
- 目标权重批量维护。
- 目标考核映射维护。
- doc、docx、pdf 智能解析导入。
- 课程信息解析与覆盖/新建。
- 课程目标解析。
- 考核项解析。
- 目标考核映射表解析。
- 解析草稿复核。
- 解析结果确认写入。
- 班级创建、学生信息导入与学生信息维护。
- 班级课程绑定。
- xls、xlsx、csv 成绩批量导入。
- 成绩预览校验与行级修正。
- 学生成绩管理增删改查。
- 达成度核算。
- 达成度智能分析与图表看板。
- 达成度报告 Word 导出（含智能文字分析）。

## 报告导出

达成度核算完成后，在“达成度核算”页面底部点击“下载达成度报告”，系统将自动生成符合重庆理工大学格式要求的 Word 文档（.docx）。

报告包含以下内容：

- 封面（课程名称、学年学期）
- 一、课程概况
- 二、课程目标对毕业要求的支撑
- 三、课程成绩评定及目标达成评价方法
- 四、成绩统计与试题分析（含成绩分布表）
- 五、课程目标达成情况（含分段统计）
- 六、课程目标达成情况报表
- 七、课程考核方式合理性
- 九、课程目标达成情况、改进举措及应用方法
- 附录A：全体学生各目标达成度明细
- 附录B：全体学生总成绩明细

## 后续可优化方向

- 为 `OutlineParseEngine` 增加单元测试样例库，覆盖不同学院的大纲格式。
- 引入 OCR 或大模型辅助解析扫描版 PDF。
- 将 `InMemoryCoaService` 拆分为多个领域服务，降低单类复杂度。
- 为成绩管理增加分页和批量编辑。
- 为达成度核算增加更多规则，如最低项约束、分层达成度、班级维度分析。
- 增加导出功能，如成绩模板导出、批量报告导出。
- 增加操作日志，便于审计教师修改过程。
