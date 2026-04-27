SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `coa`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `coa`;

DROP TABLE IF EXISTS `improve_measure`;
DROP TABLE IF EXISTS `intelligent_suggestion`;
DROP TABLE IF EXISTS `suggestion_rule`;
DROP TABLE IF EXISTS `improve_suggestion`;
DROP TABLE IF EXISTS `achieve_result_detail`;
DROP TABLE IF EXISTS `achieve_result`;
DROP TABLE IF EXISTS `calc_rule`;
DROP TABLE IF EXISTS `supervisor_eval`;
DROP TABLE IF EXISTS `teacher_reflection`;
DROP TABLE IF EXISTS `student_eval_dimension`;
DROP TABLE IF EXISTS `student_eval`;
DROP TABLE IF EXISTS `student_grade`;
DROP TABLE IF EXISTS `grade_import_batch`;
DROP TABLE IF EXISTS `parse_assess_item_draft`;
DROP TABLE IF EXISTS `parse_objective_draft`;
DROP TABLE IF EXISTS `parse_task`;
DROP TABLE IF EXISTS `course_teaching_content`;
DROP TABLE IF EXISTS `obj_assess_map`;
DROP TABLE IF EXISTS `assess_item`;
DROP TABLE IF EXISTS `obj_decompose`;
DROP TABLE IF EXISTS `teach_objective`;
DROP TABLE IF EXISTS `outline_main`;
DROP TABLE IF EXISTS `course_teacher`;
DROP TABLE IF EXISTS `base_course`;
DROP TABLE IF EXISTS `base_semester`;
DROP TABLE IF EXISTS `base_major`;
DROP TABLE IF EXISTS `sys_login_session`;
DROP TABLE IF EXISTS `sys_user_role`;
DROP TABLE IF EXISTS `sys_user`;
DROP TABLE IF EXISTS `sys_role`;
DROP TABLE IF EXISTS `base_college`;

CREATE TABLE `base_college` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '学院ID',
  `college_name` VARCHAR(100) NOT NULL COMMENT '学院名称',
  `college_code` VARCHAR(20) NOT NULL COMMENT '学院编码',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_base_college_code` (`college_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学院表';

CREATE TABLE `sys_role` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_name` VARCHAR(50) NOT NULL COMMENT '角色名称',
  `role_code` VARCHAR(30) NOT NULL COMMENT '角色编码',
  `description` VARCHAR(200) DEFAULT NULL COMMENT '角色说明',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统角色表';

CREATE TABLE `base_major` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '专业ID',
  `college_id` BIGINT NOT NULL COMMENT '所属学院ID',
  `major_name` VARCHAR(100) NOT NULL COMMENT '专业名称',
  `major_code` VARCHAR(20) NOT NULL COMMENT '专业编码',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_base_major_code` (`major_code`),
  KEY `idx_base_major_college` (`college_id`),
  CONSTRAINT `fk_base_major_college` FOREIGN KEY (`college_id`) REFERENCES `base_college` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专业表';

CREATE TABLE `base_semester` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '学期ID',
  `semester_code` VARCHAR(20) NOT NULL COMMENT '学期编码',
  `semester_name` VARCHAR(50) NOT NULL COMMENT '学期名称',
  `school_year` VARCHAR(20) NOT NULL COMMENT '学年，如2024-2025',
  `term_no` TINYINT NOT NULL COMMENT '学期序号，1或2',
  `start_date` DATE DEFAULT NULL COMMENT '开始日期',
  `end_date` DATE DEFAULT NULL COMMENT '结束日期',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_base_semester_code` (`semester_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学期表';

CREATE TABLE `sys_user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` VARCHAR(50) NOT NULL COMMENT '登录用户名',
  `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
  `real_name` VARCHAR(50) NOT NULL COMMENT '真实姓名',
  `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
  `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
  `college_id` BIGINT DEFAULT NULL COMMENT '所属学院ID',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  `last_login_at` DATETIME DEFAULT NULL COMMENT '最后登录时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_user_username` (`username`),
  KEY `idx_sys_user_college` (`college_id`),
  CONSTRAINT `fk_sys_user_college` FOREIGN KEY (`college_id`) REFERENCES `base_college` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

