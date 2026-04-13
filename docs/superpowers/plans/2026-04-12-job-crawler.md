# 招聘网站爬虫 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建独立爬虫脚本，从智联招聘和前程无忧抓取物联网相关岗位信息存入 MySQL `wxit_job` 表。

**Architecture:** 独立 Java main 方法运行，Hutool HttpUtil 发请求，Jsoup 解析 HTML，JDBC 直连 MySQL 存储。智联招聘可 SSR 直接抓取；前程无忧有反爬限制需注意。

**Tech Stack:** Java 8, Hutool 5.8.26, Jsoup 1.15.4, MySQL Connector 8.0.33, Lombok

---

## File Structure

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `pom.xml` | 添加 Jsoup 依赖 |
| Create | `src/main/java/com/wxut/agentplaza/crawler/model/JobInfo.java` | 职位数据 POJO |
| Create | `src/main/java/com/wxut/agentplaza/crawler/store/MysqlStore.java` | MySQL JDBC 存储 |
| Create | `src/main/java/com/wxut/agentplaza/crawler/crawler/BaseCrawler.java` | HTTP 工具基类 |
| Create | `src/main/java/com/wxut/agentplaza/crawler/parser/ZhaopinParser.java` | 智联招聘 HTML 解析 |
| Create | `src/main/java/com/wxut/agentplaza/crawler/crawler/ZhaopinCrawler.java` | 智联招聘爬虫 |
| Create | `src/main/java/com/wxut/agentplaza/crawler/parser/QianchengParser.java` | 前程无忧 HTML 解析 |
| Create | `src/main/java/com/wxut/agentplaza/crawler/crawler/QianchengCrawler.java` | 前程无忧爬虫 |
| Create | `src/main/java/com/wxut/agentplaza/crawler/JobCrawlerMain.java` | 入口 main 方法 |

---

### Task 1: 添加 Jsoup 依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 在 pom.xml 中添加 Jsoup 依赖**

在 `hutool-all` 依赖之后添加：

```xml
        <dependency>
            <groupId>org.jsoup</groupId>
            <artifactId>jsoup</artifactId>
            <version>1.15.4</version>
        </dependency>
```

- [ ] **Step 2: 验证依赖下载**

Run: `mvn dependency:resolve -q -f pom.xml 2>&1 | tail -5`
Expected: BUILD SUCCESS

---

### Task 2: 创建 JobInfo 模型

**Files:**
- Create: `src/main/java/com/wxut/agentplaza/crawler/model/JobInfo.java`

- [ ] **Step 1: 创建 JobInfo.java**

```java
package com.wxut.agentplaza.crawler.model;

import lombok.Data;

@Data
public class JobInfo {
    private String id;
    private String name;
    private String keyword;
    private String experience;
    private String education;
    private String description;
    private String salary;
    private String tags;
    private String jobHref;
    private String company;
    private String companyType;
    private String companySize;
    private String hrName;
    private String address;
    private String companyHref;
    private String eid;
    private String raw;
    private String city;
    private String jobSource;
}
```

---

### Task 3: 创建 MysqlStore 存储层

**Files:**
- Create: `src/main/java/com/wxut/agentplaza/crawler/store/MysqlStore.java`

- [ ] **Step 1: 创建 MysqlStore.java**

```java
package com.wxut.agentplaza.crawler.store;

import cn.hutool.core.util.IdUtil;
import com.wxut.agentplaza.crawler.model.JobInfo;

import java.sql.*;
import java.util.List;

public class MysqlStore {

    private static final String URL = "jdbc:mysql://localhost:3306/agent_plaza?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String USER = "root";
    private static final String PASS = "root";

    private static final String INSERT_SQL =
            "INSERT INTO wxit_job (id, name, keyword, experience, education, description, salary, tags, " +
            "job_href, company, company_type, company_size, hr_name, address, company_href, eid, raw, city, job_source) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE name=VALUES(name), salary=VALUES(salary), description=VALUES(description), " +
            "experience=VALUES(experience), education=VALUES(education), tags=VALUES(tags), raw=VALUES(raw)";

    public MysqlStore() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("MySQL Driver not found", e);
        }
    }

    public void saveBatch(List<JobInfo> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (JobInfo job : jobs) {
                String id = job.getId() != null ? job.getId() : IdUtil.nanoId(20);
                ps.setString(1, id);
                ps.setString(2, job.getName());
                ps.setString(3, job.getKeyword());
                ps.setString(4, job.getExperience());
                ps.setString(5, job.getEducation());
                ps.setString(6, job.getDescription());
                ps.setString(7, job.getSalary());
                ps.setString(8, job.getTags());
                ps.setString(9, job.getJobHref());
                ps.setString(10, job.getCompany());
                ps.setString(11, job.getCompanyType());
                ps.setString(12, job.getCompanySize());
                ps.setString(13, job.getHrName());
                ps.setString(14, job.getAddress());
                ps.setString(15, job.getCompanyHref());
                ps.setString(16, job.getEid());
                ps.setString(17, job.getRaw());
                ps.setString(18, job.getCity());
                ps.setString(19, job.getJobSource());
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            System.out.println("[MysqlStore] Saved " + results.length + " jobs");
        } catch (SQLException e) {
            System.err.println("[MysqlStore] Error saving batch: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
```

