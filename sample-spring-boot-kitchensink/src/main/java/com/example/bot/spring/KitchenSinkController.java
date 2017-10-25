/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.example.bot.spring;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import com.linecorp.bot.model.action.DatetimePickerAction;
import com.linecorp.bot.model.message.template.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.google.common.io.ByteStreams;

import com.linecorp.bot.client.LineMessagingClient;
import com.linecorp.bot.client.MessageContentResponse;
import com.linecorp.bot.model.ReplyMessage;
import com.linecorp.bot.model.PushMessage;
import com.linecorp.bot.model.action.MessageAction;
import com.linecorp.bot.model.action.PostbackAction;
import com.linecorp.bot.model.action.URIAction;
import com.linecorp.bot.model.event.BeaconEvent;
import com.linecorp.bot.model.event.Event;
import com.linecorp.bot.model.event.FollowEvent;
import com.linecorp.bot.model.event.JoinEvent;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.PostbackEvent;
import com.linecorp.bot.model.event.UnfollowEvent;
import com.linecorp.bot.model.event.message.AudioMessageContent;
import com.linecorp.bot.model.event.message.ImageMessageContent;
import com.linecorp.bot.model.event.message.LocationMessageContent;
import com.linecorp.bot.model.event.message.StickerMessageContent;
import com.linecorp.bot.model.event.message.TextMessageContent;
import com.linecorp.bot.model.event.message.VideoMessageContent;
import com.linecorp.bot.model.event.source.GroupSource;
import com.linecorp.bot.model.event.source.RoomSource;
import com.linecorp.bot.model.event.source.Source;
import com.linecorp.bot.model.message.AudioMessage;
import com.linecorp.bot.model.message.ImageMessage;
import com.linecorp.bot.model.message.ImagemapMessage;
import com.linecorp.bot.model.message.LocationMessage;
import com.linecorp.bot.model.message.Message;
import com.linecorp.bot.model.message.StickerMessage;
import com.linecorp.bot.model.message.TemplateMessage;
import com.linecorp.bot.model.message.TextMessage;
import com.linecorp.bot.model.message.VideoMessage;
import com.linecorp.bot.model.message.imagemap.ImagemapArea;
import com.linecorp.bot.model.message.imagemap.ImagemapBaseSize;
import com.linecorp.bot.model.message.imagemap.MessageImagemapAction;
import com.linecorp.bot.model.message.imagemap.URIImagemapAction;
import com.linecorp.bot.model.response.BotApiResponse;
import com.linecorp.bot.spring.boot.annotation.EventMapping;
import com.linecorp.bot.spring.boot.annotation.LineMessageHandler;

import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@LineMessageHandler
public class KitchenSinkController {
    @Autowired
    private LineMessagingClient lineMessagingClient;

    @EventMapping
    public void handleTextMessageEvent(MessageEvent<TextMessageContent> event) throws Exception {
            TextMessageContent message = event.getMessage();
            handleTextContent(event.getReplyToken(), event, message);
    }

    @EventMapping
    public void handleStickerMessageEvent(MessageEvent<StickerMessageContent> event) {
        handleSticker(event.getReplyToken(), event.getMessage());
    }

    @EventMapping
    public void handleLocationMessageEvent(MessageEvent<LocationMessageContent> event) {
        LocationMessageContent locationMessage = event.getMessage();
        reply(event.getReplyToken(), new LocationMessage(
                locationMessage.getTitle(),
                locationMessage.getAddress(),
                locationMessage.getLatitude(),
                locationMessage.getLongitude()
        ));
    }

