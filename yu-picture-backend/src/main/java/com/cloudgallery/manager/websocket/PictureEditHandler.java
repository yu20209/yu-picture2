package com.cloudgallery.manager.websocket;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.cloudgallery.manager.websocket.disruptor.PictureEditEventProducer;
import com.cloudgallery.manager.websocket.model.PictureEditActionEnum;
import com.cloudgallery.manager.websocket.model.PictureEditMessageTypeEnum;
import com.cloudgallery.manager.websocket.model.PictureEditRequestMessage;
import com.cloudgallery.manager.websocket.model.PictureEditResponseMessage;
import com.cloudgallery.model.entity.User;
import com.cloudgallery.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片编辑 WebSocket 处理器
 */
@Component
@Slf4j
public class PictureEditHandler extends TextWebSocketHandler {//TextWebSocketHandler 是 Spring 提供的 WebSocket 文本消息处理器。项目使用它处理 JSON 格式的文本消息。

    @Resource
    private UserService userService;

    @Resource
    @Lazy//
    private PictureEditEventProducer pictureEditEventProducer;

    // 每张图片的编辑状态，key: pictureId, value: 当前正在编辑的用户 ID
    //它保存的是：图片 ID → 当前正在编辑这张图片的用户 ID。后端就可以通过这个 Map 判断图片是否已经有人占用。
    private final Map<Long, Long> pictureEditingUsers = new ConcurrentHashMap<>();

    // 保存所有连接的会话，key: pictureId, value: 用户会话集合
    //图片 ID → 这张图片对应的所有 WebSocket 连接
    private final Map<Long, Set<WebSocketSession>> pictureSessions = new ConcurrentHashMap<>();