---

### Task 4: 创建 BaseCrawler HTTP 工具基类

**Files:**
- Create: `src/main/java/com/wxut/agentplaza/crawler/crawler/BaseCrawler.java`

- [ ] **Step 1: 创建 BaseCrawler.java**

```java
package com.wxut.agentplaza.crawler.crawler;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public abstract class BaseCrawler {

    private static final List<String> USER_AGENTS = Arrays.asList(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15"
    );

    private static final Random RANDOM = new Random();
    private static final int RETRY_COUNT = 3;

    protected String fetchPage(String url) {
        return fetchPage(url, null);
    }

    protected String fetchPage(String url, String referer) {
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                HttpRequest request = HttpRequest.get(url)
                        .header("User-Agent", randomUA())
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .timeout(15000);
                if (referer != null) {
                    request.header("Referer", referer);
                }
                HttpResponse response = request.execute();
                if (response.isOk()) {
                    return response.body();
                }
                System.err.println("[BaseCrawler] HTTP " + response.getStatus() + " for " + url);
            } catch (Exception e) {
                System.err.println("[BaseCrawler] Attempt " + (i + 1) + " failed for " + url + ": " + e.getMessage());
            }
            sleep(3000);
        }
        return null;
    }

    protected void sleep(long millis) {
        try {
            long delay = millis + RANDOM.nextInt((int) (millis / 2));
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected void sleepBetweenPages() {
        sleep(3000);
    }

    private String randomUA() {
        return USER_AGENTS.get(RANDOM.nextInt(USER_AGENTS.size()));
    }

    public abstract String getSourceName();
}
```

---

### Task 5: 创建智联招聘解析器和爬虫

**Files:**
- Create: `src/main/java/com/wxut/agentplaza/crawler/parser/ZhaopinParser.java`
- Create: `src/main/java/com/wxut/agentplaza/crawler/crawler/ZhaopinCrawler.java`

- [ ] **Step 1: 创建 ZhaopinParser.java**

智联招聘搜索列表页是 SSR HTML，每个职位在 `div.joblist-box__item` 中。关键选择器：

- 职位名称 + 链接: `a.jobinfo__name`
- 薪资: `p.jobinfo__salary`
- 职位标签: `div.jobinfo__tag > div.joblist-box__item-tag`
- 经验/学历: `div.jobinfo__other-info-item` (第2、3个)
- 公司名+链接: `a.companyinfo__name`
- 公司标签(性质/规模): `div.companyinfo__tag > div.joblist-box__item-tag`
- HR名称: `div.companyinfo__staff-name`

