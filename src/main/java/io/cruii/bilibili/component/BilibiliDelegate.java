package io.cruii.bilibili.component;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.Header;
import cn.hutool.http.HttpException;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.rholder.retry.*;
import io.cruii.bilibili.constant.BilibiliAPI;
import io.cruii.bilibili.entity.BilibiliUser;
import io.cruii.bilibili.entity.TaskConfig;
import io.cruii.bilibili.util.CosUtil;
import io.cruii.bilibili.util.ProxyUtil;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * @author cruii
 * Created on 2021/9/14
 */
@Log4j2
public class BilibiliDelegate {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36";

    @Getter
    private final TaskConfig config;

    @Getter
    private HttpRequest httpRequest;

    private String proxyHost;
    private Integer proxyPort;

    private final Retryer<String> retryer = RetryerBuilder.<String>newBuilder()
            .retryIfExceptionOfType(HttpException.class)
            .retryIfExceptionOfType(IORuntimeException.class)
            .withStopStrategy(StopStrategies.stopAfterAttempt(3))
            .withRetryListener(new RetryListener() {
                @Override
                public <V> void onRetry(Attempt<V> attempt) {
                    if (attempt.hasException()) {
                        log.error("???{}???????????????: {}, ????????????", attempt.getAttemptNumber(), attempt.getExceptionCause().getMessage());
                    }
                }
            })
            .build();

    public BilibiliDelegate(String dedeuserid, String sessdata, String biliJct) {
        TaskConfig taskConfig = new TaskConfig();
        taskConfig.setDedeuserid(dedeuserid);
        taskConfig.setSessdata(sessdata);
        taskConfig.setBiliJct(biliJct);
        taskConfig.setUserAgent(UA);
        this.config = taskConfig;
    }

    public BilibiliDelegate(TaskConfig config) {
        this.config = config;
        if (CharSequenceUtil.isBlank(config.getUserAgent())) {
            config.setUserAgent(UA);
        }
        changeProxy();
    }

    public void changeProxy() {
        String proxy = ProxyUtil.get();

        setProxy(proxy);
    }

    private void setProxy(String proxy) {
        this.proxyHost = proxy.split(":")[0];
        this.proxyPort = Integer.parseInt(proxy.split(":")[1]);
    }

    /**
     * ????????????B????????????????????????
     *
     * @return B??????????????? {@link BilibiliUser}
     */
    public BilibiliUser getUser() {
        JSONObject resp = doGet(BilibiliAPI.GET_USER_INFO_NAV);

        // ??????????????????
        JSONObject data = resp.getJSONObject("data");
        // ??????????????????
        Boolean isLogin = data.getBool("isLogin");

        if (Boolean.FALSE.equals(isLogin)) {
            log.warn("??????Cookie?????????, {}, {}", config.getDedeuserid(), config.getSessdata());

            // ????????????????????????????????????????????????
            return getUser(config.getDedeuserid());
        }

        // ?????????????????????????????????
        // ????????????
        InputStream avatarStream = getAvatarStream(data.getStr("face"));
        String path = "avatars" + File.separator + config.getDedeuserid() + ".png";
        File avatarFile = new File(path);
        if (avatarFile.exists()) {
            String localMd5 = SecureUtil.md5(avatarFile);
            String remoteMd5 = SecureUtil.md5(avatarStream);
            if (!localMd5.equals(remoteMd5)) {
                FileUtil.writeFromStream(avatarStream, avatarFile);
            }
        } else {
            FileUtil.writeFromStream(avatarStream, avatarFile);
        }

        // ????????? oss
        CosUtil.upload(avatarFile);

        String uname = data.getStr("uname");
        // ???????????????
        String coins = data.getStr("money");

        // ?????????????????????
        JSONObject vip = data.getJSONObject("vip");

        // ??????????????????
        JSONObject levelInfo = data.getJSONObject("level_info");
        Integer currentLevel = levelInfo.getInt("current_level");

        // ???????????????
        JSONObject medalWallResp = getMedalWall();
        List<JSONObject> medals = medalWallResp.getJSONObject("data")
                .getJSONArray("list")
                .stream()
                .map(JSONUtil::parseObj)
                .map(medalObj -> {
                    JSONObject medal = JSONUtil.createObj();
                    medal.set("name", medalObj.getByPath("medal_info.medal_name", String.class));
                    medal.set("level", medalObj.getByPath("medal_info.level", Integer.class));
                    medal.set("colorStart", medalObj.getByPath("medal_info.medal_color_start", Integer.class));
                    medal.set("colorEnd", medalObj.getByPath("medal_info.medal_color_end", Integer.class));
                    medal.set("colorBorder", medalObj.getByPath("medal_info.medal_color_border", Integer.class));
                    return medal;
                })
                .sorted((o1, o2) -> o2.getInt("level") - o1.getInt("level"))
                .limit(2L)
                .collect(Collectors.toList());

        BilibiliUser info = new BilibiliUser();
        info.setDedeuserid(config.getDedeuserid());
        info.setUsername(uname);
        info.setCoins(coins);
        info.setLevel(currentLevel);
        info.setCurrentExp(levelInfo.getInt("current_exp"));
        info.setNextExp(currentLevel == 6 ? 0 : levelInfo.getInt("next_exp"));
        info.setMedals(JSONUtil.toJsonStr(medals));
        info.setVipType(vip.getInt("type"));
        info.setVipStatus(vip.getInt("status"));
        info.setIsLogin(true);

        return info;
    }

