package com.example.template.controller;

import com.example.template.common.RestResult;
import com.example.template.util.JacksonUtils;
import com.example.template.util.SimpleHttpClientUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@RestController
public class SimpleController {

    private static final Logger log = LoggerFactory.getLogger(SimpleController.class);

    private static final String DING_ACCESS_TOKEN_URL = "https://api.dingtalk.com/v1.0/oauth2/userAccessToken";

    private static final String DING_USER_INFO_URL = "https://api.dingtalk.com/v1.0/contact/users/me";

    private static final TypeReference<DingUserInfo> DING_USER_INFO_TYPE = new TypeReference<DingUserInfo>() {
    };
    private static final TypeReference<AccessTokenResponse> DING_ACCESS_TOKEN_TYPE = new TypeReference<AccessTokenResponse>() {
    };

    @PostMapping(value = {"/v5/wu/base/user/sync/first-login-tag", "/id-base/v5/base/user/pwd/modify"})
    public RestResult<Boolean> changePlace2(@RequestBody Map<String, Object> loginPlaceAndTime) {
        log.info("call /v5/base/user/sync/first-login-tag =[{}]", JacksonUtils.toJson(loginPlaceAndTime));
        return RestResult.success(Boolean.TRUE);
    }


    @PostMapping("/authn-api/v5/dingTalk/check/code")
    public RestResult<String> checkCode(@RequestBody Map<String, Object> data) {
        log.info("/authn-api/v5/dingTalk/check/code [{}]", JacksonUtils.toJson(data));
        String oauthCode = Objects.toString(data.get("code"), "");

        // 进行 钉钉 OAuth 认证
        log.info("执行钉钉 DingOauth 认证");
        String appKey = "dingkop2sjlucs62jlpn";
        String appSecret = "SpLY5NN8o1OInlE_YqbWSaAV8xWGdc2YLnUoTG-LOq7f_obu-2F1Wxqwoec3SuSt";
        // 获取用户 AccessToken
        Map<String, String> params = new HashMap<>(8);
        params.put("clientId", appKey);
        params.put("clientSecret", appSecret);
        params.put("code", oauthCode);
        params.put("grantType", "authorization_code");
        HttpPost accessTokenPost = SimpleHttpClientUtils.createJsonPost(DING_ACCESS_TOKEN_URL, params);

        AccessTokenResponse atResult = SimpleHttpClientUtils.execute(accessTokenPost, resp -> {
            int statusCode = resp.getStatusLine().getStatusCode();
            String respContent;
            try {
                respContent = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                log.info("DingOauth 获取 AccessToken 响应码 [{}] 响应内容 [{}]", statusCode, respContent);
                return SimpleHttpClientUtils.toObj(respContent, DING_ACCESS_TOKEN_TYPE);
            } catch (IOException e) {
                log.error("DingOauth 获取 AccessToken 响应内容异常, 响应码 [{}]", statusCode, e);
            }
            return null;
        });
        if (null == atResult || StringUtils.isNotBlank(atResult.getCode())) {
            String message = (atResult == null ? "" : atResult.getMessage());
            log.error("DingOauth 获取 AccessToken 出错 [{}]", message);
            return RestResult.failure("DingOauth 获取 AccessToken 出错 [" + message + "]");
        }
        String accessToken = atResult.getAccessToken();
        HttpGet userInfoGet = new HttpGet(DING_USER_INFO_URL);
        userInfoGet.setHeader("x-acs-dingtalk-access-token", accessToken);
        DingUserInfo userInfoResult = SimpleHttpClientUtils.execute(userInfoGet, resp -> {
            int statusCode = resp.getStatusLine().getStatusCode();
            String respContent;
            try {
                respContent = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                log.info("DingOauth 获取用户详情响应码 [{}] 响应内容 [{}]", statusCode, respContent);
                return SimpleHttpClientUtils.toObj(respContent, DING_USER_INFO_TYPE);
            } catch (IOException e) {
                log.error("DingOauth 获取用户详情响应内容异常, 响应码 [{}]", statusCode, e);
            }
            return null;
        });
        if (null == userInfoResult || StringUtils.isNotBlank(userInfoResult.getCode())) {
            String message = (userInfoResult == null ? "" : userInfoResult.getMessage());
            log.error("DingOauth 获取用户详情出错 [{}]", message);
            return RestResult.failure("DingOauth 获取用户详情出错 [" + message + "]");
        }
        return RestResult.success(userInfoResult.getMobile());
    }


    @PostMapping(value = {"/sync/data/receiver", "/sync/data/receiver2"})
    public Map<String, Object> syncDataReceiver(@RequestBody Map<String, Object> param) {
        log.info("call /sync/data/receiver [{}]", JacksonUtils.toJson(param));

        Map<String, Object> result = new HashMap<>(4);
        result.put("resultCode", 200);
        result.put("code", 0);
        result.put("resultMsg", "成功");

        /*String resType = Objects.toString(param.get("resType"));
        List<Map<String, Object>> data = (List<Map<String, Object>>) param.get("data");
        log.info("[{}] 接受数据量 [{}]", resType, data.size());

        if ("user".equals(resType)) {
            for (Map<String, Object> d : data) {
                log.info("[{}]-[{}] 邮箱状态 [{}] 用户状态 [{}]",
                    d.get("USER_ID"), d.get("USER_NAME"), d.get("EMAIL_STATUS"), d.get("STATUS"));
            }
        }*/

        return result;
    }

    static class DingUserInfo {
        private String nick;
        private String unionId;
        private String avatarUrl;
        private String openId;
        private String mobile;
        private String stateCode;
        private Boolean visitor;

        private String requestid;
        private String code;
        private String message;

        public String getRequestid() {
            return requestid;
        }

        public void setRequestid(String requestid) {
            this.requestid = requestid;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getNick() {
            return nick;
        }

        public void setNick(String nick) {
            this.nick = nick;
        }

        public String getUnionId() {
            return unionId;
        }

        public void setUnionId(String unionId) {
            this.unionId = unionId;
        }

        public String getAvatarUrl() {
            return avatarUrl;
        }

        public void setAvatarUrl(String avatarUrl) {
            this.avatarUrl = avatarUrl;
        }

        public String getOpenId() {
            return openId;
        }

        public void setOpenId(String openId) {
            this.openId = openId;
        }

        public String getMobile() {
            return mobile;
        }

        public void setMobile(String mobile) {
            this.mobile = mobile;
        }

        public String getStateCode() {
            return stateCode;
        }

        public void setStateCode(String stateCode) {
            this.stateCode = stateCode;
        }

        public Boolean getVisitor() {
            return visitor;
        }

        public void setVisitor(Boolean visitor) {
            this.visitor = visitor;
        }
    }


    // {
    //    "expireIn": 7200,
    //    "accessToken": "798d8e5987b5362bb533397b94300f1b",
    //    "refreshToken": "723674adad5739dab2e099bcfb2b63d3"
    //}
    // 异常
    // {
    //    "requestid": "F340FF76-0F86-73F7-AA31-48529977CD5B",
    //    "code": "invalidParameter.authCode.notFound",
    //    "message": "不合法的临时授权码"
    //}
    static class AccessTokenResponse {
        private int expireIn;
        private String accessToken;
        private String refreshToken;

        private String requestid;
        private String code;
        private String message;

        public int getExpireIn() {
            return expireIn;
        }

        public void setExpireIn(int expireIn) {
            this.expireIn = expireIn;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }

        public String getRequestid() {
            return requestid;
        }

        public void setRequestid(String requestid) {
            this.requestid = requestid;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

}