```java
package com.wxut.agentplaza.crawler.parser;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.wxut.agentplaza.crawler.model.JobInfo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ZhaopinParser {

    public List<JobInfo> parseListPage(String html, String keyword, String city) {
        List<JobInfo> jobs = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return jobs;
        }

        Document doc = Jsoup.parse(html);
        Elements items = doc.select("div.joblist-box__item");

        for (Element item : items) {
            try {
                JobInfo job = parseItem(item, keyword, city);
                if (job != null && job.getName() != null) {
                    jobs.add(job);
                }
            } catch (Exception e) {
                System.err.println("[ZhaopinParser] Error parsing item: " + e.getMessage());
            }
        }
        return jobs;
    }

    private JobInfo parseItem(Element item, String keyword, String city) {
        JobInfo job = new JobInfo();

        // 职位名称和链接
        Element nameEl = item.selectFirst("a.jobinfo__name");
        if (nameEl != null) {
            job.setName(nameEl.text().trim());
            job.setJobHref(nameEl.absUrl("href"));
        }

        // 薪资
        Element salaryEl = item.selectFirst("p.jobinfo__salary");
        if (salaryEl != null) {
            job.setSalary(salaryEl.text().trim().replaceAll("\\s+", ""));
        }

        // 职位标签
        Elements tagEls = item.select("div.jobinfo__tag > div.joblist-box__item-tag");
        StringBuilder tags = new StringBuilder();
        for (Element tagEl : tagEls) {
            if (tags.length() > 0) tags.append(",");
            tags.append(tagEl.text().trim());
        }
        job.setTags(tags.toString());

        // 经验和学历 (other-info-item: 第1个是地点, 第2个是经验, 第3个是学历)
        Elements infoItems = item.select("div.jobinfo__other-info-item");
        if (infoItems.size() >= 2) {
            job.setExperience(infoItems.get(1).text().trim());
        }
        if (infoItems.size() >= 3) {
            job.setEducation(infoItems.get(2).text().trim());
        }

        // 地点 (从 location span 获取更精确的地址)
        Element locEl = item.selectFirst("span.jobinfo__other-info-location");
        if (locEl == null && infoItems.size() >= 1) {
            locEl = infoItems.get(0);
        }
        if (locEl != null) {
            job.setAddress(locEl.text().trim());
        }

        // 公司名和链接
        Element companyEl = item.selectFirst("a.companyinfo__name");
        if (companyEl != null) {
            job.setCompany(companyEl.text().trim());
            job.setCompanyHref(companyEl.absUrl("href"));
        }

        // 公司标签 (性质、规模、行业)
        Elements companyTagEls = item.select("div.companyinfo__tag > div.joblist-box__item-tag");
        if (companyTagEls.size() >= 1) {
            job.setCompanyType(companyTagEls.get(0).text().trim());
        }
        if (companyTagEls.size() >= 2) {
            job.setCompanySize(companyTagEls.get(1).text().trim());
        }

        // HR名称
        Element hrEl = item.selectFirst("div.companyinfo__staff-name");
        if (hrEl != null) {
            job.setHrName(hrEl.text().trim());
        }

        // 外部ID: 从职位链接中提取
        String jobHref = job.getJobHref();
        if (jobHref != null && !jobHref.isEmpty()) {
            String eid = jobHref.substring(jobHref.lastIndexOf('/') + 1);
            if (eid.endsWith(".htm")) {
                eid = eid.substring(0, eid.length() - 4);
            }
            job.setEid("zhaopin_" + eid);
        }

        // 设置固定字段
        job.setId(IdUtil.nanoId(20));
        job.setKeyword(keyword);
        job.setCity(city);
        job.setJobSource("智联招聘");

        // 原始数据
        Map<String, String> rawMap = new HashMap<>();
        rawMap.put("html", item.html());
        job.setRaw(JSONUtil.toJsonStr(rawMap));

        return job;
    }
}
```

- [ ] **Step 2: 创建 ZhaopinCrawler.java**

城市代码映射：上海=538, 无锡=639, 苏州=636。

URL 格式：`https://sou.zhaopin.com/?jl={cityCode}&kw={keyword}&p={page}`