    @EventMapping
    public void handleImageMessageEvent(MessageEvent<ImageMessageContent> event) throws IOException {
        // You need to install ImageMagick
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    DownloadedContent jpg = saveContent("jpg", responseBody);
                    DownloadedContent previewImg = createTempFile("jpg");
                    system(
                            "convert",
                            "-resize", "240x",
                            jpg.path.toString(),
                            previewImg.path.toString());
                    reply(((MessageEvent) event).getReplyToken(),
                          new ImageMessage(jpg.getUri(), previewImg.getUri()));
                });
    }

    @EventMapping
    public void handleAudioMessageEvent(MessageEvent<AudioMessageContent> event) throws IOException {
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    DownloadedContent mp4 = saveContent("mp4", responseBody);
                    reply(event.getReplyToken(), new AudioMessage(mp4.getUri(), 100));
                });
    }

    @EventMapping
    public void handleVideoMessageEvent(MessageEvent<VideoMessageContent> event) throws IOException {
        // You need to install ffmpeg and ImageMagick.
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    DownloadedContent mp4 = saveContent("mp4", responseBody);
                    DownloadedContent previewImg = createTempFile("jpg");
                    system("convert",
                           mp4.path + "[0]",
                           previewImg.path.toString());
                    reply(((MessageEvent) event).getReplyToken(),
                          new VideoMessage(mp4.getUri(), previewImg.uri));
                });
    }

    @EventMapping
    public void handleUnfollowEvent(UnfollowEvent event) {
        log.info("unfollowed this bot: {}", event);
    }

     @EventMapping
    public void handleFollowEvent(FollowEvent event) {
        String replyToken = event.getReplyToken();
        //this.replyText(replyToken, "アンケートを実施します");

        ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                "アンケートをはじめてもよろしいですか?\n",
                new MessageAction("OK", "OK"),
                new MessageAction("NO", "NO")
        );
        TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
      this.reply(replyToken, templateMessage);
    }

    @EventMapping
    public void handleJoinEvent(JoinEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Joined " + event.getSource());
    }

    @EventMapping
    public void handlePostbackEvent(PostbackEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Got postback data " + event.getPostbackContent().getData() + ", param " + event.getPostbackContent().getParams().toString());
    }

    @EventMapping
    public void handleBeaconEvent(BeaconEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Got beacon message " + event.getBeacon().getHwid());
    }

    @EventMapping
    public void handleOtherEvent(Event event) {
        log.info("Received message(Ignored): {}", event);
    }
    
    private void push(@NonNull Message message) {
        push(Collections.singletonList(message));
    }
    
   private void push(@NonNull List<Message> messages) {
//        TextMessage textMessage = new TextMessage("hello");
//         PushMessage pushMessage = new PushMessage(
//                 "U39a1544457d27d31218a298b0dc9c705",
//                 textMessage
//         );
//         String newRoyalUrl = createUri("/static/buttons/21jO3NZSEZL.jpg");
//         String higtUrl = createUri("/static/buttons/11hcgYLUWPL.jpg");
//        ImageMessage imageMessage = new ImageMessage(newRoyalUrl,higtUrl);
        try {
            BotApiResponse response =
                    lineMessagingClient
                            .pushMessage(new PushMessage("U39a1544457d27d31218a298b0dc9c705", messages))
                            .get();
            log.info("Sent messages: {}", response);
            } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }

    }
    
    private void pushText(@NonNull String message) {
        if (message.length() > 1000) {
            message = message.substring(0, 1000 - 2) + "……";
        }
        this.push(new TextMessage(message));
    }

    private void reply(@NonNull String replyToken, @NonNull Message message) {
        reply(replyToken, Collections.singletonList(message));
    }

    private void reply(@NonNull String replyToken, @NonNull List<Message> messages) {
        try {
            BotApiResponse apiResponse = lineMessagingClient
                    .replyMessage(new ReplyMessage(replyToken, messages))
                    .get();
            log.info("Sent messages: {}", apiResponse);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void replyText(@NonNull String replyToken, @NonNull String message) {
        if (replyToken.isEmpty()) {
            throw new IllegalArgumentException("replyToken must not be empty");
        }
        if (message.length() > 1000) {
            message = message.substring(0, 1000 - 2) + "……";
        }
        this.reply(replyToken, new TextMessage(message));
    }

    private void handleHeavyContent(String replyToken, String messageId,
                                    Consumer<MessageContentResponse> messageConsumer) {
        final MessageContentResponse response;
        try {
            response = lineMessagingClient.getMessageContent(messageId)
                                          .get();
        } catch (InterruptedException | ExecutionException e) {
            reply(replyToken, new TextMessage("Cannot get image: " + e.getMessage()));
            throw new RuntimeException(e);
        }
        messageConsumer.accept(response);
    }

    private void handleSticker(String replyToken, StickerMessageContent content) {
        reply(replyToken, new StickerMessage(
                content.getPackageId(), content.getStickerId())
        );
    }

    private void handleTextContent(String replyToken, Event event, TextMessageContent content)
            throws Exception {
        String text = content.getText();
        log.info("Got text message from {}: {}", replyToken, text);
        switch (text) {
            case "profile": {
                String userId = event.getSource().getUserId();
                if (userId != null) {
                    lineMessagingClient
                            .getProfile(userId)
                            .whenComplete((profile, throwable) -> {
                                if (throwable != null) {
                                    this.replyText(replyToken, throwable.getMessage());
                                    return;
                                }

                                this.reply(
                                        replyToken,
                                        Arrays.asList(new TextMessage(
                                                              "Display name: " + userId),
                                                      new TextMessage("Status message: "
                                                                      + profile.getStatusMessage()))
                                );

                            });
                } else {
                    this.replyText(replyToken, "Bot can't use profile API without user ID");
                }
                break;
            }
            case "image": {
               String newRoyalUrl = createUri("/static/buttons/21jO3NZSEZL.jpg");
               ImageMessage imageMessage = new ImageMessage(newRoyalUrl,newRoyalUrl);
               push(imageMessage);
               break;
            }
            case "video": {
               String video = createUri("/static/buttons/ionkesho_cm.mp4");
               String videoImage = createUri("/static/buttons/video.JPG");
               VideoMessage videoMessage = new VideoMessage(video,videoImage);
               push(videoMessage);
               break;
            }
            case "audio": {
               String video = createUri("/static/buttons/ionkesho_cm.mp4");
               AudioMessage audioMessage = new AudioMessage(video,100);
               push(audioMessage);
               break;
            }
            case "location": {
               String title = "コンテスト会場";
               String address = "〒150-0002 東京都渋谷区渋谷２丁目２１−１"; 
               double latitude = 35.65910807942215;
               double longitude = 139.70372892916203;
               LocationMessage locationMessage = new LocationMessage(title, address, latitude, longitude);
               push(locationMessage);
               break;
            }
            case "sticker": {
               String packageId = "1";
               String stickerId = "1";
               StickerMessage stickerMessage = new StickerMessage(packageId,stickerId);
               push(stickerMessage);
               stickerId = "2";
               stickerMessage = new StickerMessage(packageId,stickerId);
               push(stickerMessage);
               stickerId = "3";
               stickerMessage = new StickerMessage(packageId,stickerId);
               push(stickerMessage);
               stickerId = "4";
               stickerMessage = new StickerMessage(packageId,stickerId);
               push(stickerMessage);
               stickerId = "5";
               stickerMessage = new StickerMessage(packageId,stickerId);
               push(stickerMessage);
               break;
            }
            case "A": {
                this.replyText(replyToken, "じめじめとした日が続いていますがいかがお過ごしですか？");
                break;
            }
            case "B": {
                String newRoyalUrl = createUri("/static/buttons/21jO3NZSEZL.jpg");
                String higtUrl = createUri("/static/buttons/11hcgYLUWPL.jpg");
                String softUrl = createUri("/static/buttons/11jKc29jgaL.jpg");
                String successUrl = createUri("/static/buttons/11VjyV6RZDL.jpg");
                String peeressUrl = createUri("/static/buttons/21ij1JnxCGL.jpg");
                this.pushText("梅雨の時期にぴったりな商品を紹介します！");
                CarouselTemplate carouselTemplate = new CarouselTemplate(
                        Arrays.asList(
                                new CarouselColumn(peeressUrl, "薬用ピアレス　スプリーム", "つやつやと潤い豊かな素肌をもたらします。特に温泉蒸しタオル美容におすすめの弱酸性クリームです。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/cream05.html")
                                        
                                )),
                                 new CarouselColumn(softUrl, "ソフトローション", "お肌に潤いを与え女性特有のデリケートなお肌にも優しくなじみ、しっとり感も長く保つ保湿力のある化粧水です。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/lotion02.html")
                                        
                                )),
                               new CarouselColumn(successUrl, "薬用サクセスストーリー", "ダイズエキス・ローヤルゼリーエキス・ヒアルロン酸をベースに、天然保湿成分を加えた弱酸性の薬用化粧品。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/white01.html")
                                      
                                ))
                            ));
                TemplateMessage templateMessage = new TemplateMessage("Carousel alt text", carouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "C": {
                String newRoyalUrl = createUri("/static/buttons/21jO3NZSEZL.jpg");
                String higtUrl = createUri("/static/buttons/11hcgYLUWPL.jpg");
                String softUrl = createUri("/static/buttons/11jKc29jgaL.jpg");
                String successUrl = createUri("/static/buttons/11VjyV6RZDL.jpg");
                String peeressUrl = createUri("/static/buttons/21ij1JnxCGL.jpg");
                CarouselTemplate carouselTemplate = new CarouselTemplate(
                        Arrays.asList(
                                new CarouselColumn(successUrl, "薬用サクセスストーリー", "ダイズエキス・ローヤルゼリーエキス・ヒアルロン酸をベースに、天然保湿成分を加えた弱酸性の薬用化粧品。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/white01.html")
                                        
                                )),
                                new CarouselColumn(peeressUrl, "薬用ピアレス　スプリーム", "つやつやと潤い豊かな素肌をもたらします。特に温泉蒸しタオル美容におすすめの弱酸性クリームです。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/cream05.html")
                                        
                                )),
                               new CarouselColumn(higtUrl, "ハイローション", "さっぱりとした使いごこちで、お肌にすばやく浸透（角質層）。しっかりと潤いを与えます。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/lotion01.html")
                                      
                                ))
                            ));
                TemplateMessage templateMessage = new TemplateMessage("Carousel alt text", carouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "D": {
                this.replyText(replyToken, "梅雨もあけ、いよいよ夏本番となってまいりましたが、日焼け対策はちゃんと行っていますか？");
                break;
            }
            case "E": {
               String video = createUri("/static/buttons/ionkesho_cm.mp4");
               String videoImage = createUri("/static/buttons/video.JPG");
               VideoMessage videoMessage = new VideoMessage(video,videoImage);
               this.pushText("新しいCMが公開されました！");
               push(videoMessage);
               break;
            }
            case "F": {
                String newRoyalUrl = createUri("/static/buttons/21jO3NZSEZL.jpg");
                String higtUrl = createUri("/static/buttons/11hcgYLUWPL.jpg");
                String softUrl = createUri("/static/buttons/11jKc29jgaL.jpg");
                String successUrl = createUri("/static/buttons/11VjyV6RZDL.jpg");
                String peeressUrl = createUri("/static/buttons/21ij1JnxCGL.jpg");
                this.pushText("夏の暑い時期にぴったりな商品を紹介します！");
                CarouselTemplate carouselTemplate = new CarouselTemplate(
                        Arrays.asList(
                                new CarouselColumn(peeressUrl, "薬用ピアレス　スプリーム", "つやつやと潤い豊かな素肌をもたらします。特に温泉蒸しタオル美容におすすめの弱酸性クリームです。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/cream05.html")
                                        
                                )),
                                new CarouselColumn(softUrl, "ソフトローション", "お肌に潤いを与え女性特有のデリケートなお肌にも優しくなじみ、しっとり感も長く保つ保湿力のある化粧水です。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/lotion02.html")
                                        
                                )),
                               new CarouselColumn(newRoyalUrl, "ニューロイヤル", "イオン化粧品独自の温泉蒸しタオル美容に欠かせないアイテム。蒸しタオルによって、成分がなじみ、きめの整った素肌へ導きます。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/cream01.html")
                                        
                                ))
                            ));
                TemplateMessage templateMessage = new TemplateMessage("Carousel alt text", carouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "G": {
               String title = "コンテスト会場";
               String address = "〒150-0002 東京都渋谷区渋谷２丁目２１−１"; 
               double latitude = 35.65910807942215;
               double longitude = 139.70372892916203;
               LocationMessage locationMessage = new LocationMessage(title, address, latitude, longitude);
               this.pushText("コンテストのお知らせ\n9月に行われるコンテストの場所が決定いたしました。\n奮ってご参加ください。");
               push(locationMessage);
               break;
            }
            case "1": {
                this.replyText(replyToken, "購入ありがとうございます");
                break;
            }
            case "2": {
                ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                        "ご購入いただきました商品の使い心地はいかがでしょうか?",
                        new MessageAction("良い", "良い"),
                        new MessageAction("悪い", "悪い")
                );
                TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "良い": {
                this.replyText(replyToken, "ご回答ありがとうございます！");
                break;
            }
            case "悪い": {
                this.replyText(replyToken, "ご回答ありがとうございます！");
                break;
            }
            case "3": {
                String newRoyalUrl = createUri("/static/buttons/21jO3NZSEZL.jpg");
                String higtUrl = createUri("/static/buttons/11hcgYLUWPL.jpg");
                String softUrl = createUri("/static/buttons/11jKc29jgaL.jpg");
                String successUrl = createUri("/static/buttons/11VjyV6RZDL.jpg");
                String peeressUrl = createUri("/static/buttons/21ij1JnxCGL.jpg");
                String homeUrl = createUri("/static/buttons/images.jpg");
                CarouselTemplate carouselTemplate = new CarouselTemplate(
                        Arrays.asList(
                                new CarouselColumn(homeUrl,"商品紹介","そろそろ商品が残り少なくなってきていませんか？", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("ホームページ",
                                                "http://www.ionkesho.jp")
                                        
                                )),
                                new CarouselColumn(newRoyalUrl, "ニューロイヤル", "イオン化粧品独自の温泉蒸しタオル美容に欠かせないアイテム。蒸しタオルによって、成分がなじみ、きめの整った素肌へ導きます。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/cream01.html")
                                        
                                )),
                                new CarouselColumn(peeressUrl, "薬用ピアレス　スプリーム", "つやつやと潤い豊かな素肌をもたらします。特に温泉蒸しタオル美容におすすめの弱酸性クリームです。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/cream05.html")
                                        
                                )),
                               new CarouselColumn(softUrl, "ソフトローション", "お肌に潤いを与え女性特有のデリケートなお肌にも優しくなじみ、しっとり感も長く保つ保湿力のある化粧水です。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/lotion02.html")
                                      
                                ))
                            ));
                TemplateMessage templateMessage = new TemplateMessage("Carousel alt text", carouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "4": {
                String newRoyalUrl = createUri("/static/buttons/21jO3NZSEZL.jpg");
                String higtUrl = createUri("/static/buttons/11hcgYLUWPL.jpg");
                String softUrl = createUri("/static/buttons/11jKc29jgaL.jpg");
                String successUrl = createUri("/static/buttons/11VjyV6RZDL.jpg");
                String peeressUrl = createUri("/static/buttons/21ij1JnxCGL.jpg");
                String homeUrl = createUri("/static/buttons/images.jpg");
                CarouselTemplate carouselTemplate = new CarouselTemplate(
                        Arrays.asList(
                                new CarouselColumn(homeUrl,"新しい商品","おすすめの商品の紹介です", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("ホームページ",
                                                "http://www.ionkesho.jp")
                                        
                                )),
                                new CarouselColumn(newRoyalUrl, "ニューロイヤル", "イオン化粧品独自の温泉蒸しタオル美容に欠かせないアイテム。蒸しタオルによって、成分がなじみ、きめの整った素肌へ導きます。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/cream01.html")
                                        
                                )),
                                new CarouselColumn(peeressUrl, "薬用ピアレス　スプリーム", "つやつやと潤い豊かな素肌をもたらします。特に温泉蒸しタオル美容におすすめの弱酸性クリームです。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/cream05.html")
                                        
                                )),
                               new CarouselColumn(softUrl, "ソフトローション", "お肌に潤いを与え女性特有のデリケートなお肌にも優しくなじみ、しっとり感も長く保つ保湿力のある化粧水です。", Arrays.asList(
                                        new URIAction("電話をかける",
                                                      "tel:090XXXX6238"),
                                        new URIAction("商品ページ",
                                                "http://www.ionkesho.jp/products/list/lotion02.html")
                                      
                                ))
                            ));
                TemplateMessage templateMessage = new TemplateMessage("Carousel alt text", carouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "confirm": {
                ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                        "Do it?",
                        new MessageAction("Yes", "Yes!"),
                        new MessageAction("No", "No!")
                );
                TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
                this.push(templateMessage);
                break;
            }
            case "buttons": {
                String imageUrl = createUri("/static/buttons/1040.jpg");
                ButtonsTemplate buttonsTemplate = new ButtonsTemplate(
                        imageUrl,
                        "My button sample",
                        "Hello, my button",
                        Arrays.asList(
                                new URIAction("Go to line.me",
                                              "https://line.me"),
                                new PostbackAction("Say hello1",
                                                   "hello こんにちは"),
                                new PostbackAction("言 hello2",
                                                   "hello こんにちは",
                                                   "hello こんにちは"),
                                new MessageAction("Say message",
                                                  "Rice=米")
                        ));
                TemplateMessage templateMessage = new TemplateMessage("Button alt text", buttonsTemplate);
                this.push(templateMessage);
                break;
            }
            case "carousel": {
                String imageUrl = createUri("/static/buttons/1040.jpg");
                CarouselTemplate carouselTemplate = new CarouselTemplate(
                        Arrays.asList(
                                new CarouselColumn(imageUrl, "hoge", "fuga", Arrays.asList(
                                        new URIAction("Go to line.me",
                                                      "https://line.me"),
                                        new URIAction("Go to line.me",
                                                "https://line.me"),
                                        new PostbackAction("Say hello1",
                                                           "hello こんにちは")
                                )),
                                new CarouselColumn(imageUrl, "hoge", "fuga", Arrays.asList(
                                        new PostbackAction("言 hello2",
                                                           "hello こんにちは",
                                                           "hello こんにちは"),
                                        new PostbackAction("言 hello2",
                                                "hello こんにちは",
                                                "hello こんにちは"),
                                        new MessageAction("Say message",
                                                          "Rice=米")
                                )),
                                new CarouselColumn(imageUrl, "Datetime Picker", "Please select a date, time or datetime", Arrays.asList(
                                        new DatetimePickerAction("Datetime",
                                                "action=sel",
                                                "datetime",
                                                "2017-06-18T06:15",
                                                "2100-12-31T23:59",
                                                "1900-01-01T00:00"),
                                        new DatetimePickerAction("Date",
                                                "action=sel&only=date",
                                                "date",
                                                "2017-06-18",
                                                "2100-12-31",
                                                "1900-01-01"),
                                        new DatetimePickerAction("Time",
                                                "action=sel&only=time",
                                                "time",
                                                "06:15",
                                                "23:59",
                                                "00:00")
                                ))
                        ));
                TemplateMessage templateMessage = new TemplateMessage("Carousel alt text", carouselTemplate);
                this.push(templateMessage);
                break;
            }
            case "image_carousel": {
                String imageUrl = createUri("/static/buttons/1040.jpg");
                ImageCarouselTemplate imageCarouselTemplate = new ImageCarouselTemplate(
                        Arrays.asList(
                                new ImageCarouselColumn(imageUrl,
                                        new URIAction("Goto line.me",
                                                "https://line.me")
                                ),
                                new ImageCarouselColumn(imageUrl,
                                        new MessageAction("Say message",
                                                "Rice=米")
                                ),
                                new ImageCarouselColumn(imageUrl,
                                        new PostbackAction("言 hello2",
                                                "hello こんにちは",
                                                "hello こんにちは")
                                )
                        ));
                TemplateMessage templateMessage = new TemplateMessage("ImageCarousel alt text", imageCarouselTemplate);
                this.push(templateMessage);
                break;
            }
            case "imagemap":
                this.reply(replyToken, new ImagemapMessage(
                        createUri("/static/rich"),
                        "This is alt text",
                        new ImagemapBaseSize(1040, 1040),
                        Arrays.asList(
                                new URIImagemapAction(
                                        "https://store.line.me/family/manga/en",
                                        new ImagemapArea(
                                                0, 0, 520, 520
                                        )
                                ),
                                new URIImagemapAction(
                                        "https://store.line.me/family/music/en",
                                        new ImagemapArea(
                                                520, 0, 520, 520
                                        )
                                ),
                                new URIImagemapAction(
                                        "https://store.line.me/family/play/en",
                                        new ImagemapArea(
                                                0, 520, 520, 520
                                        )
                                ),
                                new MessageImagemapAction(
                                        "URANAI!",
                                        new ImagemapArea(
                                                520, 520, 520, 520
                                        )
                                )
                        )
                ));
                break;
            default:
                log.info("Returns echo message {}: {}", replyToken, text);
                this.replyText(
                        replyToken,
                        text
                );
                break;
        }
    }

    private static String createUri(String path) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                                          .path(path).build()
                                          .toUriString();
    }

    private void system(String... args) {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        try {
            Process start = processBuilder.start();
            int i = start.waitFor();
            log.info("result: {} =>  {}", Arrays.toString(args), i);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            log.info("Interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    private static DownloadedContent saveContent(String ext, MessageContentResponse responseBody) {
        log.info("Got content-type: {}", responseBody);

        DownloadedContent tempFile = createTempFile(ext);
        try (OutputStream outputStream = Files.newOutputStream(tempFile.path)) {
            ByteStreams.copy(responseBody.getStream(), outputStream);
            log.info("Saved {}: {}", ext, tempFile);
            return tempFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DownloadedContent createTempFile(String ext) {
        String fileName = LocalDateTime.now().toString() + '-' + UUID.randomUUID().toString() + '.' + ext;
        Path tempFile = KitchenSinkApplication.downloadedContentDir.resolve(fileName);
        tempFile.toFile().deleteOnExit();
        return new DownloadedContent(
                tempFile,
                createUri("/downloaded/" + tempFile.getFileName()));
    }

    @Value
    public static class DownloadedContent {
        Path path;
        String uri;
    }
}
