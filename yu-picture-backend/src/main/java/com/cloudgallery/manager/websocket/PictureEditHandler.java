package com.cloudgallery.manager.websocket;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.cloudgallery.manager.websocket.disruptor.PictureEditEventProducer;
import com.cloudgallery.manager.websocket.model.PictureEditActionEnum;
import com.cloudgallery.manager.websocket.model.PictureEditLock;
import com.cloudgallery.manager.websocket.model.PictureEditMessageTypeEnum;
import com.cloudgallery.manager.websocket.model.PictureEditRequestMessage;
import com.cloudgallery.manager.websocket.model.PictureEditResponseMessage;
import com.cloudgallery.model.entity.User;
import com.cloudgallery.service.UserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.concurrent.TimeUnit;

/**
 * 图片编辑 WebSocket 处理器
 */
@Component
@Slf4j
public class PictureEditHandler extends TextWebSocketHandler {

    /**
     * 编辑锁的最长空闲时间，超过后自动释放，避免用户异常掉线导致图片被永久锁定
     */
    private static final long EDIT_LOCK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

    /**
     * 复用的 ObjectMapper（线程安全），避免每次广播都重新创建
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        OBJECT_MAPPER.registerModule(module);
    }

    @Resource
    private UserService userService;

    @Resource
    @Lazy
    private PictureEditEventProducer pictureEditEventProducer;

    /**
     * 每张图片的编辑锁，key: pictureId, value: 当前编辑者及其会话
     */
    private final Map<Long, PictureEditLock> pictureEditingUsers = new ConcurrentHashMap<>();

    /**
     * 保存所有连接的会话，key: pictureId, value: 用户会话集合
     */
    private final Map<Long, Set<WebSocketSession>> pictureSessions = new ConcurrentHashMap<>();

