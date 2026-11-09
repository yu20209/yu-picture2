package com.cloudgallery.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.cloudgallery.exception.BusinessException;
import com.cloudgallery.exception.ErrorCode;
import com.cloudgallery.exception.ThrowUtils;
import com.cloudgallery.manager.PictureFileCleaner;
import com.cloudgallery.manager.cache.PictureCacheManager;
import com.cloudgallery.manager.sharding.DynamicShardingManager;
import com.cloudgallery.mapper.PictureMapper;
import com.cloudgallery.model.dto.space.SpaceAddRequest;
import com.cloudgallery.model.dto.space.SpaceQueryRequest;
import com.cloudgallery.model.entity.Picture;
import com.cloudgallery.model.entity.Space;
import com.cloudgallery.model.entity.SpaceUser;
import com.cloudgallery.model.entity.User;
import com.cloudgallery.model.enums.SpaceLevelEnum;
import com.cloudgallery.model.enums.SpaceRoleEnum;
import com.cloudgallery.model.enums.SpaceTypeEnum;
import com.cloudgallery.model.vo.SpaceVO;
import com.cloudgallery.model.vo.UserVO;
import com.cloudgallery.service.SpaceService;
import com.cloudgallery.mapper.SpaceMapper;
import com.cloudgallery.service.SpaceUserService;
import com.cloudgallery.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author CloudGallery
 * @description 针对表【space(空间)】的数据库操作Service实现
 * @createDate 2024-12-18 19:53:34
 */