CREATE TABLE `sys_user_role` (
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `role_id` BIGINT NOT NULL COMMENT '角色ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`user_id`, `role_id`),
  KEY `idx_sys_user_role_role` (`role_id`),
  CONSTRAINT `fk_sys_user_role_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_sys_user_role_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

CREATE TABLE `sys_login_session` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `access_token` VARCHAR(255) NOT NULL COMMENT '访问令牌',
  `refresh_token` VARCHAR(255) NOT NULL COMMENT '刷新令牌',
  `remember_me` TINYINT NOT NULL DEFAULT 0 COMMENT '是否记住登录',
  `expires_at` DATETIME NOT NULL COMMENT '访问令牌过期时间',
  `refresh_expires_at` DATETIME DEFAULT NULL COMMENT '刷新令牌过期时间',
  `revoked_at` DATETIME DEFAULT NULL COMMENT '注销时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_login_session_access` (`access_token`),
  UNIQUE KEY `uk_sys_login_session_refresh` (`refresh_token`),
  KEY `idx_sys_login_session_user` (`user_id`),
  CONSTRAINT `fk_sys_login_session_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录会话表';

CREATE TABLE `base_course` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '课程ID',
  `course_code` VARCHAR(30) NOT NULL COMMENT '课程代码',
  `course_name` VARCHAR(100) NOT NULL COMMENT '课程名称',
  `course_name_en` VARCHAR(255) DEFAULT NULL COMMENT '课程英文名称',
  `credits` DECIMAL(3,1) DEFAULT NULL COMMENT '学分',
  `hours` INT DEFAULT NULL COMMENT '学时',
  `course_type` VARCHAR(20) DEFAULT NULL COMMENT '课程类型：理论/实践/综合',
  `target_students` VARCHAR(255) DEFAULT NULL COMMENT '授课对象',
  `teaching_language` VARCHAR(100) DEFAULT NULL COMMENT '授课语言',
  `prerequisite_course` VARCHAR(255) DEFAULT NULL COMMENT '先修课程',
  `course_owner` VARCHAR(100) DEFAULT NULL COMMENT '课程负责人',
  `college_id` BIGINT NOT NULL COMMENT '开课学院ID',
  `major_id` BIGINT DEFAULT NULL COMMENT '所属专业ID',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_base_course_code` (`course_code`),
  KEY `idx_base_course_college` (`college_id`),
  KEY `idx_base_course_major` (`major_id`),
  CONSTRAINT `fk_base_course_college` FOREIGN KEY (`college_id`) REFERENCES `base_college` (`id`),
  CONSTRAINT `fk_base_course_major` FOREIGN KEY (`major_id`) REFERENCES `base_major` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程表';

CREATE TABLE `course_teacher` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '教师授课ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `teacher_id` BIGINT NOT NULL COMMENT '教师ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_teacher_scope` (`course_id`, `teacher_id`, `semester_id`),
  KEY `idx_course_teacher_teacher` (`teacher_id`),
  KEY `idx_course_teacher_semester` (`semester_id`),
  CONSTRAINT `fk_course_teacher_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_course_teacher_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_course_teacher_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师授课关系表';

CREATE TABLE `outline_main` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '课程大纲ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `teacher_id` BIGINT NOT NULL COMMENT '负责教师ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `version` VARCHAR(20) NOT NULL DEFAULT 'V1.0' COMMENT '版本号',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0草稿 1已发布',
  `overview` TEXT DEFAULT NULL COMMENT '课程概述',
  `target_source` TEXT DEFAULT NULL COMMENT '目标来源说明',
  `remark` TEXT DEFAULT NULL COMMENT '备注',
  `published_at` DATETIME DEFAULT NULL COMMENT '发布时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outline_scope` (`course_id`, `teacher_id`, `semester_id`, `version`),
  KEY `idx_outline_teacher` (`teacher_id`),
  KEY `idx_outline_semester` (`semester_id`),
  CONSTRAINT `fk_outline_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_outline_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `sys_user` (`id`),
  CONSTRAINT `fk_outline_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程大纲表';

CREATE TABLE `course_teaching_content` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '教学内容ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `title` VARCHAR(255) NOT NULL COMMENT '教学内容',
  `lecture_hours` DECIMAL(5,1) DEFAULT NULL COMMENT '讲授学时',
  `practice_hours` DECIMAL(5,1) DEFAULT NULL COMMENT '实践学时',
  `teaching_method` VARCHAR(100) DEFAULT NULL COMMENT '教学方式',
  `related_objectives` VARCHAR(255) DEFAULT NULL COMMENT '涉及课程目标',
  `requirements` TEXT DEFAULT NULL COMMENT '基本要求',
  `source_text` TEXT DEFAULT NULL COMMENT '来源原文',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_course_teaching_scope` (`course_id`, `semester_id`, `sort_order`),
  CONSTRAINT `fk_course_teaching_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_course_teaching_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程教学内容表';

CREATE TABLE `teach_objective` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '教学目标ID',
  `outline_id` BIGINT NOT NULL COMMENT '所属大纲ID',
  `obj_code` VARCHAR(20) NOT NULL COMMENT '目标编号，如OBJ-1',
  `obj_content` TEXT NOT NULL COMMENT '目标内容',
  `obj_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1知识 2能力 3素质',
  `weight` DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '目标权重',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
  `grad_req_id` VARCHAR(20) DEFAULT NULL COMMENT '支撑毕业要求编号，如3.1',
  `grad_req_desc` VARCHAR(500) DEFAULT NULL COMMENT '毕业要求简述',
  `relation_level` VARCHAR(4) NOT NULL DEFAULT 'H' COMMENT '关联程度H/M/L',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teach_objective_code` (`outline_id`, `obj_code`),
  KEY `idx_teach_objective_outline` (`outline_id`),
  CONSTRAINT `fk_teach_objective_outline` FOREIGN KEY (`outline_id`) REFERENCES `outline_main` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学目标表';

CREATE TABLE `obj_decompose` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '目标分解点ID',
  `objective_id` BIGINT NOT NULL COMMENT '教学目标ID',
  `point_code` VARCHAR(20) NOT NULL COMMENT '分解点编号',
  `point_content` TEXT NOT NULL COMMENT '分解点内容',
  `point_type` TINYINT NOT NULL DEFAULT 1 COMMENT '1知识点 2能力点 3素质点',
  `weight` DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '分解点权重',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_obj_decompose_code` (`objective_id`, `point_code`),
  KEY `idx_obj_decompose_objective` (`objective_id`),
  CONSTRAINT `fk_obj_decompose_objective` FOREIGN KEY (`objective_id`) REFERENCES `teach_objective` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='目标分解表';

CREATE TABLE `assess_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '考核项目ID',
  `outline_id` BIGINT NOT NULL COMMENT '所属大纲ID',
  `item_name` VARCHAR(50) NOT NULL COMMENT '考核项目名称',
  `item_type` VARCHAR(20) DEFAULT NULL COMMENT '平时/期中/期末/实验/实践',
  `weight` DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '考核权重',
  `max_score` DECIMAL(6,2) NOT NULL DEFAULT 100.00 COMMENT '满分值',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_assess_item_name` (`outline_id`, `item_name`),
  KEY `idx_assess_item_outline` (`outline_id`),
  CONSTRAINT `fk_assess_item_outline` FOREIGN KEY (`outline_id`) REFERENCES `outline_main` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='考核项目表';

CREATE TABLE `obj_assess_map` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '目标考核映射ID',
  `objective_id` BIGINT NOT NULL COMMENT '教学目标ID',
  `assess_item_id` BIGINT NOT NULL COMMENT '考核项目ID',
  `contribution_weight` DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '贡献权重',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_obj_assess_map_scope` (`objective_id`, `assess_item_id`),
  KEY `idx_obj_assess_map_assess_item` (`assess_item_id`),
  CONSTRAINT `fk_obj_assess_map_objective` FOREIGN KEY (`objective_id`) REFERENCES `teach_objective` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_obj_assess_map_assess_item` FOREIGN KEY (`assess_item_id`) REFERENCES `assess_item` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='目标考核映射表';

CREATE TABLE `parse_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '解析任务ID',
  `task_no` VARCHAR(40) NOT NULL COMMENT '任务编号',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `teacher_id` BIGINT NOT NULL COMMENT '教师ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `outline_id` BIGINT DEFAULT NULL COMMENT '目标写入的大纲ID',
  `source_file_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
  `source_file_path` VARCHAR(500) DEFAULT NULL COMMENT '文件存储路径',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PARSING' COMMENT 'PARSING/DONE/FAILED/CONFIRMED',
  `obj_extract_count` INT NOT NULL DEFAULT 0 COMMENT '提取目标数量',
  `assess_extract_count` INT NOT NULL DEFAULT 0 COMMENT '提取考核项数量',
  `overwrite_mode` TINYINT NOT NULL DEFAULT 0 COMMENT '0追加 1覆盖',
  `error_code` VARCHAR(20) DEFAULT NULL COMMENT '错误码',
  `error_message` VARCHAR(500) DEFAULT NULL COMMENT '错误信息',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `finished_at` DATETIME DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_parse_task_no` (`task_no`),
  KEY `idx_parse_task_scope` (`course_id`, `semester_id`, `teacher_id`),
  CONSTRAINT `fk_parse_task_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`),
  CONSTRAINT `fk_parse_task_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `sys_user` (`id`),
  CONSTRAINT `fk_parse_task_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`),
  CONSTRAINT `fk_parse_task_outline` FOREIGN KEY (`outline_id`) REFERENCES `outline_main` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能解析任务表';

CREATE TABLE `parse_objective_draft` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '目标草稿ID',
  `parse_task_id` BIGINT NOT NULL COMMENT '解析任务ID',
  `obj_code_suggest` VARCHAR(20) DEFAULT NULL COMMENT '建议目标编号',
  `obj_content_suggest` TEXT DEFAULT NULL COMMENT '建议目标内容',
  `obj_type_suggest` TINYINT DEFAULT NULL COMMENT '建议目标类型',
  `weight_suggest` DECIMAL(5,2) DEFAULT NULL COMMENT '建议权重',
  `grad_req_id_suggest` VARCHAR(20) DEFAULT NULL COMMENT '建议支撑毕业要求编号',
  `grad_req_desc_suggest` VARCHAR(500) DEFAULT NULL COMMENT '建议毕业要求简述',
  `relation_level_suggest` VARCHAR(4) DEFAULT NULL COMMENT '建议关联程度H/M/L',
  `obj_content_final` TEXT DEFAULT NULL COMMENT '教师确认内容',
  `obj_type_final` TINYINT DEFAULT NULL COMMENT '教师确认类型',
  `weight_final` DECIMAL(5,2) DEFAULT NULL COMMENT '教师确认权重',
  `grad_req_id_final` VARCHAR(20) DEFAULT NULL COMMENT '教师确认支撑毕业要求编号',
  `grad_req_desc_final` VARCHAR(500) DEFAULT NULL COMMENT '教师确认毕业要求简述',
  `relation_level_final` VARCHAR(4) DEFAULT NULL COMMENT '教师确认关联程度H/M/L',
  `confidence_score` DECIMAL(5,3) DEFAULT NULL COMMENT '置信度',
  `confidence_level` VARCHAR(10) DEFAULT NULL COMMENT 'HIGH/MEDIUM/LOW',
  `original_text` TEXT DEFAULT NULL COMMENT '原始文本',
  `is_confirmed` TINYINT NOT NULL DEFAULT 0 COMMENT '0未处理 1确认 2忽略',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_parse_objective_task` (`parse_task_id`),
  CONSTRAINT `fk_parse_objective_task` FOREIGN KEY (`parse_task_id`) REFERENCES `parse_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='解析目标草稿表';

CREATE TABLE `parse_assess_item_draft` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '考核项草稿ID',
  `parse_task_id` BIGINT NOT NULL COMMENT '解析任务ID',
  `item_name_suggest` VARCHAR(50) DEFAULT NULL COMMENT '建议考核项名称',
  `item_type_suggest` VARCHAR(20) DEFAULT NULL COMMENT '建议考核项类型',
  `weight_suggest` DECIMAL(5,2) DEFAULT NULL COMMENT '建议权重',
  `item_name_final` VARCHAR(50) DEFAULT NULL COMMENT '教师确认名称',
  `item_type_final` VARCHAR(20) DEFAULT NULL COMMENT '教师确认类型',
  `weight_final` DECIMAL(5,2) DEFAULT NULL COMMENT '教师确认权重',
  `confidence_score` DECIMAL(5,3) DEFAULT NULL COMMENT '置信度',
  `confidence_level` VARCHAR(10) DEFAULT NULL COMMENT 'HIGH/MEDIUM/LOW',
  `original_text` TEXT DEFAULT NULL COMMENT '原始文本',
  `is_confirmed` TINYINT NOT NULL DEFAULT 0 COMMENT '0未处理 1确认 2忽略',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_parse_assess_task` (`parse_task_id`),
  CONSTRAINT `fk_parse_assess_task` FOREIGN KEY (`parse_task_id`) REFERENCES `parse_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='解析考核项草稿表';

CREATE TABLE `grade_import_batch` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '导入批次ID',
  `batch_no` VARCHAR(40) NOT NULL COMMENT '批次编号',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `assess_item_id` BIGINT NOT NULL COMMENT '考核项ID',
  `teacher_id` BIGINT NOT NULL COMMENT '教师ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `source_file_name` VARCHAR(255) NOT NULL COMMENT '导入文件名',
  `source_file_path` VARCHAR(500) DEFAULT NULL COMMENT '文件存储路径',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PARSING' COMMENT 'PARSING/PARSED/CONFIRMED/FAILED',
  `total_rows` INT NOT NULL DEFAULT 0 COMMENT '总行数',
  `valid_rows` INT NOT NULL DEFAULT 0 COMMENT '有效行数',
  `error_rows` INT NOT NULL DEFAULT 0 COMMENT '异常行数',
  `import_mode` VARCHAR(20) DEFAULT 'valid_only' COMMENT 'valid_only/all',
  `confirmed_at` DATETIME DEFAULT NULL COMMENT '确认导入时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grade_import_batch_no` (`batch_no`),
  KEY `idx_grade_import_scope` (`course_id`, `assess_item_id`, `semester_id`),
  CONSTRAINT `fk_grade_import_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`),
  CONSTRAINT `fk_grade_import_assess_item` FOREIGN KEY (`assess_item_id`) REFERENCES `assess_item` (`id`),
  CONSTRAINT `fk_grade_import_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `sys_user` (`id`),
  CONSTRAINT `fk_grade_import_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成绩导入批次表';

