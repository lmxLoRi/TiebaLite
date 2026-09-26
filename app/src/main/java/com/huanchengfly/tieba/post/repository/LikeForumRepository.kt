package com.huanchengfly.tieba.post.repository

import androidx.compose.runtime.Immutable
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.UserLikeForumBean
import com.huanchengfly.tieba.post.api.models.protos.User
import com.huanchengfly.tieba.post.api.models.protos.profile.ProfileResponse
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.firstOrNull

/** 关注吧列表最多翻多少页（每页 50） */
private const val MAX_FORUM_PAGES = 6

/**
 * 获取用户「关注的吧」。
 *
 * 用户把关注的吧设为隐藏时 `/c/f/forum/like` 会返回空列表，此时用两处数据兜底恢复**部分**内容：
 * ① 资料页的 `User.likeForum`（带吧 id）；
 * ② 网页版面板 `/home/get/panel` 的 `honor.grade`（按吧内等级分组，只有吧名）。
 */
object LikeForumRepository {

    /**
     * 筛选下拉用的一项。[id] 可能为空——面板只提供吧名，拿不到 id 的只能按名字筛选。
     */
    @Immutable
    data class Entry(
        val id: Long?,
        val name: String,
    )

    /** 分页拉取该用户关注的全部吧；被隐藏时返回空列表 */
    suspend fun loadAll(uid: Long): List<UserLikeForumBean.ForumBean> {
        val collected = mutableListOf<UserLikeForumBean.ForumBean>()
        var currentPage = 1
        var hasMore = true
        while (hasMore && currentPage <= MAX_FORUM_PAGES) {
            val bean = TiebaApi.getInstance()
                .userLikeForumFlow(uid.toString(), currentPage)
                .firstOrNull() ?: break
            collected += bean.forumList.forumList
            hasMore = bean.hasMore == "1"
            currentPage++
        }
        return collected.distinctBy { it.id }
    }

    /** 关注吧被隐藏时的兜底数据，可能不完整 */
    suspend fun loadHidden(uid: Long): HiddenLikeForum? {
        val sources = loadSources(uid) ?: return null
        val gradedForums = sources.grade
            .map { (level, group) -> GradeGroup(level, group.forumList.toImmutableList()) }
            .filter { it.forums.isNotEmpty() }
            .sortedByDescending { it.level.toIntOrNull() ?: 0 }
        val gradedNames = gradedForums.flatMap { it.forums }.toSet()
        val plain = sources.user.likeForum
            .map { it.forum_name }
            .filter { it.isNotEmpty() && it !in gradedNames }
            .distinct()
        if (gradedForums.isEmpty() && plain.isEmpty()) return null
        return HiddenLikeForum(
            grade = gradedForums.toImmutableList(),
            plain = plain.toImmutableList(),
        )
    }

    /**
     * 供「回复」页签筛选下拉使用的吧列表：
     * 正常时取关注吧列表（带 id），被隐藏时用兜底数据补齐（能对上资料页的吧带上 id）。
     */
    suspend fun loadFilterEntries(uid: Long): List<Entry> {
        val all = loadAll(uid)
        if (all.isNotEmpty()) {
            return all.mapNotNull { bean ->
                bean.name?.takeIf { it.isNotEmpty() }?.let { Entry(bean.id.toLongOrNull(), it) }
            }.distinctBy { it.name }
        }

        val sources = loadSources(uid) ?: return emptyList()
        val idsByName = sources.user.likeForum
            .filter { it.forum_name.isNotEmpty() && it.forum_id != 0L }
            .associate { it.forum_name to it.forum_id }
        val names = buildList {
            sources.grade.values.forEach { addAll(it.forumList) }
            addAll(sources.user.likeForum.map { it.forum_name })
        }.filter { it.isNotEmpty() }.distinct()
        return names.map { Entry(idsByName[it], it) }
    }

    /** 拉一次资料页 + 面板，供上面两个方法共用 */
    private suspend fun loadSources(uid: Long): Sources? {
        val user = runCatching {
            TiebaApi.getInstance().userProfileFlow(uid).firstOrNull()
        }.getOrNull()?.let { it: ProfileResponse -> it.data_?.user } ?: return null

        val grade = runCatching {
            TiebaApi.getInstance().userPanelFlow(user.name)
                .firstOrNull()
                ?.data
                ?.honor
                ?.grade
                .orEmpty()
        }.getOrDefault(emptyMap())

        return Sources(user, grade)
    }

    private data class Sources(
        val user: User,
        val grade: Map<String, com.huanchengfly.tieba.post.api.models.UserPanelBean.ForumGroup>,
    )
}

/** 用户隐藏「关注的吧」时能恢复出来的信息，可能不完整 */
@Immutable
data class HiddenLikeForum(
    /** 按吧内等级分组的吧名，等级从高到低 */
    val grade: ImmutableList<GradeGroup> = persistentListOf(),
    /** 只在资料页出现、未出现在等级分组里的吧名 */
    val plain: ImmutableList<String> = persistentListOf(),
) {
    val isEmpty: Boolean
        get() = grade.isEmpty() && plain.isEmpty()
}

@Immutable
data class GradeGroup(
    /** 吧内等级（接口以字符串形式给出） */
    val level: String,
    val forums: ImmutableList<String>,
)
