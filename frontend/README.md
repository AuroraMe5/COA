# COA 前端

本目录是 COA 教学目标达成系统的 Vue 3 前端，提供教师工作台、课程管理、数据采集、达成度核算、报告预览导出，以及超级管理员用户管理页面。

## 技术栈

- Vue 3
- Vue Router
- Pinia
- Axios
- ECharts
- Vue CLI

## 本地运行

```powershell
npm install
npm run serve
```

默认访问：

```text
http://localhost:8080
```

开发环境接口统一使用 `/api/v1`，通过 `vue.config.js` 代理到后端 `http://localhost:8081`。

## 构建

```powershell
npm run build
```

## 关键目录

```text
src/api/index.js                    接口封装
src/router/index.js                 路由与权限守卫
src/stores/auth.js                  登录会话与角色状态
src/components/layout/              登录后主布局
src/components/common/              通用面板、状态、弹窗组件
src/utils/feedback.js               全局反馈弹窗
src/views/admin/UserManageView.vue  超级管理员用户管理
src/views/objectives/               课程、目标、大纲、智能解析
src/views/collect/                  班级、学生、成绩管理
src/views/analysis/                 达成度核算、报告预览导出
```

## 当前路由

```text
/login
/dashboard
/objectives/outlines
/objectives/list
/objectives/edit/:id?
/objectives/weights
/objectives/parse-import
/objectives/mapping
/collect/classes
/collect/grades/manage
/analysis/calculation
/analysis/report
/admin/users
```

`/admin/users` 仅 `ADMIN` 角色可访问。普通教师不会看到该菜单，直接访问也会被重定向到首页。

## 主要页面能力

- 课程管理支持直接新增课程、删除当前教师创建并在所选学期负责的课程，并继续在当前页面维护课程详情。
- 智能解析导入仍用于从大纲文件生成课程、目标、考核项、教学内容和映射草稿。

## 默认账号

```text
超级管理员：admin / admin123
教师账号：wangbin / 123456
教师账号：liuqing / 123456
教师账号：chenyu / 123456
```

## 交互约定

- 所有接口集中在 `src/api/index.js`。
- Axios 会自动携带 Bearer Token。
- 401 会清理本地会话并跳转登录页。
- 全局操作反馈统一使用 `showFeedback` 和 `confirmFeedback`。
- 达成度核算默认达成阈值为 `0.6`，良好阈值为 `0.7`。
- 报告预览与 Word 导出使用同一份后端报告数据。