    /**
     * ???Cookie????????????????????????????????????
     *
     * @param userId B???uid
     * @return B??????????????? {@link BilibiliUser}
     */
    public BilibiliUser getUser(String userId) {
        String body = HttpRequest.get(BilibiliAPI.GET_USER_SPACE_INFO + "?mid=" + userId).execute().body();

        JSONObject resp = JSONUtil.parseObj(body);
        JSONObject baseInfo = resp.getJSONObject("data");
        if (resp.getInt("code") == -404 || baseInfo == null) {
            log.error("??????[{}]?????????", userId);
            return null;
        }
        InputStream avatarStream = getAvatarStream(baseInfo.getStr("face"));
        String path = "avatars" + File.separator + config.getDedeuserid() + ".png";
        File avatarFile = new File(path);
        if (avatarFile.exists()) {
            String localMd5 = SecureUtil.md5(avatarFile);
            String remoteMd5 = SecureUtil.md5(avatarStream);
            if (!localMd5.equals(remoteMd5)) {
                FileUtil.writeFromStream(avatarStream, avatarFile);
            }
        } else {
            FileUtil.writeFromStream(avatarStream, avatarFile);
        }

        // ????????? oss
        CosUtil.upload(avatarFile);

        BilibiliUser info = new BilibiliUser();
        info.setDedeuserid(userId);
        info.setUsername(baseInfo.getStr("name"));
        info.setLevel(baseInfo.getInt("level"));
        info.setIsLogin(false);

        return info;
    }

