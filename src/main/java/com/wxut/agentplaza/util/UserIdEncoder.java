package com.wxut.agentplaza.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

public class UserIdEncoder {

    public static String encodeUserId(String userId) throws Exception {
        // 1. UTF-8 字节
        byte[] text = userId.getBytes(StandardCharsets.UTF_8);
        // 2. Gzip 压缩
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(text);
        }
        byte[] compressed = bos.toByteArray();
        // 3. Base64 编码
        return Base64.getEncoder().encodeToString(compressed);
    }

    public static void main(String[] args) throws Exception {
        String userId = args.length > 0 ? args[0] : "2013070823";
        String encoded = encodeUserId(userId);
        System.out.println("原始工号: " + userId);
        System.out.println("编码结果: " + encoded);
    }
}