```java
package com.wxut.agentplaza.crawler.crawler;

import cn.hutool.core.io.URLUtil;
import com.wxut.agentplaza.crawler.model.JobInfo;
import com.wxut.agentplaza.crawler.parser.ZhaopinParser;
import com.wxut.agentplaza.crawler.store.MysqlStore;

import java.net.URLEncoder;
import java.util.*;

public class ZhaopinCrawler extends BaseCrawler {

    private static final Map<String, String> CITY_CODE_MAP = new LinkedHashMap<>();

    static {
        CITY_CODE_MAP.put("上海市", "538");
        CITY_CODE_MAP.put("无锡市", "639");
        CITY_CODE_MAP.put("苏州市", "636");
    }

    private static final List<String> KEYWORDS = Arrays.asList(
            "物联网", "智能制造", "工业自动化"
    );

    private static final int MAX_PAGES = 5;

    private final ZhaopinParser parser = new ZhaopinParser();
    private final MysqlStore store;

    public ZhaopinCrawler(MysqlStore store) {
        this.store = store;
    }

    @Override
    public String getSourceName() {
        return "智联招聘";
    }

    public void run() {
        System.out.println("========== 智联招聘 爬虫启动 ==========");

        for (String keyword : KEYWORDS) {
            for (Map.Entry<String, String> entry : CITY_CODE_MAP.entrySet()) {
                String city = entry.getKey();
                String cityCode = entry.getValue();
                System.out.println("\n--- 搜索: " + keyword + " @ " + city + " ---");
                crawlKeywordCity(keyword, city, cityCode);
            }
        }

        System.out.println("========== 智联招聘 爬虫结束 ==========\n");
    }

    private void crawlKeywordCity(String keyword, String city, String cityCode) {
        for (int page = 1; page <= MAX_PAGES; page++) {
            try {
                String encodedKw = URLEncoder.encode(keyword, "UTF-8");
                String url = "https://sou.zhaopin.com/?jl=" + cityCode + "&kw=" + encodedKw + "&p=" + page;
                System.out.println("[ZhaopinCrawler] Fetching page " + page + ": " + url);

                String html = fetchPage(url);
                if (html == null || html.isEmpty()) {
                    System.err.println("[ZhaopinCrawler] Empty response, stopping pagination");
                    break;
                }

                List<JobInfo> jobs = parser.parseListPage(html, keyword, city);
                if (jobs.isEmpty()) {
                    System.out.println("[ZhaopinCrawler] No jobs found on page " + page + ", stopping");
                    break;
                }

                System.out.println("[ZhaopinCrawler] Parsed " + jobs.size() + " jobs from page " + page);
                store.saveBatch(jobs);

                if (page < MAX_PAGES) {
                    sleepBetweenPages();
                }
            } catch (Exception e) {
                System.err.println("[ZhaopinCrawler] Error on page " + page + ": " + e.getMessage());
                break;
            }
        }
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -q -f d:/WorkMints/vscode/wxut-agent-pg-api/pom.xml 2>&1 | tail -5`
Expected: BUILD SUCCESS

---

### Task 6: 创建前程无忧解析器和爬虫

**Files:**
- Create: `src/main/java/com/wxut/agentplaza/crawler/parser/QianchengParser.java`
- Create: `src/main/java/com/wxut/agentplaza/crawler/crawler/QianchengCrawler.java`

> **注意:** 前程无忧 `search.51job.com` 对简单 HTTP 请求返回空响应（TLS 指纹/JS 挑战），以下代码基于已知的页面结构编写，实际运行时可能无法获取数据。如需抓取前程无忧，后续可改用 Playwright 等无头浏览器方案。

- [ ] **Step 1: 创建 QianchengParser.java**

前程无忧搜索列表的 DOM 结构：每个职位在 `div.el` 或 `div.joblist` 的 `div.e` 中（经典版），或在新版中为 `div.joblist-item`。以下基于新版 search.51job.com 的常见结构：