CREATE TABLE `student_grade` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '成绩记录ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `assess_item_id` BIGINT NOT NULL COMMENT '考核项ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `import_batch_id` BIGINT DEFAULT NULL COMMENT '导入批次ID',
  `student_no` VARCHAR(20) NOT NULL COMMENT '学号',
  `student_name` VARCHAR(50) DEFAULT NULL COMMENT '学生姓名',
  `score` DECIMAL(6,2) DEFAULT NULL COMMENT '得分',
  `max_score` DECIMAL(6,2) NOT NULL DEFAULT 100.00 COMMENT '满分值',
  `valid_flag` TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 0无效',
  `error_message` VARCHAR(255) DEFAULT NULL COMMENT '异常说明',
  `created_by` BIGINT DEFAULT NULL COMMENT '导入人ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_student_grade_scope` (`course_id`, `assess_item_id`, `semester_id`),
  KEY `idx_student_grade_student` (`student_no`),
  KEY `idx_student_grade_batch` (`import_batch_id`),
  CONSTRAINT `fk_student_grade_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`),
  CONSTRAINT `fk_student_grade_assess_item` FOREIGN KEY (`assess_item_id`) REFERENCES `assess_item` (`id`),
  CONSTRAINT `fk_student_grade_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`),
  CONSTRAINT `fk_student_grade_batch` FOREIGN KEY (`import_batch_id`) REFERENCES `grade_import_batch` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_student_grade_creator` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生成绩表';

CREATE TABLE `student_eval` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '学生评价ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `student_no` VARCHAR(20) NOT NULL COMMENT '学号',
  `student_name` VARCHAR(50) DEFAULT NULL COMMENT '学生姓名',
  `content` TEXT DEFAULT NULL COMMENT '评价内容',
  `score` DECIMAL(4,1) DEFAULT NULL COMMENT '综合评价分',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_student_eval_scope` (`course_id`, `semester_id`),
  KEY `idx_student_eval_student` (`student_no`),
  CONSTRAINT `fk_student_eval_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`),
  CONSTRAINT `fk_student_eval_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生评价表';

CREATE TABLE `student_eval_dimension` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '学生评价维度ID',
  `eval_id` BIGINT NOT NULL COMMENT '学生评价ID',
  `dimension_key` VARCHAR(50) NOT NULL COMMENT '维度编码',
  `dimension_name` VARCHAR(50) NOT NULL COMMENT '维度名称',
  `dimension_score` DECIMAL(4,1) DEFAULT NULL COMMENT '维度得分',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_student_eval_dimension` (`eval_id`, `dimension_key`),
  CONSTRAINT `fk_student_eval_dimension_eval` FOREIGN KEY (`eval_id`) REFERENCES `student_eval` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生评价维度表';

