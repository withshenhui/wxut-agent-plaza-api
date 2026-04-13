package com.wxut.agentplaza.crawler.crawler;

import com.wxut.agentplaza.crawler.model.JobInfo;
import com.wxut.agentplaza.crawler.parser.QianchengParser;
import com.wxut.agentplaza.crawler.store.MysqlStore;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class QianchengCrawler extends BaseCrawler {

    private static final Map<String, String> CITY_CODE_MAP = new LinkedHashMap<>();

    static {
        CITY_CODE_MAP.put("上海市", "020000");
        CITY_CODE_MAP.put("南京市", "080200");
        CITY_CODE_MAP.put("苏州市", "080300");
        CITY_CODE_MAP.put("无锡市", "080400");
        CITY_CODE_MAP.put("常州市", "080500");
        CITY_CODE_MAP.put("镇江市", "080600");
        CITY_CODE_MAP.put("扬州市", "080700");
        CITY_CODE_MAP.put("南通市", "080800");
        CITY_CODE_MAP.put("盐城市", "080900");
        CITY_CODE_MAP.put("徐州市", "081000");
        CITY_CODE_MAP.put("淮安市", "081100");
        CITY_CODE_MAP.put("连云港市", "081200");
        CITY_CODE_MAP.put("泰州市", "081300");
        CITY_CODE_MAP.put("宿迁市", "081400");
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
        System.out.println("========== 前程无忧 爬虫启动 (Selenium) ==========");

        WebDriver driver = null;
        try {
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless");
            options.addArguments("--disable-gpu");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-blink-features=AutomationControlled");
            options.addArguments("--window-size=1920,1080");

            driver = new ChromeDriver(options);
            driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);
            driver.manage().timeouts().pageLoadTimeout(30, TimeUnit.SECONDS);

            for (String keyword : KEYWORDS) {
                for (Map.Entry<String, String> entry : CITY_CODE_MAP.entrySet()) {
                    String city = entry.getKey();
                    String cityCode = entry.getValue();
                    System.out.println("\n--- 搜索: " + keyword + " @ " + city + " ---");
                    crawlKeywordCity(driver, keyword, city, cityCode);
                }
            }
        } catch (Exception e) {
            System.err.println("[QianchengCrawler] Selenium error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }

        System.out.println("========== 前程无忧 爬虫结束 ==========\n");
    }

    private void crawlKeywordCity(WebDriver driver, String keyword, String city, String cityCode) {
        for (int page = 1; page <= MAX_PAGES; page++) {
            try {
                String encodedKw = URLEncoder.encode(keyword, "UTF-8");
                String url = "https://search.51job.com/list/" + cityCode +
                        ",000000,0000,00,9,99," + encodedKw + ",2," + page + ".html" +
                        "?lang=c&postchannel=0000&workyear=99&cotype=99&degreefrom=99" +
                        "&jobterm=99&companysize=99&ord_field=0";
                System.out.println("[QianchengCrawler] Fetching page " + page + ": " + url);

                driver.get(url);

                // 等待页面加载和JS渲染
                Thread.sleep(5000);

                String html = driver.getPageSource();
                if (html == null || html.isEmpty()) {
                    System.err.println("[QianchengCrawler] Empty page source, stopping");
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
