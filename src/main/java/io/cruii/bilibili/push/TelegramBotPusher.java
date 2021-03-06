package io.cruii.bilibili.push;

import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.log4j.Log4j2;

/**
 * @author cruii
 * Created on 2021/10/03
 */
@Log4j2
public class TelegramBotPusher implements Pusher {
    private final String token;

    private final String chatId;

    public TelegramBotPusher(String token, String chatId) {
        this.token = token;
        this.chatId = chatId;
    }

    @Override
    public boolean push(String content) {
        String body = HttpRequest.post("https://api.telegram.org/bot" + token + "/sendMessage")
                .header(Header.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .body("chat_id=" + chatId + "&text=" + content)
                .execute().body();
        if (JSONUtil.isJson(body)) {
            JSONObject resp = JSONUtil.parseObj(body);
            if (Boolean.TRUE.equals(resp.getBool("ok"))) {
                log.info("Telegram Bot 推送成功");
                return true;
            }
        }
        log.error("Telegram Bot 推送失败：{}", body);
        return false;
    }
}