CREATE TABLE `teacher_reflection` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '教学反思ID',
  `outline_id` BIGINT NOT NULL COMMENT '大纲ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `teacher_id` BIGINT NOT NULL COMMENT '教师ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `problem_summary` TEXT DEFAULT NULL COMMENT '问题概述',
  `reason_analysis` TEXT DEFAULT NULL COMMENT '原因分析',
  `improvement_plan` TEXT DEFAULT NULL COMMENT '改进计划',
  `next_action` TEXT DEFAULT NULL COMMENT '后续动作',
  `strengths` TEXT DEFAULT NULL COMMENT '教学优点',
  `weaknesses` TEXT DEFAULT NULL COMMENT '教学不足',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teacher_reflection_scope` (`outline_id`, `teacher_id`, `semester_id`),
  KEY `idx_teacher_reflection_course` (`course_id`),
  CONSTRAINT `fk_teacher_reflection_outline` FOREIGN KEY (`outline_id`) REFERENCES `outline_main` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_teacher_reflection_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`),
  CONSTRAINT `fk_teacher_reflection_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `sys_user` (`id`),
  CONSTRAINT `fk_teacher_reflection_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学反思表';

CREATE TABLE `supervisor_eval` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '督导评价ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `supervisor_id` BIGINT NOT NULL COMMENT '督导人ID',
  `score` DECIMAL(4,1) DEFAULT NULL COMMENT '督导评分',
  `content` TEXT DEFAULT NULL COMMENT '评价内容',
  `focus_items_json` JSON DEFAULT NULL COMMENT '关注点列表',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_supervisor_eval_scope` (`course_id`, `semester_id`),
  KEY `idx_supervisor_eval_supervisor` (`supervisor_id`),
  CONSTRAINT `fk_supervisor_eval_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`),
  CONSTRAINT `fk_supervisor_eval_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`),
  CONSTRAINT `fk_supervisor_eval_supervisor` FOREIGN KEY (`supervisor_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='督导评价表';