@Service
public class SpaceServiceImpl extends ServiceImpl<SpaceMapper, Space>
        implements SpaceService {

    @Resource
    private UserService userService;

    @Resource
    private SpaceUserService spaceUserService;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private PictureMapper pictureMapper;

    @Resource
    private PictureFileCleaner pictureFileCleaner;

    @Resource
    private PictureCacheManager pictureCacheManager;

    // 为了方便部署，注释掉分表
//    @Resource
//    @Lazy
//    private DynamicShardingManager dynamicShardingManager;

    /**
     * 创建空间
     *
     * @param spaceAddRequest
     * @param loginUser
     * @return
     */
    @Override
    public long addSpace(SpaceAddRequest spaceAddRequest, User loginUser) {

        // 转换实体类和 DTO
        Space space = new Space();
        //将两个对象中名称相同、类型兼容的属性复制过去。
            //SpaceAddRequest	接收前端提交的创建空间参数——SpringMVC 将 JSON 转换成 SpaceAddRequest 对象。
            //Space	数据库实体对象，表示数据库中的空间记录
        BeanUtils.copyProperties(spaceAddRequest, space);
        // 1. 填充参数默认值
        // 填充空间名称
        if (StrUtil.isBlank(space.getSpaceName())) {
            space.setSpaceName("默认空间");
        }
        // 填充空间级别common——枚举类 Enum
        if (space.getSpaceLevel() == null) {
            space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        }
        // 填充空间类别private
        if (space.getSpaceType() == null) {
            space.setSpaceType(SpaceTypeEnum.PRIVATE.getValue());
        }
        // 填充容量和大小
        this.fillSpaceBySpaceLevel(space);
        // 2. 校验参数
        this.validSpace(space, true);
        // 3. 校验权限，非管理员只能创建普通级别的空间
        //将当前登录用户的 ID 设置为新空间的所属用户 ID。
        Long userId = loginUser.getId();
        space.setUserId(userId);
        //空间级别：非普通，但是用户不是管理员，拒绝
        if (SpaceLevelEnum.COMMON.getValue() != space.getSpaceLevel() && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限创建指定级别的空间");
        }
        // 4. 控制同一用户只能创建一个私有空间、以及一个团队空间
          //intern() 可以先理解为：让相同内容的字符串取得字符串池中的同一个引用。
        String lock = String.valueOf(userId).intern();
        synchronized (lock) {
            Long newSpaceId = transactionTemplate.execute(status -> {
                // 判断是否已有空间
                boolean exists = this.lambdaQuery()
                        .eq(Space::getUserId, userId)
                        .eq(Space::getSpaceType, space.getSpaceType())
                        .exists();
                // 如果已有空间，就不能再创建——每个用户每类空间只能创建一
                ThrowUtils.throwIf(exists, ErrorCode.OPERATION_ERROR, "每个用户每类空间只能创建一个");
                // 创建——将 Java 实体对象保存到数据库。
                boolean result = this.save(space);
                ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "保存空间到数据库失败");
                // 创建成功后，如果是团队空间，关联新增团队成员记录
                    /*空间类型有两种（SpaceTypeEnum）：
                    private（私有空间）：只有本人能用，不需要成员表 → 直接跳过
                    team（团队空间）：可以邀请别人协作，需要一张关系表来记录"谁属于哪个空间、担任什么角色"
                    */
                if (SpaceTypeEnum.TEAM.getValue() == space.getSpaceType()) {
                    SpaceUser spaceUser = new SpaceUser();
                    spaceUser.setSpaceId(space.getId());
                    spaceUser.setUserId(userId);
                    //创建团队空间的人，自动成为这个团队空间的管理员。
                    spaceUser.setSpaceRole(SpaceRoleEnum.ADMIN.getValue());
                    result = spaceUserService.save(spaceUser);
                    ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建团队成员记录失败");
                }
//                // 创建分表（仅对团队空间生效）为方便部署，暂时不使用
//                dynamicShardingManager.createSpacePictureTable(space);
                // 返回新写入的数据 id,作为newSpaceId的返回结果
                return space.getId();
            });
            /*相当于
            if (newSpaceId != null) {
                return newSpaceId;
            } else {
                return -1L;
            **/
            return Optional.ofNullable(newSpaceId).orElse(-1L);
        }
    }

    @Override
    public void validSpace(Space space, boolean add) {
        ThrowUtils.throwIf(space == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        String spaceName = space.getSpaceName();
        Integer spaceLevel = space.getSpaceLevel();
        //把数字转换成对应的枚举对象。
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(spaceLevel);
        Integer spaceType = space.getSpaceType();
        SpaceTypeEnum spaceTypeEnum = SpaceTypeEnum.getEnumByValue(spaceType);
        // 创建时校验,add 是一个 boolean 参数,为true时代表当前正在执行新增空间操作。
        if (add) {
            if (StrUtil.isBlank(spaceName)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名称不能为空");
            }
            if (spaceLevel == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不能为空");
            }
            if (spaceType == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间类别不能为空");
            }
        }
        // 修改数据时，空间名称进行校验
        if (StrUtil.isNotBlank(spaceName) && spaceName.length() > 30) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名称过长");
        }
        // 修改数据时，空间级别进行校验
        if (spaceLevel != null && spaceLevelEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不存在");
        }
        // 修改数据时，空间类别进行校验
        if (spaceType != null && spaceTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间类别不存在");
        }
    }

    @Override
    public SpaceVO getSpaceVO(Space space, HttpServletRequest request) {
        // 对象转封装类
        SpaceVO spaceVO = SpaceVO.objToVo(space);
        // 关联查询用户信息
        Long userId = space.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            spaceVO.setUser(userVO);
        }
        return spaceVO;
    }

    @Override
    public Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, HttpServletRequest request) {
        List<Space> spaceList = spacePage.getRecords();
        Page<SpaceVO> spaceVOPage = new Page<>(spacePage.getCurrent(), spacePage.getSize(), spacePage.getTotal());
        if (CollUtil.isEmpty(spaceList)) {
            return spaceVOPage;
        }
        // 对象列表 => 封装对象列表
        List<SpaceVO> spaceVOList = spaceList.stream()
                .map(SpaceVO::objToVo)
                .collect(Collectors.toList());
        // 1. 关联查询用户信息
        // 1,2,3,4
        Set<Long> userIdSet = spaceList.stream().map(Space::getUserId).collect(Collectors.toSet());
        // 1 => user1, 2 => user2
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        spaceVOList.forEach(spaceVO -> {
            Long userId = spaceVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            spaceVO.setUser(userService.getUserVO(user));
        });
        spaceVOPage.setRecords(spaceVOList);
        return spaceVOPage;
    }

    @Override
    public QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest) {
        QueryWrapper<Space> queryWrapper = new QueryWrapper<>();
        if (spaceQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = spaceQueryRequest.getId();
        Long userId = spaceQueryRequest.getUserId();
        String spaceName = spaceQueryRequest.getSpaceName();
        Integer spaceLevel = spaceQueryRequest.getSpaceLevel();
        Integer spaceType = spaceQueryRequest.getSpaceType();
        String sortField = spaceQueryRequest.getSortField();
        String sortOrder = spaceQueryRequest.getSortOrder();
        // 拼接查询条件
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(spaceName), "spaceName", spaceName);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceLevel), "spaceLevel", spaceLevel);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceType), "spaceType", spaceType);
        // 排序
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }

    //根据空间等级，自动设置空间的容量上限和图片数量上限。
    @Override
    public void fillSpaceBySpaceLevel(Space space) {
        //根据等级获取枚举对象
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(space.getSpaceLevel());
        if (spaceLevelEnum != null) {
            //获取该等级的最大容量
            long maxSize = spaceLevelEnum.getMaxSize();
            if (space.getMaxSize() == null) {
                //设置空间最大容量
                space.setMaxSize(maxSize);
            }
            //获取最大图片数量
            long maxCount = spaceLevelEnum.getMaxCount();
            if (space.getMaxCount() == null) {
                space.setMaxCount(maxCount);
            }
        }
    }

    @Override
    public void checkSpaceAuth(User loginUser, Space space) {
        // 仅本人或管理员可编辑
        if (!space.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    @Override
    public void deleteSpace(long spaceId, User loginUser) {
        ThrowUtils.throwIf(spaceId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        // 判断空间是否存在
        Space oldSpace = this.getById(spaceId);
        ThrowUtils.throwIf(oldSpace == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        // 校验权限
        this.checkSpaceAuth(loginUser, oldSpace);
        // 先查出空间下的全部图片，事务提交后再清理物理文件
        List<Picture> pictureList = pictureMapper.selectList(
                new LambdaQueryWrapper<Picture>().eq(Picture::getSpaceId, spaceId));
        // 开启事务：删除空间 + 删除（逻辑删除）空间下的图片
        transactionTemplate.execute(status -> {
            boolean result = this.removeById(spaceId);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除空间失败");
            if (CollUtil.isNotEmpty(pictureList)) {
                List<Long> pictureIdList = pictureList.stream()
                        .map(Picture::getId)
                        .collect(Collectors.toList());
                int deleteCount = pictureMapper.deleteBatchIds(pictureIdList);
                ThrowUtils.throwIf(deleteCount <= 0, ErrorCode.OPERATION_ERROR, "删除空间下的图片失败");
            }
            return true;
        });
        // 事务提交后异步清理图片文件（先做引用检查，仍被其他记录引用的文件会保留）
        pictureList.forEach(picture -> {
            pictureFileCleaner.asyncCleanIfUnreferenced(picture.getUrl(), null);
            String thumbnailUrl = picture.getThumbnailUrl();
            if (StrUtil.isNotBlank(thumbnailUrl) && !StrUtil.equals(thumbnailUrl, picture.getUrl())) {
                pictureFileCleaner.asyncCleanIfUnreferenced(thumbnailUrl, null);
            }
        });
        // 失效该空间的图片列表缓存
        pictureCacheManager.evictBySpaceId(spaceId);
    }
}


