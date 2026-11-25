package com.wxut.agentplaza.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxut.agentplaza.common.Result;
import com.wxut.agentplaza.entity.SysConfig;
import com.wxut.agentplaza.service.AuthService;
import com.wxut.agentplaza.service.SysConfigService;
import com.wxut.agentplaza.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 企微 H5 免密登录：code → 企微 getuserinfo → UserId → 中台账号 → 同一套 JWT。
 *  调试通道 mockUser 仅当命中 sys_config wecom.debug_whitelist 时可用（域名未就绪期联调）。 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class WecomAuthController {

    private final AuthService authService;
    private final SysConfigService configService;
    private final ObjectMapper om = new ObjectMapper();

    @PostMapping("/wecom-login")
    public Result<LoginVO> wecomLogin(@RequestBody Map<String, String> body) throws Exception {
        String mockUser = body.get("mockUser");
        String wecomUserId;
        if (mockUser != null && !mockUser.isEmpty()) {
            String wl = cfg("wecom.debug_whitelist", "");
            boolean ok = false;
            for (String w : wl.split(",")) { if (!w.trim().isEmpty() && w.trim().equals(mockUser)) { ok = true; break; } }
            if (!ok) return Result.error("wecom_debug_denied");
            wecomUserId = mockUser;
        } else {
            String code = body.get("code");
            if (code == null || code.isEmpty()) return Result.error("wecom_no_code");
            wecomUserId = fetchUserId(code);
            if (wecomUserId == null) return Result.error("wecom_auth_failed");
        }
        return Result.success(authService.loginByWecom(wecomUserId));
    }

    /** code → 企微 access_token → userid */
    private String fetchUserId(String code) throws Exception {
        String corpid = cfg("wecom.corpid", "");
        String secret = cfg("wecom.secret", "");
        if (corpid.isEmpty() || secret.isEmpty()) return null;
        JsonNode tok = getJson("https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid="
                + URLEncoder.encode(corpid, "UTF-8") + "&corpsecret=" + URLEncoder.encode(secret, "UTF-8"));
        if (tok == null || tok.path("errcode").asInt(-1) != 0) return null;
        String accessToken = tok.path("access_token").asText("");
        JsonNode ui = getJson("https://qyapi.weixin.qq.com/cgi-bin/auth/getuserinfo?access_token="
                + URLEncoder.encode(accessToken, "UTF-8") + "&code=" + URLEncoder.encode(code, "UTF-8"));
        if (ui == null || ui.path("errcode").asInt(-1) != 0) return null;
        String uid = ui.path("userid").asText("");
        return uid.isEmpty() ? null : uid;
    }

    private JsonNode getJson(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return om.readTree(new String(bo.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("企微接口调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String cfg(String key, String def) {
        try {
            SysConfig c = configService.getByKey(key);
            String v = c == null ? null : c.getConfigValue();
            return (v == null || v.trim().isEmpty()) ? def : v.trim();
        } catch (Exception e) {
            return def;
        }
    }
}