CREATE TABLE `calc_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '核算规则ID',
  `rule_name` VARCHAR(50) NOT NULL COMMENT '规则名称',
  `calc_method` VARCHAR(20) NOT NULL COMMENT 'weighted_avg/threshold',
  `threshold_value` DECIMAL(5,4) NOT NULL DEFAULT 0.7000 COMMENT '达成阈值',
  `pass_threshold` DECIMAL(5,4) NOT NULL DEFAULT 0.6000 COMMENT '及格阈值',
  `config_json` JSON DEFAULT NULL COMMENT '扩展配置',
  `is_default` TINYINT NOT NULL DEFAULT 0 COMMENT '1默认 0非默认',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='核算规则表';

CREATE TABLE `achieve_result` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '达成度结果ID',
  `result_batch_no` VARCHAR(40) NOT NULL COMMENT '同一次核算批次号',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `objective_id` BIGINT DEFAULT NULL COMMENT '目标ID，NULL表示课程整体',
  `calc_rule_id` BIGINT NOT NULL COMMENT '核算规则ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `normal_score` DECIMAL(6,4) DEFAULT NULL COMMENT '平时得分率',
  `mid_score` DECIMAL(6,4) DEFAULT NULL COMMENT '期中得分率',
  `final_score` DECIMAL(6,4) DEFAULT NULL COMMENT '期末得分率',
  `achieve_value` DECIMAL(6,4) NOT NULL COMMENT '达成度值',
  `is_achieved` TINYINT NOT NULL DEFAULT 0 COMMENT '1达成 0未达成',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1有效 0失效',
  `calc_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '核算时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_achieve_result_scope` (`course_id`, `semester_id`, `objective_id`),
  KEY `idx_achieve_result_batch` (`result_batch_no`),
  CONSTRAINT `fk_achieve_result_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`),
  CONSTRAINT `fk_achieve_result_objective` FOREIGN KEY (`objective_id`) REFERENCES `teach_objective` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_achieve_result_rule` FOREIGN KEY (`calc_rule_id`) REFERENCES `calc_rule` (`id`),
  CONSTRAINT `fk_achieve_result_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='达成度结果表';

