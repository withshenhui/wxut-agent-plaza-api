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
