package com.cloudgallery.manager.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import javax.annotation.Resource;

/**
 * WebSocket 配置（定义连接）WebSocketConfig 只负责配置连接入口，自己不处理图片编辑业务。
 * 它把工作分给两个类：
 * - WsHandshakeInterceptor：决定能不能连接。
 * - PictureEditHandler：连接成功后负责接收、处理和发送消息。
 */
@Configuration
@EnableWebSocket//启用 WebSocket 支持。
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private PictureEditHandler pictureEditHandler;

    @Resource
    private WsHandshakeInterceptor wsHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        //当客户端连接 /ws/picture/edit 时，由 PictureEditHandler 处理这个连接及其后续消息。
        registry.addHandler(pictureEditHandler, "/ws/picture/edit")
                //连接建立前，先执行拦截器。例如检查用户是否登录、是否有权限编辑图片。校验不通过，就拒绝连接。
                .addInterceptors(wsHandshakeInterceptor)
                //允许来自不同来源的网页发起 WebSocket 握手请求。
                .setAllowedOrigins("*");
    }
}