CREATE TABLE `achieve_result_detail` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '达成度明细ID',
  `achieve_result_id` BIGINT NOT NULL COMMENT '达成度结果ID',
  `assess_item_id` BIGINT NOT NULL COMMENT '考核项ID',
  `score_rate` DECIMAL(6,4) DEFAULT NULL COMMENT '得分率',
  `contribution_weight` DECIMAL(5,2) DEFAULT NULL COMMENT '贡献权重',
  `achieve_value` DECIMAL(6,4) DEFAULT NULL COMMENT '该考核项贡献的达成值',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_achieve_result_detail` (`achieve_result_id`, `assess_item_id`),
  CONSTRAINT `fk_achieve_result_detail_result` FOREIGN KEY (`achieve_result_id`) REFERENCES `achieve_result` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_achieve_result_detail_item` FOREIGN KEY (`assess_item_id`) REFERENCES `assess_item` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='达成度结果明细表';

CREATE TABLE `improve_suggestion` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '建议模板ID',
  `achieve_range_min` DECIMAL(5,4) DEFAULT NULL COMMENT '适用达成度下限',
  `achieve_range_max` DECIMAL(5,4) DEFAULT NULL COMMENT '适用达成度上限',
  `suggestion_content` TEXT NOT NULL COMMENT '建议内容',
  `category` VARCHAR(30) DEFAULT NULL COMMENT '建议分类',
  `priority` TINYINT NOT NULL DEFAULT 2 COMMENT '1高 2中 3低',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='改进建议模板库';

CREATE TABLE `suggestion_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '建议规则ID',
  `rule_code` VARCHAR(20) NOT NULL COMMENT '规则编码，如R01',
  `rule_name` VARCHAR(100) NOT NULL COMMENT '规则名称',
  `rule_type` VARCHAR(30) NOT NULL COMMENT 'OBJECTIVE/ASSESSMENT/POSITIVE',
  `trigger_condition_json` JSON NOT NULL COMMENT '触发条件JSON',
  `suggestion_template` TEXT NOT NULL COMMENT '建议模板',
  `priority` TINYINT NOT NULL DEFAULT 2 COMMENT '优先级 1高2中3低',
  `is_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
  `description` VARCHAR(255) DEFAULT NULL COMMENT '规则说明',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_suggestion_rule_code` (`rule_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能建议规则表';