```java
package com.wxut.agentplaza.crawler.parser;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.wxut.agentplaza.crawler.model.JobInfo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QianchengParser {

    public List<JobInfo> parseListPage(String html, String keyword, String city) {
        List<JobInfo> jobs = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return jobs;
        }

        Document doc = Jsoup.parse(html);

        // 新版 51job 搜索页选择器
        Elements items = doc.select("div.joblist-item");
        if (items.isEmpty()) {
            // 旧版选择器
            items = doc.select("div.el > div.e");
        }
        if (items.isEmpty()) {
            // 更通用的选择器尝试
            items = doc.select("div.j_joblist > div.e");
        }

        for (Element item : items) {
            try {
                JobInfo job = parseItem(item, keyword, city);
                if (job != null && job.getName() != null) {
                    jobs.add(job);
                }
            } catch (Exception e) {
                System.err.println("[QianchengParser] Error parsing item: " + e.getMessage());
            }
        }
        return jobs;
    }

    private JobInfo parseItem(Element item, String keyword, String city) {
        JobInfo job = new JobInfo();

        // 职位名称和链接
        Element nameEl = item.selectFirst("a.el-title, span.title a, a.t1");
        if (nameEl == null) {
            nameEl = item.selectFirst("p.t a, span.jname a");
        }
        if (nameEl != null) {
            job.setName(nameEl.text().trim());
            job.setJobHref(nameEl.absUrl("href"));
        }

        // 薪资
        Element salaryEl = item.selectFirst("span.sal, p.salary, span.el-sal");
        if (salaryEl == null) {
            salaryEl = item.selectFirst("span.in, em.sal");
        }
        if (salaryEl != null) {
            job.setSalary(salaryEl.text().trim());
        }

        // 经验和学历
        Elements attrEls = item.select("span.el-label, span.dAttr, p.info span");
        for (Element attr : attrEls) {
            String text = attr.text().trim();
            if (text.contains("经验") || text.matches(".*\\d+-?\\d*年.*")) {
                job.setExperience(text);
            } else if (text.contains("硕") || text.contains("本") || text.contains("专") ||
                       text.contains("大专") || text.contains("本科") || text.contains("硕士") || text.contains("博士")) {
                job.setEducation(text);
            }
        }

        // 公司名和链接
        Element companyEl = item.selectFirst("a.el-company, span.company a, a.clnames");
        if (companyEl == null) {
            companyEl = item.selectFirst("p.company a, span.cname a");
        }
        if (companyEl != null) {
            job.setCompany(companyEl.text().trim());
            job.setCompanyHref(companyEl.absUrl("href"));
        }

        // 公司性质和规模
        Elements companyInfoEls = item.select("span.el-prop, span.dCparam, p.company span");
        for (Element ci : companyInfoEls) {
            String text = ci.text().trim();
            if (text.contains("民营") || text.contains("国企") || text.contains("外资") ||
                text.contains("合资") || text.contains("上市") || text.contains("股份") ||
                text.contains("私营")) {
                job.setCompanyType(text);
            }
            if (text.matches(".*\\d+.*人.*")) {
                job.setCompanySize(text);
            }
        }

        // 地点
        Element locEl = item.selectFirst("span.el-area, span.dArea, p.info span:first-child");
        if (locEl != null) {
            job.setAddress(locEl.text().trim());
        }

        // 标签
        Elements tagEls = item.select("span.el-tag, span.tTag, div.tags span");
        StringBuilder tags = new StringBuilder();
        for (Element tag : tagEls) {
            if (tags.length() > 0) tags.append(",");
            tags.append(tag.text().trim());
        }
        job.setTags(tags.toString());

        // 外部ID: 从职位链接中提取
        String jobHref = job.getJobHref();
        if (jobHref != null && !jobHref.isEmpty()) {
            String path = jobHref;
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0) {
                path = path.substring(lastSlash + 1);
            }
            if (path.endsWith(".html")) {
                path = path.substring(0, path.length() - 5);
            }
            job.setEid("51job_" + path);
        }

        // 固定字段
        job.setId(IdUtil.nanoId(20));
        job.setKeyword(keyword);
        job.setCity(city);
        job.setJobSource("前程无忧");

        // 原始数据
        Map<String, String> rawMap = new HashMap<>();
        rawMap.put("html", item.html());
        job.setRaw(JSONUtil.toJsonStr(rawMap));

        return job;
    }
}
```

- [ ] **Step 2: 创建 QianchengCrawler.java**

城市代码映射：上海=020000, 无锡=070300, 苏州=070200。

URL 格式：`https://search.51job.com/list/{cityCode},000000,0000,00,9,99,{keyword},2,{page}.html`