    /**
     * ???????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getMedalWall() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.put("target_id", CollUtil.newArrayList(config.getDedeuserid()));
        return doGet(BilibiliAPI.GET_MEDAL_WALL, params);
    }

    /**
     * ??????Cookie?????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject checkCookie() {
        return doGet(BilibiliAPI.GET_USER_INFO_NAV, null);
    }

    /**
     * ????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getCoinChangeLog() {
        return doGet(BilibiliAPI.GET_COIN_CHANGE_LOG);
    }

    /**
     * ????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getExpRewardStatus() {
        return doGet(BilibiliAPI.GET_EXP_REWARD_STATUS);
    }

    /**
     * ??????????????????UP????????????????????????BVID
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getFollowedUpPostVideo() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.put("uid", CollUtil.newArrayList(config.getDedeuserid()));
        params.put("type_list", CollUtil.newArrayList("8"));
        params.put("from", CollUtil.newArrayList());
        params.put("platform", CollUtil.newArrayList("web"));

        return doGet(BilibiliAPI.GET_FOLLOWED_UP_POST_VIDEO, params);
    }

    /**
     * ????????????ID??????3???????????????
     *
     * @param regionId ??????ID
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getTrendVideo(String regionId) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.put("rid", CollUtil.newArrayList(regionId));
        params.put("day", CollUtil.newArrayList("3"));

        return doGet(BilibiliAPI.GET_TREND_VIDEO, params);
    }

    /**
     * ????????????
     *
     * @param bvid       ?????????BVID
     * @param playedTime ??????????????????
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject playVideo(String bvid, int playedTime) {
        Map<String, Object> params = new HashMap<>();
        params.put("bvid", bvid);
        params.put("played_time", playedTime);
        String requestBody = HttpUtil.toParams(params);
        return doPost(BilibiliAPI.REPORT_HEARTBEAT, requestBody);
    }

    /**
     * ????????????
     *
     * @param bvid ?????????BVID
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject shareVideo(String bvid) {
        Map<String, Object> params = new HashMap<>();
        params.put("bvid", bvid);
        params.put("csrf", config.getBiliJct());
        String requestBody = HttpUtil.toParams(params);

        return doPost(BilibiliAPI.SHARE_VIDEO, requestBody);
    }


    /**
     * ????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject mangaCheckIn(String platform) {
        Map<String, Object> params = new HashMap<>();
        params.put("platform", platform);
        String requestBody = HttpUtil.toParams(params);
        return doPost(BilibiliAPI.MANGA_SIGN, requestBody);
    }

    /**
     * ??????????????????????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getCoinExpToday() {
        return doGet(BilibiliAPI.GET_COIN_EXP_TODAY);
    }

    /**
     * ????????????????????????
     *
     * @param bvid ??????BVID
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getVideoDetails(String bvid) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.put("bvid", CollUtil.newArrayList(bvid));
        return doGet(BilibiliAPI.GET_VIDEO_DETAILS, params);
    }

    /**
     * ????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getCoin() {
        return doGet(BilibiliAPI.GET_COIN);
    }


    /**
     * ???????????????????????????
     *
     * @param bvid ?????????bvid
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject checkDonateCoin(String bvid) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.put("bvid", CollUtil.newArrayList(bvid));
        return doGet(BilibiliAPI.CHECK_DONATE_COIN, params);
    }

    /**
     * ??????
     *
     * @param bvid    ?????????bvid
     * @param numCoin ?????????
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject donateCoin(String bvid, int numCoin, int isLike) {
        Map<String, Object> params = new HashMap<>();
        params.put("bvid", bvid);
        params.put("multiply", numCoin);
        params.put("select_like", isLike);
        params.put("cross_domain", true);
        params.put("csrf", config.getBiliJct());
        String requestBody = HttpUtil.toParams(params);

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.bilibili.com/video/" + bvid);
        headers.put("Origin", "https://www.bilibili.com");

        return doPost(BilibiliAPI.DONATE_COIN, requestBody, headers);
    }

    /**
     * ?????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getLiveWallet() {
        return doGet(BilibiliAPI.BILI_LIVE_WALLET);
    }

    /**
     * ?????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject silver2Coin() {
        Map<String, Object> params = new HashMap<>();
        params.put("csrf_token", config.getBiliJct());
        params.put("csrf", config.getBiliJct());
        String requestBody = HttpUtil.toParams(params);

        return doPost(BilibiliAPI.SILVER_2_COIN, requestBody);
    }

    /**
     * ????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject liveCheckIn() {
        return doGet(BilibiliAPI.BILI_LIVE_CHECK_IN);
    }

    /**
     * ????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject listGifts() {
        return doGet(BilibiliAPI.LIST_GIFTS);
    }

    /**
     * ?????????????????????
     *
     * @param userId ??????id
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getLiveRoomInfo(String userId) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.put("mid", CollUtil.newArrayList(userId));

        return doGet(BilibiliAPI.GET_LIVE_ROOM_INFO, params);
    }

    /**
     * ?????????????????????
     *
     * @param userId  ?????????uid
     * @param roomId  ???????????????id
     * @param bagId   ??????id
     * @param giftId  ??????id
     * @param giftNum ????????????
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject donateGift(String userId, String roomId,
                                 String bagId, String giftId, int giftNum) {
        Map<String, Object> params = new HashMap<>();
        params.put("biz_id", roomId);
        params.put("ruid", userId);
        params.put("gift_id", giftId);
        params.put("bag_id", bagId);
        params.put("gift_num", giftNum);
        params.put("uid", config.getDedeuserid());
        params.put("csrf", config.getBiliJct());
        params.put("send_ruid", 0);
        params.put("storm_beat_id", 0);
        params.put("price", 0);
        params.put("platform", "pc");
        params.put("biz_code", "live");
        String requestBody = HttpUtil.toParams(params);

        return doPost(BilibiliAPI.SEND_GIFT, requestBody);
    }

    /**
     * ??????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getChargeInfo() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.put("mid", CollUtil.newArrayList(config.getDedeuserid()));

        return doGet(BilibiliAPI.GET_CHARGE_INFO, params);
    }

    /**
     * ??????
     *
     * @param couponBalance B????????????
     * @param upUserId      ???????????????userId
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject doCharge(int couponBalance, String upUserId) {
        Map<String, Object> params = new HashMap<>();
        params.put("bp_num", couponBalance);
        params.put("is_bp_remains_prior", true);
        params.put("up_mid", upUserId);
        params.put("otype", "up");
        params.put("oid", config.getDedeuserid());
        params.put("csrf", config.getBiliJct());

        String requestBody = HttpUtil.toParams(params);

        return doPost(BilibiliAPI.CHARGE, requestBody);
    }

    /**
     * ??????????????????
     *
     * @param orderNo ???????????????
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject doChargeComment(String orderNo) {
        Map<String, Object> params = new HashMap<>();
        params.put("order_id", orderNo);
        params.put("message", "up???????????????");
        params.put("csrf", config.getBiliJct());

        String requestBody = HttpUtil.toParams(params);

        return doPost(BilibiliAPI.COMMIT_CHARGE_COMMENT, requestBody);
    }

    /**
     * ???????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getMangaVipReward() {
        Map<String, Object> params = new HashMap<>();
        params.put("reason_id", 1);

        String requestBody = JSONUtil.parseObj(params).toJSONString(0);
        return doPost(BilibiliAPI.GET_MANGA_VIP_REWARD, requestBody);
    }

    /**
     * ?????????????????????
     *
     * @param type 1 = B??????  2 = ??????????????????
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getVipReward(int type) {
        Map<String, Object> params = new HashMap<>();
        params.put("type", type);
        params.put("csrf", config.getBiliJct());

        String requestBody = HttpUtil.toParams(params);
        return doPost(BilibiliAPI.GET_VIP_REWARD, requestBody);
    }

    /**
     * ????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject readManga() {
        Map<String, String> params = new HashMap<>(4);
        params.put("device", "pc");
        params.put("platform", "web");
        params.put("comic_id", "26009");
        params.put("ep_id", "300318");

        String requestBody = JSONUtil.parseObj(params).toJSONString(0);
        return doPost(BilibiliAPI.READ_MANGA, requestBody);
    }

    public JSONObject followUser(String uid) {
        Map<String, Object> params = new HashMap<>();
        params.put("fid", uid);
        params.put("act", 1);
        params.put("re_src", 11);
        params.put("csrf", config.getBiliJct());

        String requestBody = HttpUtil.toParams(params);
        return doPost(BilibiliAPI.RELATION_MODIFY, requestBody);
    }

    public String getAvatar() {
        JSONObject resp = doGet(BilibiliAPI.GET_USER_INFO_NAV);
        // ??????????????????
        JSONObject data = resp.getJSONObject("data");
        InputStream avatarStream = getAvatarStream(data.getStr("face"));
        return Base64.encode(avatarStream);
    }

    /**
     * ??????B????????????????????????
     *
     * @param avatarUrl ????????????
     * @return ???????????????
     */
    private InputStream getAvatarStream(String avatarUrl) {
        return HttpRequest
                .get(avatarUrl)
                .execute().bodyStream();
    }

