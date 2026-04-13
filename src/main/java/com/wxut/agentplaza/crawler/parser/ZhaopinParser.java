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

    public String parseDescription(String detailHtml) {
        if (detailHtml == null || detailHtml.isEmpty()) {
            return null;
        }
        Document doc = Jsoup.parse(detailHtml);
        Element descEl = doc.selectFirst("div.describtion-card__detail-content");
        if (descEl != null) {
            return descEl.text().trim();
        }
        return null;
    }
}