    /**
     * 连接建立成功
     *这段是 WebSocket 图片协同编辑的"连接建立成功后回调"——
     * 每当有一个用户通过握手校验、和服务器建立好 WebSocket 长连接，Spring 就会调用一次 afterConnectionEstablished。
     * 方法做两件事：① 把这个会话登记到"该图片的会话集合"里；
     * ② 通知其他正在围观的人"某某加入了编辑"。
     * @param session
     * @throws Exception
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        // 保存会话到集合中
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        pictureSessions.putIfAbsent(pictureId, ConcurrentHashMap.newKeySet());
        //将当前用户的 Session 加入集合。
        pictureSessions.get(pictureId).add(session);
        // 构造响应，发送加入编辑的消息通知
        //这里的 PictureEditResponseMessage 专门用于 WebSocket 服务端向前端发送消息。
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
        String message = String.format("用户 %s 加入编辑", user.getUserName());
        //分别设置消息内容和用户信息。
        pictureEditResponseMessage.setMessage(message);
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        // 广播给所有用户
        broadcastToPicture(pictureId, pictureEditResponseMessage);
    }

    /**
     * 收到前端发送的消息，根据消息类别处理消息
     *
     * @param session
     * @param message
     * @throws Exception
     */
    @Override
    //TextMessage 是 Spring WebSocket 提供的文本消息对象。前端发送的 JSON 内容就在里面。message.getPayload()可以获取消息正文。
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        super.handleTextMessage(session, message);
        // 获取消息内容，将 JSON 转换为 PictureEditRequestMessage{包括消息类型、编辑动作}
            //为什么要转换？
            //因为 Java 后端需要通过对象的 getter 获取数据：
        PictureEditRequestMessage pictureEditRequestMessage = JSONUtil.toBean(message.getPayload(), PictureEditRequestMessage.class);
        // 从 Session 属性中获取到公共参数
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        // 根据消息类型处理消息（生产消息到 Disruptor 环形队列中）
        pictureEditEventProducer.publishEvent(pictureEditRequestMessage, session, user, pictureId);
    }

    /**
     * 进入编辑状态
     *
     * @param pictureEditRequestMessage
     * @param session
     * @param user
     * @param pictureId
     */
    public void handleEnterEditMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        // 1.判断图片是否已经有人编辑
        if (!pictureEditingUsers.containsKey(pictureId)) {
            // 2.设置用户正在编辑该图片
            pictureEditingUsers.put(pictureId, user.getId());
            // 3.构造响应，发送加入编辑的消息通知
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.ENTER_EDIT.getValue());
            String message = String.format("用户 %s 开始编辑图片", user.getUserName());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            // 4.广播给所有用户
            broadcastToPicture(pictureId, pictureEditResponseMessage);
        }
    }

    /**
     * 处理编辑操作
     *
     * @param pictureEditRequestMessage
     * @param session
     * @param user
     * @param pictureId
     */
    public void handleEditActionMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        // 第一步：查找当前编辑者
        Long editingUserId = pictureEditingUsers.get(pictureId);
        // 第二步：获取编辑动作
        String editAction = pictureEditRequestMessage.getEditAction();
        //第三步：验证编辑动作是否合法
        PictureEditActionEnum actionEnum = PictureEditActionEnum.getEnumByValue(editAction);
        if (actionEnum == null) {
            log.error("无效的编辑动作");
            return;
        }
        // 第四步：判断是不是当前编辑者——
        // 第一个:editingUserId != null说明当前图片确实有人取得了编辑权。
        //第二个：editingUserId.equals(user.getId())说明发送消息的人就是当前编辑者。
        if (editingUserId != null && editingUserId.equals(user.getId())) {
            // 第五步：构造编辑动作通知
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EDIT_ACTION.getValue());
            String message = String.format("%s 执行 %s", user.getUserName(), actionEnum.getText());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setEditAction(editAction);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            // 第六步：广播给其他用户_session代表因此只通知其他用户，不通知发送者。
            broadcastToPicture(pictureId, pictureEditResponseMessage, session);
        }
    }


    /**
     * 退出编辑状态
     *
     * @param pictureEditRequestMessage
     * @param session
     * @param user
     * @param pictureId
     */
    public void handleExitEditMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        // 正在编辑的用户
        Long editingUserId = pictureEditingUsers.get(pictureId);
        // 确认是当前的编辑者
        if (editingUserId != null && editingUserId.equals(user.getId())) {
            // 移除用户正在编辑该图片
            pictureEditingUsers.remove(pictureId);
            // 构造响应，发送退出编辑的消息通知
            PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
            pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EXIT_EDIT.getValue());
            String message = String.format("用户 %s 退出编辑图片", user.getUserName());
            pictureEditResponseMessage.setMessage(message);
            pictureEditResponseMessage.setUser(userService.getUserVO(user));
            broadcastToPicture(pictureId, pictureEditResponseMessage);
        }
    }

    /**
     * 关闭连接
     *
     * @param session
     * @param status
     * @throws Exception
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        super.afterConnectionClosed(session, status);
        // 从 Session 属性中获取到公共参数
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        // 调用前面的方法，移除当前用户的编辑状态
        handleExitEditMessage(null, session, user, pictureId);
        // 删除会话
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        if (sessionSet != null) {
            sessionSet.remove(session);
            //如果这张图片已经没有任何用户连接，就把图片 ID 对应的整个集合也从 Map 中删除。
            if (sessionSet.isEmpty()) {
                pictureSessions.remove(pictureId);
            }
        }
        // 通知其他用户，该用户已经离开编辑
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
        String message = String.format("用户 %s 离开编辑", user.getUserName());
        pictureEditResponseMessage.setMessage(message);
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        broadcastToPicture(pictureId, pictureEditResponseMessage);
    }

    /**
     * 广播给该图片的所有用户（支持排除掉某个 Session）
     *
     * @param pictureId
     * @param pictureEditResponseMessage
     * @param excludeSession
     */
    private void broadcastToPicture(Long pictureId, PictureEditResponseMessage pictureEditResponseMessage, WebSocketSession excludeSession) throws IOException {
        //1. 获取这张图片的所有连接
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        //2. 判断有没有连接
        if (CollUtil.isNotEmpty(sessionSet)) {
            // 3.创建 ObjectMapper——Java 对象不能直接通过 WebSocket 发送给前端。需要先转换成 JSON 字符串。
            ObjectMapper objectMapper = new ObjectMapper();
            // 4.配置序列化：将 Long 类型转为 String，解决丢失精度问题
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance); // 支持 long 基本类型
            objectMapper.registerModule(module);
            // 5.序列化为 JSON 字符串，现在把字符串包装成 WebSocket 可以发送的消息对象。
            String message = objectMapper.writeValueAsString(pictureEditResponseMessage);
            TextMessage textMessage = new TextMessage(message);
            //6. 遍历所有连接
            for (WebSocketSession session : sessionSet) {
                // 7.排除掉的 session 不发送
                if (excludeSession != null && session.equals(excludeSession)) {
                    continue;
                }
                //8. 真正发送消息
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }
        }
    }

    /**
     * 广播给该图片的所有用户
     *
     * @param pictureId
     * @param pictureEditResponseMessage
     */
    private void broadcastToPicture(Long pictureId, PictureEditResponseMessage pictureEditResponseMessage) throws IOException {
        broadcastToPicture(pictureId, pictureEditResponseMessage, null);
    }
}
