    /**
     * ????????????
     *
     * @param username B????????????
     * @return ??????*????????????????????????
     */
    private String coverUsername(String username) {
        StringBuilder sb = new StringBuilder();

        if (username.length() > 2) {
            // ????????????????????????2???????????????????????????*?????????
            for (int i = 0; i < username.length(); i++) {
                if (i > 0 && i < username.length() - 1) {
                    sb.append("*");
                } else {
                    sb.append(username.charAt(i));
                }
            }
        } else {
            // ???????????????????????????2?????????????????????????????????
            sb.append(username.charAt(0)).append("*");
        }

        return sb.toString();
    }

    private JSONObject doGet(String url) {
        return doGet(url, null);
    }

    /**
     * ????????????B???API??????
     *
     * @param url    ??????API??????
     * @param params ????????????????????? {@link MultiValueMap}
     * @return ????????????JSON?????? {@link JSONObject}
     */
    private JSONObject doGet(String url, MultiValueMap<String, String> params) {
        url = UriComponentsBuilder.fromHttpUrl(url)
                .queryParams(params)
                .build().toUriString();
        httpRequest = HttpRequest.get(url)
                .timeout(100000)
                .header(Header.CONNECTION, "keep-alive")
                .header(Header.USER_AGENT, config.getUserAgent())
                .cookie("bili_jct=" + config.getBiliJct() +
                        ";SESSDATA=" + config.getSessdata() +
                        ";DedeUserID=" + config.getDedeuserid() + ";");

        if (!ObjectUtil.hasNull(proxyHost, proxyPort)) {
            httpRequest.setHttpProxy(proxyHost, proxyPort);
        }

        return retryableCall(httpRequest);
    }

