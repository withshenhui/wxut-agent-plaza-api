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
        String currentUrl = url;
        for (int redirect = 0; redirect <= 5; redirect++) {
            HttpResponse response = doRequest(currentUrl, referer);
            if (response == null) {
                return null;
            }
            int status = response.getStatus();
            if (status == 301 || status == 302 || status == 303 || status == 307) {
                String location = response.header("Location");
                if (location != null && !location.isEmpty()) {
                    System.out.println("[BaseCrawler] Redirect " + status + " -> " + location);
                    currentUrl = location;
                    sleep(1000);
                    continue;
                }
            }
            if (response.isOk()) {
                return response.body();
            }
            System.err.println("[BaseCrawler] HTTP " + status + " for " + currentUrl);
            return null;
        }
        System.err.println("[BaseCrawler] Too many redirects for " + url);
        return null;
    }

    private HttpResponse doRequest(String url, String referer) {
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
                return request.execute();
            } catch (Exception e) {
                System.err.println("[BaseCrawler] Attempt " + (i + 1) + " failed for " + url + ": " + e.getMessage());
                if (i < RETRY_COUNT - 1) {
                    sleep(3000);
                }
            }
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
