# 招聘网站爬虫设计文档

## 概述

在现有 Spring Boot 项目中新增独立爬虫模块，从前程无忧（51job）和智联招聘抓取物联网/智能制造/工业自动化相关岗位信息，存入 MySQL 数据库。

## 运行方式

独立脚本，通过 `main` 方法直接运行，不依赖 Spring Boot 容器启动。可通过 IDE 直接运行或 `java -cp` 命令行执行。

## 数据源与范围

- **来源**: 前程无忧（51job.com）、智联招聘（zhaopin.com）
- **城市**: 上海市、无锡市、苏州市
- **职位类型**: 物联网、智能制造、工业自动化
- **抓取方式**: Hutool HttpUtil + Jsoup 解析 HTML

## 包结构

```
com.wxut.agentplaza.crawler/
├── JobCrawlerMain.java          — 入口 main 方法
├── model/
│   └── JobInfo.java              — 职位数据 POJO
├── parser/
│   ├── JobParser.java            — 解析接口
│   ├── QianchengParser.java      — 前程无忧解析器
│   └── ZhilianParser.java        — 智联招聘解析器
├── crawler/
│   ├── BaseCrawler.java          — 爬虫基类（HTTP请求、限速、UA伪装）
│   ├── QianchengCrawler.java     — 前程无忧爬虫
│   └── ZhilianCrawler.java       — 智联招聘爬虫
└── store/
    └── MysqlStore.java           — MySQL JDBC 直连存储
```

## 数据库表设计

使用已有表 `wxit_job`，DDL 见 `difydoc/wxit_job.sql`：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(20) PK | ID |
| name | VARCHAR(64) | 职位名称 |
| keyword | VARCHAR(16) | 职位类型：物联网、智能制造、工业自动化 |
| experience | VARCHAR(64) | 职位经验 |
| education | VARCHAR(64) | 职位学历 |
| description | TEXT | 职位描述 |
| salary | VARCHAR(64) | 职位薪资 |
| tags | TEXT | 职位标签 |
| job_href | VARCHAR(256) | 职位链接 |
| company | VARCHAR(128) | 公司名称 |
| company_type | VARCHAR(16) | 公司性质 |
| company_size | VARCHAR(31) | 公司规模 |
| hr_name | VARCHAR(64) | HR名称 |
| address | TINYTEXT | 公司地址 |
| company_href | VARCHAR(256) | 公司链接 |
| eid | VARCHAR(64) UNIQUE | 外部ID（去重依据） |
| raw | JSON | 原始数据 |
| city | VARCHAR(64) | 所在城市：上海市、无锡市、苏州市 |
| job_source | VARCHAR(16) | 数据来源：前程无忧、智联招聘 |

**唯一索引**: `uni_eid` (`eid`) — 用于去重

## 关键实现细节

### HTTP 请求
- 使用项目已有的 Hutool `HttpUtil`
- 随机 User-Agent 轮换
- 请求间隔 2-5 秒随机延迟
- 失败重试 3 次

### 数据库连接
- JDBC 直连，读取项目 `application-dev.yml` 中的数据库配置
- 使用 INSERT ... ON DUPLICATE KEY UPDATE 实现去重写入

### 新增依赖
仅需 Jsoup 一个新依赖：
```xml
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.15.4</version>
</dependency>
```

### 去重策略
基于 `eid` 唯一索引，使用 INSERT ... ON DUPLICATE KEY UPDATE 实现去重写入。

## 已知风险

前程无忧和智联招聘反爬机制较强，简单 HTTP+Jsoup 可能遇到：
- 搜索列表页动态加载，需分析 Ajax 接口
- IP 封禁和验证码
- 页面结构变更

实际抓取效果需运行调试，可能要根据网站实际情况调整解析逻辑。