    /**
     * 连接建立成功
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        pictureSessions.putIfAbsent(pictureId, ConcurrentHashMap.newKeySet());
        pictureSessions.get(pictureId).add(session);
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
        pictureEditResponseMessage.setMessage(String.format("用户 %s 加入编辑", user.getUserName()));
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        broadcastToPicture(pictureId, pictureEditResponseMessage);
    }

    /**
     * 收到前端发送的消息，根据消息类别处理消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        super.handleTextMessage(session, message);
        PictureEditRequestMessage pictureEditRequestMessage = JSONUtil.toBean(message.getPayload(), PictureEditRequestMessage.class);
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        // 生产消息到 Disruptor 环形队列中，由消费者线程串行处理
        pictureEditEventProducer.publishEvent(pictureEditRequestMessage, session, user, pictureId);
    }

    /**
     * 进入编辑状态
     */
    public void handleEnterEditMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        // putIfAbsent 是原子操作，避免 containsKey + put 的"检查再执行"竞态
        PictureEditLock newLock = new PictureEditLock(user.getId(), session.getId(), System.currentTimeMillis());
        PictureEditLock existingLock = pictureEditingUsers.putIfAbsent(pictureId, newLock);
        if (existingLock != null) {
            // 已有编辑者：只通知当前申请者，不影响正在编辑的用户
            PictureEditResponseMessage responseMessage = new PictureEditResponseMessage();
            responseMessage.setType(PictureEditMessageTypeEnum.ERROR.getValue());
            String message = existingLock.getUserId().equals(user.getId())
                    ? "当前图片正在你的另一个窗口中被编辑，请关闭其他编辑窗口后重试"
                    : "当前图片已有其他用户正在编辑，请稍后再试";
            responseMessage.setMessage(message);
            responseMessage.setUser(userService.getUserVO(user));
            sendToSession(session, responseMessage);
            return;
        }
        // 抢锁成功，广播进入编辑状态
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.ENTER_EDIT.getValue());
        pictureEditResponseMessage.setMessage(String.format("用户 %s 开始编辑图片", user.getUserName()));
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        broadcastToPicture(pictureId, pictureEditResponseMessage);
    }

    /**
     * 处理编辑操作
     */
    public void handleEditActionMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        PictureEditActionEnum actionEnum = PictureEditActionEnum.getEnumByValue(pictureEditRequestMessage.getEditAction());
        if (actionEnum == null) {
            log.error("无效的编辑动作");
            return;
        }
        PictureEditLock lock = pictureEditingUsers.get(pictureId);
        // 必须同时匹配用户和会话，避免同一用户的另一个窗口越权操作
        if (lock == null || !lock.getUserId().equals(user.getId()) || !lock.getSessionId().equals(session.getId())) {
            return;
        }
        // 编辑动作代表仍在活跃编辑，刷新锁的空闲时间
        lock.setLastActiveTime(System.currentTimeMillis());
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EDIT_ACTION.getValue());
        pictureEditResponseMessage.setMessage(String.format("%s 执行 %s", user.getUserName(), actionEnum.getText()));
        pictureEditResponseMessage.setEditAction(pictureEditRequestMessage.getEditAction());
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        broadcastToPicture(pictureId, pictureEditResponseMessage, session);
    }

    /**
     * 退出编辑状态
     */
    public void handleExitEditMessage(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) throws IOException {
        PictureEditLock lock = pictureEditingUsers.get(pictureId);
        if (lock == null || session == null) {
            return;
        }
        // 只有持锁的用户 + 同一个会话才能释放锁
        if (!lock.getUserId().equals(user.getId()) || !lock.getSessionId().equals(session.getId())) {
            return;
        }
        // 条件删除：只有锁仍是刚才读到的那个实例时才移除，避免误删别人刚抢到的锁
        if (!pictureEditingUsers.remove(pictureId, lock)) {
            return;
        }
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.EXIT_EDIT.getValue());
        pictureEditResponseMessage.setMessage(String.format("用户 %s 退出编辑图片", user.getUserName()));
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        broadcastToPicture(pictureId, pictureEditResponseMessage);
    }

    /**
     * 关闭连接
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        super.afterConnectionClosed(session, status);
        User user = (User) session.getAttributes().get("user");
        Long pictureId = (Long) session.getAttributes().get("pictureId");
        // 退出编辑也投递到 Disruptor，保证所有锁操作都在同一个消费者线程中串行执行
        PictureEditRequestMessage exitMessage = new PictureEditRequestMessage();
        exitMessage.setType(PictureEditMessageTypeEnum.EXIT_EDIT.getValue());
        pictureEditEventProducer.publishEvent(exitMessage, session, user, pictureId);
        // 删除会话
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        if (sessionSet != null) {
            sessionSet.remove(session);
            if (sessionSet.isEmpty()) {
                pictureSessions.remove(pictureId);
            }
        }
        // 通知其他用户，该用户已经离开编辑
        PictureEditResponseMessage pictureEditResponseMessage = new PictureEditResponseMessage();
        pictureEditResponseMessage.setType(PictureEditMessageTypeEnum.INFO.getValue());
        pictureEditResponseMessage.setMessage(String.format("用户 %s 离开编辑", user.getUserName()));
        pictureEditResponseMessage.setUser(userService.getUserVO(user));
        broadcastToPicture(pictureId, pictureEditResponseMessage);
    }

    /**
     * 定时释放超时未活跃的编辑锁
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void releaseExpiredEditLocks() {
        long now = System.currentTimeMillis();
        pictureEditingUsers.forEach((pictureId, lock) -> {
            if (now - lock.getLastActiveTime() < EDIT_LOCK_TIMEOUT_MS) {
                return;
            }
            if (!pictureEditingUsers.remove(pictureId, lock)) {
                return;
            }
            log.info("图片 {} 的编辑锁已超时释放，原编辑者 userId = {}", pictureId, lock.getUserId());
            PictureEditResponseMessage responseMessage = new PictureEditResponseMessage();
            responseMessage.setType(PictureEditMessageTypeEnum.EXIT_EDIT.getValue());
            responseMessage.setMessage("编辑超时，已自动释放编辑权");
            broadcastToPicture(pictureId, responseMessage);
        });
    }

    /**
     * 广播给该图片的所有用户（支持排除掉某个 Session）
     */
    private void broadcastToPicture(Long pictureId, PictureEditResponseMessage pictureEditResponseMessage, WebSocketSession excludeSession) {
        Set<WebSocketSession> sessionSet = pictureSessions.get(pictureId);
        if (CollUtil.isEmpty(sessionSet)) {
            return;
        }
        TextMessage textMessage;
        try {
            textMessage = new TextMessage(OBJECT_MAPPER.writeValueAsString(pictureEditResponseMessage));
        } catch (JsonProcessingException e) {
            log.error("WebSocket 消息序列化失败", e);
            return;
        }
        for (WebSocketSession session : sessionSet) {
            if (excludeSession != null && session.equals(excludeSession)) {
                continue;
            }
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(textMessage);
            } catch (IOException e) {
                // 单个连接发送失败不影响其他连接
                log.warn("WebSocket 消息发送失败，sessionId = {}", session.getId(), e);
            }
        }
    }

    /**
     * 广播给该图片的所有用户
     */
    private void broadcastToPicture(Long pictureId, PictureEditResponseMessage pictureEditResponseMessage) {
        broadcastToPicture(pictureId, pictureEditResponseMessage, null);
    }

    /**
     * 只给指定会话发送消息
     */
    private void sendToSession(WebSocketSession session, PictureEditResponseMessage pictureEditResponseMessage) throws IOException {
        if (session == null || !session.isOpen()) {
            return;
        }
        session.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(pictureEditResponseMessage)));
    }
}