CREATE TABLE `intelligent_suggestion` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '智能建议实例ID',
  `receiver_user_id` BIGINT NOT NULL COMMENT '建议接收教师ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `objective_id` BIGINT DEFAULT NULL COMMENT '关联目标ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `rule_id` BIGINT DEFAULT NULL COMMENT '规则ID',
  `rule_code` VARCHAR(20) DEFAULT NULL COMMENT '规则编码',
  `suggestion_source` VARCHAR(30) NOT NULL DEFAULT 'RULE' COMMENT '建议来源：RULE/REPORT_GEN',
  `priority` TINYINT NOT NULL DEFAULT 2 COMMENT '优先级 1高2中3低',
  `title` VARCHAR(200) NOT NULL COMMENT '建议标题',
  `suggestion_text` TEXT NOT NULL COMMENT '建议正文',
  `data_basis_json` JSON DEFAULT NULL COMMENT '数据依据',
  `is_read` TINYINT NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
  `is_dismissed` TINYINT NOT NULL DEFAULT 0 COMMENT '0正常 1已忽略',
  `dismiss_reason` VARCHAR(200) DEFAULT NULL COMMENT '忽略原因',
  `read_at` DATETIME DEFAULT NULL COMMENT '已读时间',
  `dismissed_at` DATETIME DEFAULT NULL COMMENT '忽略时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_intelligent_suggestion_scope` (`receiver_user_id`, `course_id`, `semester_id`, `priority`),
  KEY `idx_intelligent_suggestion_rule` (`rule_id`),
  CONSTRAINT `fk_intelligent_suggestion_user` FOREIGN KEY (`receiver_user_id`) REFERENCES `sys_user` (`id`),
  CONSTRAINT `fk_intelligent_suggestion_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`),
  CONSTRAINT `fk_intelligent_suggestion_objective` FOREIGN KEY (`objective_id`) REFERENCES `teach_objective` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_intelligent_suggestion_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`),
  CONSTRAINT `fk_intelligent_suggestion_rule` FOREIGN KEY (`rule_id`) REFERENCES `suggestion_rule` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能建议实例表';

CREATE TABLE `improve_measure` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '改进措施ID',
  `suggestion_id` BIGINT DEFAULT NULL COMMENT '来源建议ID',
  `course_id` BIGINT NOT NULL COMMENT '课程ID',
  `objective_id` BIGINT DEFAULT NULL COMMENT '关联目标ID',
  `teacher_id` BIGINT NOT NULL COMMENT '填报教师ID',
  `semester_id` BIGINT NOT NULL COMMENT '学期ID',
  `problem_desc` TEXT NOT NULL COMMENT '问题描述',
  `measure_content` TEXT NOT NULL COMMENT '改进措施内容',
  `expected_effect` TEXT DEFAULT NULL COMMENT '预期效果',
  `actual_effect` TEXT DEFAULT NULL COMMENT '实际效果',
  `owner_name` VARCHAR(50) DEFAULT NULL COMMENT '责任人',
  `deadline` DATE DEFAULT NULL COMMENT '完成期限',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0计划中 1进行中 2已完成',
  `effect_summary` TEXT DEFAULT NULL COMMENT '效果总结',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_improve_measure_scope` (`course_id`, `semester_id`, `status`),
  KEY `idx_improve_measure_teacher` (`teacher_id`),
  KEY `idx_improve_measure_suggestion` (`suggestion_id`),
  CONSTRAINT `fk_improve_measure_suggestion` FOREIGN KEY (`suggestion_id`) REFERENCES `intelligent_suggestion` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_improve_measure_course` FOREIGN KEY (`course_id`) REFERENCES `base_course` (`id`),
  CONSTRAINT `fk_improve_measure_objective` FOREIGN KEY (`objective_id`) REFERENCES `teach_objective` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_improve_measure_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `sys_user` (`id`),
  CONSTRAINT `fk_improve_measure_semester` FOREIGN KEY (`semester_id`) REFERENCES `base_semester` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='改进措施表';

INSERT INTO `sys_role` (`id`, `role_name`, `role_code`, `description`) VALUES
  (1, '系统管理员', 'ADMIN', '负责系统配置、规则维护与全局数据管理'),
  (2, '教学管理人员', 'MANAGER', '负责学院级数据查看、规则触发与质量监控'),
  (3, '任课教师', 'TEACHER', '负责课程目标管理、数据采集与改进闭环'),
  (4, '教学督导', 'SUPERVISOR', '负责督导评价录入与查看');

INSERT INTO `base_college` (`id`, `college_name`, `college_code`) VALUES
  (1, '计算机科学与工程学院', 'CSE'),
  (2, '软件学院', 'SE');

INSERT INTO `base_major` (`id`, `college_id`, `major_name`, `major_code`) VALUES
  (1, 1, '计算机科学与技术', 'CS'),
  (2, 2, '软件工程', 'SE');

INSERT INTO `base_semester` (`id`, `semester_code`, `semester_name`, `school_year`, `term_no`, `status`) VALUES
  (1, '2024-2025-1', '2024-2025学年第一学期', '2024-2025', 1, 1),
  (2, '2024-2025-2', '2024-2025学年第二学期', '2024-2025', 2, 1),
  (3, '2025-2026-1', '2025-2026学年第一学期', '2025-2026', 1, 1);

INSERT INTO `sys_user` (`id`, `username`, `password_hash`, `real_name`, `email`, `phone`, `college_id`, `status`, `last_login_at`) VALUES
  (1001, 'admin.li', '$2b$10$zRshjBF/lJLkkx3K8Mwqj.tLMOv5YqhkwPd08YUAOI95eZkOTCgaa', '李明', 'admin.li@cqust.edu.cn', '13800001001', 1, 1, '2026-04-23 08:20:00'),
  (1101, 'manager.zhou', '$2b$10$zRshjBF/lJLkkx3K8Mwqj.tLMOv5YqhkwPd08YUAOI95eZkOTCgaa', '周海涛', 'manager.zhou@cqust.edu.cn', '13800001101', 1, 1, '2026-04-22 16:45:00'),
  (1201, 'wangbin', '$2b$10$zRshjBF/lJLkkx3K8Mwqj.tLMOv5YqhkwPd08YUAOI95eZkOTCgaa', '王斌', 'wangbin@cqust.edu.cn', '13800001201', 1, 1, '2026-04-23 09:12:00'),
  (1202, 'liuqing', '$2b$10$zRshjBF/lJLkkx3K8Mwqj.tLMOv5YqhkwPd08YUAOI95eZkOTCgaa', '刘庆', 'liuqing@cqust.edu.cn', '13800001202', 1, 1, '2026-04-23 08:55:00'),
  (1203, 'chenyu', '$2b$10$zRshjBF/lJLkkx3K8Mwqj.tLMOv5YqhkwPd08YUAOI95eZkOTCgaa', '陈宇', 'chenyu@cqust.edu.cn', '13800001203', 2, 1, '2026-04-21 14:18:00'),
  (1301, 'supervisor.zhao', '$2b$10$zRshjBF/lJLkkx3K8Mwqj.tLMOv5YqhkwPd08YUAOI95eZkOTCgaa', '赵敏', 'supervisor.zhao@cqust.edu.cn', '13800001301', 1, 1, '2026-04-20 10:30:00');

INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES
  (1001, 1),
  (1101, 2),
  (1201, 3),
  (1202, 3),
  (1203, 3),
  (1301, 4);

INSERT INTO `base_course` (`id`, `course_code`, `course_name`, `credits`, `hours`, `course_type`, `college_id`, `major_id`, `status`) VALUES
  (2001, 'CS21001', '数据结构', 4.0, 64, '理论', 1, 1, 1),
  (2002, 'CS31012', '数据库原理', 3.5, 56, '理论', 1, 1, 1),
  (2003, 'CS31008', '操作系统', 3.5, 56, '理论', 1, 1, 1),
  (2004, 'SE32005', '软件工程', 3.0, 48, '综合', 2, 2, 1),
  (2005, 'SE42016', '软件测试', 2.5, 40, '实践', 2, 2, 1);

INSERT INTO `course_teacher` (`id`, `course_id`, `teacher_id`, `semester_id`, `status`) VALUES
  (3001, 2001, 1201, 1, 1),
  (3002, 2002, 1201, 1, 1),
  (3003, 2003, 1202, 1, 1),
  (3004, 2004, 1203, 1, 1),
  (3005, 2005, 1203, 2, 1),
  (3006, 2001, 1201, 2, 1),
  (3007, 2002, 1202, 2, 1),
  (3008, 2004, 1203, 3, 1);

INSERT INTO `calc_rule` (`id`, `rule_name`, `calc_method`, `threshold_value`, `pass_threshold`, `config_json`, `is_default`, `status`) VALUES
  (1, '默认加权平均核算规则', 'weighted_avg', 0.7000, 0.6000, JSON_OBJECT('description', '按考核项贡献权重汇总教学目标达成度'), 1, 1);

INSERT INTO `suggestion_rule` (`id`, `rule_code`, `rule_name`, `rule_type`, `trigger_condition_json`, `suggestion_template`, `priority`, `is_enabled`, `sort_order`, `description`) VALUES
  (1, 'R01', '目标未达成', 'OBJECTIVE', JSON_OBJECT('metric', 'objective_achieve_value', 'operator', 'lt', 'valueSource', 'calc_rule.threshold_value'), '目标达成度低于阈值，建议针对薄弱知识点增加课堂练习与课后巩固。', 1, 1, 1, '目标达成度低于阈值时触发'),
  (2, 'R02', '期末成绩明显下滑', 'ASSESSMENT', JSON_OBJECT('metric', 'final_exam_avg', 'operator', 'lt_hist_avg_by', 'value', 10), '期末成绩相较历史平均下降明显，建议复核试题难度与复习支持方案。', 1, 1, 2, '期末成绩相对历史平均下滑时触发'),
  (3, 'R03', '不及格率偏高', 'ASSESSMENT', JSON_OBJECT('metric', 'fail_rate', 'operator', 'gt', 'value', 0.35), '不及格率偏高，建议诊断薄弱环节并安排针对性辅导。', 2, 1, 3, '不及格率超阈值时触发'),
  (4, 'R07', '教学效果良好', 'POSITIVE', JSON_OBJECT('metric', 'overall_achievement', 'operator', 'gte', 'value', 0.85), '课程整体达成良好，建议总结优秀做法并在同类课程中推广。', 3, 1, 4, '课程整体达成度较高时触发');

INSERT INTO `improve_suggestion` (`id`, `achieve_range_min`, `achieve_range_max`, `suggestion_content`, `category`, `priority`, `status`) VALUES
  (1, 0.0000, 0.6999, '建议围绕未达成目标增加专题讲练、案例讲解和分层作业。', '目标改进', 1, 1),
  (2, 0.7000, 0.8499, '建议保持当前教学策略，并针对边缘目标进行小范围优化。', '持续优化', 2, 1),
  (3, 0.8500, 1.0000, '建议总结优秀教学经验并进行课程组共享。', '经验推广', 3, 1);

SET FOREIGN_KEY_CHECKS = 1;
