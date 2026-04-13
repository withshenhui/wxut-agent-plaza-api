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