    private JSONObject doPost(String url, String requestBody) {
        return doPost(url, requestBody, null);
    }

    private JSONObject doPost(String url, String requestBody, Map<String, String> headers) {
        httpRequest = HttpRequest.post(url)
                .timeout(10000)
                .header(Header.CONTENT_TYPE, JSONUtil.isJson(requestBody) ?
                        MediaType.APPLICATION_JSON_VALUE : MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .header(Header.CONNECTION, "keep-alive")
                .header(Header.USER_AGENT, config.getUserAgent())
                .header(Header.REFERER, "https://www.bilibili.com/")
                .addHeaders(headers)
                .cookie("bili_jct=" + config.getBiliJct() +
                        ";SESSDATA=" + config.getSessdata() +
                        ";DedeUserID=" + config.getDedeuserid() + ";");
        if (!ObjectUtil.hasNull(proxyHost, proxyPort)) {
            httpRequest.setHttpProxy(proxyHost, proxyPort);
        }

        return retryableCall(httpRequest.body(requestBody));
    }

    private JSONObject retryableCall(HttpRequest httpRequest) {
        Callable<String> task = () -> httpRequest.execute().body();
        String responseBody = null;
        try {
            responseBody = retryer.call(task);
        } catch (ExecutionException e) {
            log.error("??????????????????[{}]??????, {}", httpRequest.getUrl(), e.getMessage());
        } catch (RetryException e) {
            log.error("????????????[{}]??????????????????, {}", httpRequest.getUrl(), e.getMessage());
        }
        return JSONUtil.parseObj(responseBody);
    }
}