```java
package com.wxut.agentplaza.crawler.crawler;

import com.wxut.agentplaza.crawler.model.JobInfo;
import com.wxut.agentplaza.crawler.parser.QianchengParser;
import com.wxut.agentplaza.crawler.store.MysqlStore;

import java.net.URLEncoder;
import java.util.*;

public class QianchengCrawler extends BaseCrawler {

    private static final Map<String, String> CITY_CODE_MAP = new LinkedHashMap<>();

    static {
        CITY_CODE_MAP.put("上海市", "020000");
        CITY_CODE_MAP.put("无锡市", "070300");
        CITY_CODE_MAP.put("苏州市", "070200");
    }

    private static final List<String> KEYWORDS = Arrays.asList(
            "物联网", "智能制造", "工业自动化"
    );

    private static final int MAX_PAGES = 5;

    private final QianchengParser parser = new QianchengParser();
    private final MysqlStore store;

    public QianchengCrawler(MysqlStore store) {
        this.store = store;
    }

    @Override
    public String getSourceName() {
        return "前程无忧";
    }

    public void run() {
        System.out.println("========== 前程无忧 爬虫启动 ==========");
        System.out.println("注意: 前程无忧有较强反爬机制, 简单HTTP请求可能返回空数据");

        for (String keyword : KEYWORDS) {
            for (Map.Entry<String, String> entry : CITY_CODE_MAP.entrySet()) {
                String city = entry.getKey();
                String cityCode = entry.getValue();
                System.out.println("\n--- 搜索: " + keyword + " @ " + city + " ---");
                crawlKeywordCity(keyword, city, cityCode);
            }
        }

        System.out.println("========== 前程无忧 爬虫结束 ==========\n");
    }

    private void crawlKeywordCity(String keyword, String city, String cityCode) {
        for (int page = 1; page <= MAX_PAGES; page++) {
            try {
                String encodedKw = URLEncoder.encode(keyword, "UTF-8");
                String url = "https://search.51job.com/list/" + cityCode +
                        ",000000,0000,00,9,99," + encodedKw + ",2," + page + ".html" +
                        "?lang=c&postchannel=0000&workyear=99&cotype=99&degreefrom=99" +
                        "&jobterm=99&companysize=99&ord_field=0";
                System.out.println("[QianchengCrawler] Fetching page " + page + ": " + url);

                String html = fetchPage(url, "https://www.51job.com/");
                if (html == null || html.isEmpty()) {
                    System.err.println("[QianchengCrawler] Empty response (anti-crawl?), stopping pagination");
                    break;
                }

                List<JobInfo> jobs = parser.parseListPage(html, keyword, city);
                if (jobs.isEmpty()) {
                    System.out.println("[QianchengCrawler] No jobs found on page " + page + ", stopping");
                    break;
                }

                System.out.println("[QianchengCrawler] Parsed " + jobs.size() + " jobs from page " + page);
                store.saveBatch(jobs);

                if (page < MAX_PAGES) {
                    sleepBetweenPages();
                }
            } catch (Exception e) {
                System.err.println("[QianchengCrawler] Error on page " + page + ": " + e.getMessage());
                break;
            }
        }
    }
}
```

---

### Task 7: 创建入口类 JobCrawlerMain

**Files:**
- Create: `src/main/java/com/wxut/agentplaza/crawler/JobCrawlerMain.java`

- [ ] **Step 1: 创建 JobCrawlerMain.java**

```java
package com.wxut.agentplaza.crawler;

import com.wxut.agentplaza.crawler.crawler.QianchengCrawler;
import com.wxut.agentplaza.crawler.crawler.ZhaopinCrawler;
import com.wxut.agentplaza.crawler.store.MysqlStore;

public class JobCrawlerMain {

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  招聘网站爬虫 启动");
        System.out.println("  数据源: 智联招聘, 前程无忧");
        System.out.println("  城市: 上海市, 无锡市, 苏州市");
        System.out.println("  关键词: 物联网, 智能制造, 工业自动化");
        System.out.println("========================================\n");

        MysqlStore store = new MysqlStore();

        // 先爬智联招聘（已验证可用）
        try {
            ZhaopinCrawler zhaopin = new ZhaopinCrawler(store);
            zhaopin.run();
        } catch (Exception e) {
            System.err.println("智联招聘爬虫异常: " + e.getMessage());
            e.printStackTrace();
        }

        // 再爬前程无忧（可能因反爬返回空数据）
        try {
            QianchengCrawler qiancheng = new QianchengCrawler(store);
            qiancheng.run();
        } catch (Exception e) {
            System.err.println("前程无忧爬虫异常: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("========================================");
        System.out.println("  所有爬虫任务完成");
        System.out.println("========================================");
    }
}
```

- [ ] **Step 2: 编译整个项目**

Run: `mvn compile -q -f d:/WorkMints/vscode/wxut-agent-pg-api/pom.xml 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交代码**

```bash
git add pom.xml src/main/java/com/wxut/agentplaza/crawler/ docs/
git commit -m "feat: 添加招聘网站爬虫模块（智联招聘+前程无忧）"
```

---

## 前程无忧替代方案

前程无忧 `search.51job.com` 使用 TLS 指纹识别 + JS 挑战，简单 HTTP 请求大概率返回空响应。如果需要抓取前程无忧数据，后续可考虑：

1. **Playwright/Selenium 无头浏览器** — 添加对应 Java 依赖，驱动真实浏览器渲染
2. **代理 + Cookie 池** — 获取浏览器会话 cookie 后使用
3. **手动数据采集** — 浏览器打开搜索页，手动保存 HTML 供离线解析
