package com.cloudgallery.manager.auth;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.cloudgallery.manager.auth.model.SpaceUserAuthConfig;
import com.cloudgallery.manager.auth.model.SpaceUserPermissionConstant;
import com.cloudgallery.manager.auth.model.SpaceUserRole;
import com.cloudgallery.model.entity.Space;
import com.cloudgallery.model.entity.SpaceUser;
import com.cloudgallery.model.entity.User;
import com.cloudgallery.model.enums.SpaceRoleEnum;
import com.cloudgallery.model.enums.SpaceTypeEnum;
import com.cloudgallery.service.SpaceUserService;
import com.cloudgallery.service.UserService;
import org.checkerframework.checker.units.qual.A;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 空间成员权限管理
 */
@Component
public class SpaceUserAuthManager {

    @Resource
    private UserService userService;

    @Resource
    private SpaceUserService spaceUserService;

    public static final SpaceUserAuthConfig SPACE_USER_AUTH_CONFIG;
    //静态代码块会在类初始化时执行一次。当 SpaceUserAuthManager 类初始化时，就读取权限配置文件。
    //角色和权限的对应关系可以通过配置文件管理。

    static {
        String json = ResourceUtil.readUtf8Str("biz/spaceUserAuthConfig.json");
        //它的作用是把 JSON 字符串转换成 Java 对象。项目没有把所有角色权限直接写死在 Java 代码里，而是放在 JSON 配置文件中。
        SPACE_USER_AUTH_CONFIG = JSONUtil.toBean(json, SpaceUserAuthConfig.class);
    }

    /**
     * 根据角色获取权限列表
     *
     * @param spaceUserRole
     * @return
     */
    public List<String> getPermissionsByRole(String spaceUserRole) {
        if (StrUtil.isBlank(spaceUserRole)) {
            return new ArrayList<>();
        }
        SpaceUserRole role = SPACE_USER_AUTH_CONFIG.getRoles()
                .stream()
                .filter(r -> r.getKey().equals(spaceUserRole))
                .findFirst()
                .orElse(null);
        if (role == null) {
            return new ArrayList<>();
        }
        //返回这个角色对应的权限列表。
        return role.getPermissions();
    }


    /**
     * 获取权限列表
     *
     * @param space
     * @param loginUser
     * @return
     */
    public List<String> getPermissionList(Space space, User loginUser) {
        if (loginUser == null) {
            return new ArrayList<>();
        }
        // 管理员权限
        List<String> ADMIN_PERMISSIONS = getPermissionsByRole(SpaceRoleEnum.ADMIN.getValue());
        // 公共图库
        //因为公共图库没有指定某个私人或团队空间。
        //因此，项目用 null 表示当前访问的是公共图库。
        if (space == null) {
            if (userService.isAdmin(loginUser)) {
                return ADMIN_PERMISSIONS;
            }
            //普通用户最终只获得图片查看权限
            return Collections.singletonList(SpaceUserPermissionConstant.PICTURE_VIEW);
        }
        // 将空间类型值（如 1、2）转换为对应的枚举（PRIVATE、TEAM）
        SpaceTypeEnum spaceTypeEnum = SpaceTypeEnum.getEnumByValue(space.getSpaceType());
        // 如果空间类型不合法或无法识别，则不授予任何权限
        if (spaceTypeEnum == null) {
            return new ArrayList<>();
        }
        // 根据空间获取对应的权限
        switch (spaceTypeEnum) {
            case PRIVATE:
                // 私有空间，仅本人或管理员有所有权限
                if (space.getUserId().equals(loginUser.getId()) || userService.isAdmin(loginUser)) {
                    return ADMIN_PERMISSIONS;
                } else {
                    return new ArrayList<>();
                }
            case TEAM:
                // 团队空间，查询 SpaceUser 并获取角色和权限——先确认成员身份，再根据成员角色获取权限。
                SpaceUser spaceUser = spaceUserService.lambdaQuery()
                        .eq(SpaceUser::getSpaceId, space.getId())
                        .eq(SpaceUser::getUserId, loginUser.getId())
                        .one();
                if (spaceUser == null) {
                    return new ArrayList<>();
                } else {
                    return getPermissionsByRole(spaceUser.getSpaceRole());
                }
        }
        return new ArrayList<>();
    }
}
