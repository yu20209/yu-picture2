<template>
  <div id="globalSider">
    <a-layout-sider
      v-if="loginUserStore.loginUser.id"
      width="200"
      breakpoint="lg"
      collapsed-width="0"
    >
      <a-menu
        v-model:selectedKeys="current"
        mode="inline"
        :items="menuItems"
        @click="doMenuClick"
      />
    </a-layout-sider>
  </div>
</template>
<script lang="ts" setup>
import { computed, h, ref, watch, watchEffect } from 'vue'
import { PictureOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { SPACE_TYPE_ENUM } from '@/constants/space.ts'
import { listMyTeamSpaceUsingPost } from '@/api/spaceUserController.ts'
import { message } from 'ant-design-vue'

const loginUserStore = useLoginUserStore()
const router = useRouter()

// 固定的菜单列表
const fixedMenuItems = [
  {
    key: '/',
    icon: () => h(PictureOutlined),
    label: '公共图库',
  },
  {
    key: '/my_space',
    label: '我的空间',
    icon: () => h(UserOutlined),
  },
  {
    key: '/add_space?type=' + SPACE_TYPE_ENUM.TEAM,
    label: '创建团队',
    icon: () => h(TeamOutlined),
  },
]

const teamSpaceList = ref<API.SpaceUserVO[]>([])
const menuItems = computed(() => {
  // 固定菜单：公共图库 + 我的空间
  const fixed = fixedMenuItems.slice(0, 2)

  // 团队分组：创建团队 + 用户的所有团队空间（owner + 成员）
  const teamChildren = [
    // 创建团队入口
    {
      key: '/add_space?type=' + SPACE_TYPE_ENUM.TEAM,
      label: '创建团队',
      icon: () => h(TeamOutlined),
    },
    // 用户加入的团队空间
    ...teamSpaceList.value.map((spaceUser) => ({
      key: '/space/' + spaceUser.spaceId,
      label: spaceUser.space?.spaceName,
    })),
  ]

  // 如果没有团队空间，仍然显示「创建团队」入口
  if (teamSpaceList.value.length < 1) {
    return [...fixed, { type: 'group', label: '我的团队', key: 'team-group', children: teamChildren }]
  }

  return [
    ...fixed,
    { type: 'group', label: '我的团队', key: 'team-group', children: teamChildren },
  ]
})

// 加载团队空间列表
const fetchTeamSpaceList = async () => {
  const res = await listMyTeamSpaceUsingPost()
  if (res.data.code === 0 && res.data.data) {
    teamSpaceList.value = res.data.data
  } else {
    message.error('加载我的团队空间失败，' + res.data.message)
  }
}

/**
 * 监听变量，改变时触发数据的重新加载
 */
watchEffect(() => {
  // 登录才加载
  if (loginUserStore.loginUser.id) {
    fetchTeamSpaceList()
  }
})

// 监听路由变化：每次切路由时都刷新团队空间列表
// 这样创建空间后、加成员后等场景，侧边栏能及时显示最新数据
watch(
  () => router.currentRoute.value.fullPath,
  () => {
    if (loginUserStore.loginUser.id) {
      fetchTeamSpaceList()
    }
  }
)

// 当前要高亮的菜单项
const current = ref<string[]>([])
// 监听路由变化，更新高亮菜单项
router.afterEach((to, from, next) => {
  current.value = [to.path]
})

// 路由跳转事件
const doMenuClick = ({ key }) => {
  router.push(key)
}
</script>

<style scoped>
#globalSider .ant-layout-sider {
  background: none;
}
</style>
