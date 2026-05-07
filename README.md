# COA 教学目标达成系统

本项目是一个面向高校课程教学目标达成度管理的 Web 系统，覆盖课程大纲维护、智能解析导入、班级与学生数据采集、成绩导入与维护、达成度核算、报告预览与 Word 导出，以及超级管理员的教师账号管理。

## 技术栈

后端：

- Java 21
- Spring Boot 4.0.5
- Spring Web MVC
- Spring JDBC
- MySQL
- Apache POI
- Apache PDFBox
- Spring Security Crypto

前端：

- Vue 3
- Vue Router
- Pinia
- Axios
- ECharts
- Vue CLI

## 目录结构

```text
COA/
├── backend/coa/                  Spring Boot 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/coa/
│       │   ├── controller/
│       │   ├── security/
│       │   └── service/
│       └── resources/application.properties
├── frontend/                     Vue 前端
│   ├── package.json
│   └── src/
│       ├── api/
│       ├── components/
│       ├── router/
│       ├── stores/
│       └── views/
├── init.sql                      当前数据库初始化脚本
└── README.md
```

## 快速启动

### 1. 初始化数据库

```sql
SOURCE D:/COA/init.sql;
```

`init.sql` 会创建 `coa` 数据库并写入必要演示数据。脚本可重复执行，会先清理旧表再重建当前结构。

默认账号：

```text
超级管理员：admin / admin123
教师账号：wangbin / 123456
教师账号：liuqing / 123456
教师账号：chenyu / 123456
```

### 2. 启动后端

```powershell
cd backend/coa
.\mvnw.cmd spring-boot:run
```

默认地址：

```text
http://localhost:8081
```

数据库配置位于：

```text
backend/coa/src/main/resources/application.properties
```

可通过环境变量覆盖：

```text
COA_DATASOURCE_URL
COA_DATASOURCE_USERNAME
COA_DATASOURCE_PASSWORD
```

### 3. 启动前端

```powershell
cd frontend
npm install
npm run serve
```

默认地址：

```text
http://localhost:8080
```

开发环境 `/api` 请求由 `frontend/vue.config.js` 代理到 `http://localhost:8081`。

## 当前功能

### 账号与权限

- 登录、退出、当前用户查询。
- 会话使用 Bearer Token。
- 禁用账号无法登录，已有 token 也会失效。
- 超级管理员拥有教师页面同等访问能力，并额外拥有用户管理入口。
- 用户管理支持新增教师、删除教师、重置教师密码、禁用/启用教师账号。

### 课程与目标

- 教师可直接新增课程，也可删除自己创建并在所选学期负责的课程。
- 课程详情维护。
- 教学内容维护。
- 课程大纲创建、编辑、发布。
- 教学目标增删改、目标分解点维护、目标权重维护。
- 考核项维护。
- 目标与考核项映射维护。

### 智能解析导入

- 支持上传 doc、docx、pdf。
- 解析课程信息、教学目标、考核项、教学内容和目标考核映射。
- 解析结果先进入草稿复核区，教师确认后写入正式业务表。

### 数据采集

- 班级创建和编辑。
- 学生信息导入、新增、编辑、删除。
- 班级课程绑定。
- 成绩文件上传，支持 xls、xlsx、csv。
- 成绩导入预览、异常行修正、确认导入。
- 学生成绩横向表查询、新增、编辑、删除。
- 考核内容及方式表维护。

### 达成度与报告

- 选择课程、学期、班级进行达成度核算。
- 支持默认加权平均规则，默认达成阈值 `0.6`，良好阈值 `0.7`。
- 生成目标达成度、课程整体达成度、考核项贡献明细。
- 生成核算建议，报告预览会读取这些建议。
- 报告预览和 Word 导出共用同一份后端报告组装逻辑。

## 前端路由

```text
/login                         登录
/dashboard                     首页概览
/objectives/outlines           课程管理
/objectives/list               教学目标列表
/objectives/edit/:id?          教学目标编辑
/objectives/weights            目标权重
/objectives/parse-import       智能解析导入
/objectives/mapping            目标考核映射
/collect/classes               班级与学生管理
/collect/grades/manage         学生成绩管理
/analysis/calculation          达成度核算
/analysis/report               报告预览和导出
/admin/users                   用户管理，仅 ADMIN 可访问
```

## 后端接口

### 认证

```text
POST /api/v1/auth/login
POST /api/v1/auth/logout
POST /api/v1/auth/refresh
GET  /api/v1/auth/me
```

### 管理员

```text
GET    /api/v1/admin/teachers
POST   /api/v1/admin/teachers
PATCH  /api/v1/admin/teachers/{id}/password
PATCH  /api/v1/admin/teachers/{id}/status
DELETE /api/v1/admin/teachers/{id}
```

### 目录与课程

```text
GET /api/v1/reference/catalogs
GET /api/v1/courses
POST /api/v1/courses
GET /api/v1/courses/{id}
PUT /api/v1/courses/{id}
DELETE /api/v1/courses/{id}
PUT /api/v1/courses/{id}/teaching-contents
PUT /api/v1/courses/{id}/assess-items
GET /api/v1/semesters
GET /api/v1/assess-items
```

### 大纲与目标

```text
GET   /api/v1/outlines
POST  /api/v1/outlines
PUT   /api/v1/outlines/{id}
PATCH /api/v1/outlines/{id}/publish
POST  /api/v1/outlines/{id}/publish

GET  /api/v1/objectives
GET  /api/v1/objectives/{id}
POST /api/v1/objectives
PUT  /api/v1/objectives/{id}
PUT  /api/v1/objectives/batch-weights
POST /api/v1/objectives/batch

GET /api/v1/obj-assess-maps
PUT /api/v1/obj-assess-maps
```

### 智能解析

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
PUT    /api/v1/parse/tasks/{taskId}/course-info
POST   /api/v1/parse/tasks/{taskId}/confirm
```

### 数据采集

```text
GET    /api/v1/collect/assessment-contents
PUT    /api/v1/collect/assessment-contents
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
DELETE /api/v1/collect/grades/batches/{batchId}
GET    /api/v1/collect/grades
POST   /api/v1/collect/grades/rows
DELETE /api/v1/collect/grades/rows
```

### 达成度与报告

```text
GET  /api/v1/analysis/dashboard
GET  /api/v1/achieve/results
POST /api/v1/achieve/tasks
GET  /api/v1/achieve/content-maps
POST /api/v1/achieve/content-maps
GET  /api/v1/report/preview-meta
GET  /api/v1/report/download
```

## 当前数据库结构

`init.sql` 仅保留当前代码路径实际使用的表：

```text
base_college
base_major
base_semester
base_course
sys_role
sys_user
sys_user_role
sys_login_session
course_teacher
base_class
base_student
class_course
outline_main
course_teaching_content
teach_objective
obj_decompose
assess_item
assess_content
obj_assess_map
obj_assess_content_map
parse_task
parse_objective_draft
parse_assess_item_draft
grade_import_batch
grade_import_preview
student_grade
calc_rule
achieve_result
achieve_result_detail
suggestion_rule
intelligent_suggestion
```

已移除的旧结构：

```text
student_eval
student_eval_dimension
teacher_reflection
supervisor_eval
improve_suggestion
improve_measure
```

这些结构没有当前 Controller 或前端页面入口，保留会导致文档和数据库结构与实际系统能力不一致。

## 构建与校验

后端：

```powershell
cd backend/coa
.\mvnw.cmd -q -DskipTests package
```

前端：

```powershell
cd frontend
npm run build
```
