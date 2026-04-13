package com.wxut.agentplaza.crawler.crawler;

import com.wxut.agentplaza.crawler.model.JobInfo;
import com.wxut.agentplaza.crawler.parser.ZhaopinParser;
import com.wxut.agentplaza.crawler.store.MysqlStore;

import java.net.URLEncoder;
import java.util.*;

public class ZhaopinCrawler extends BaseCrawler {

    private static final Map<String, String> CITY_CODE_MAP = new LinkedHashMap<>();

    static {
        CITY_CODE_MAP.put("上海市", "538");
        CITY_CODE_MAP.put("南京市", "613");
        CITY_CODE_MAP.put("苏州市", "636");
        CITY_CODE_MAP.put("无锡市", "639");
        CITY_CODE_MAP.put("常州市", "640");
        CITY_CODE_MAP.put("南通市", "642");
        CITY_CODE_MAP.put("扬州市", "646");
        CITY_CODE_MAP.put("镇江市", "645");
        CITY_CODE_MAP.put("徐州市", "631");
        CITY_CODE_MAP.put("淮安市", "638");
        CITY_CODE_MAP.put("盐城市", "634");
        CITY_CODE_MAP.put("连云港市", "641");
        CITY_CODE_MAP.put("泰州市", "649");
        CITY_CODE_MAP.put("宿迁市", "632");
    }

    private static final List<String> KEYWORDS = Arrays.asList(
            "物联网", "智能制造", "工业自动化"
    );

    private static final int MAX_PAGES = 10;

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

                // 抓取每个职位的详情页获取描述
                fetchDescriptions(jobs);

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

    private void fetchDescriptions(List<JobInfo> jobs) {
        for (JobInfo job : jobs) {
            String href = job.getJobHref();
            if (href == null || href.isEmpty()) {
                continue;
            }
            try {
                String detailHtml = fetchPage(href);
                if (detailHtml != null) {
                    String desc = parser.parseDescription(detailHtml);
                    if (desc != null) {
                        job.setDescription(desc);
                    }
                }
                sleep(2000);
            } catch (Exception e) {
                System.err.println("[ZhaopinCrawler] Failed to fetch detail for " + href + ": " + e.getMessage());
            }
        }
    }
}
