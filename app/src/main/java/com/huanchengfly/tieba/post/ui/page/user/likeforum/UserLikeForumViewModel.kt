package com.huanchengfly.tieba.post.ui.page.user.likeforum

import androidx.compose.runtime.Immutable
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.UserLikeForumBean
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.arch.wrapImmutable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@HiltViewModel
class UserLikeForumViewModel @Inject constructor() :
    BaseViewModel<UserLikeForumUiIntent, UserLikeForumPartialChange, UserLikeForumUiState, UiEvent>() {
    override fun createInitialState(): UserLikeForumUiState = UserLikeForumUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<UserLikeForumUiIntent, UserLikeForumPartialChange, UserLikeForumUiState> =
        UserLikeForumPartialChangeProducer

    private object UserLikeForumPartialChangeProducer :
        PartialChangeProducer<UserLikeForumUiIntent, UserLikeForumPartialChange, UserLikeForumUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<UserLikeForumUiIntent>): Flow<UserLikeForumPartialChange> =
            merge(
                intentFlow.filterIsInstance<UserLikeForumUiIntent.Refresh>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<UserLikeForumUiIntent.LoadMore>()
                    .flatMapConcat { it.toPartialChangeFlow() },
            )

        private fun UserLikeForumUiIntent.Refresh.toPartialChangeFlow(): Flow<UserLikeForumPartialChange.Refresh> =
            TiebaApi.getInstance()
                .userLikeForumFlow(uid.toString())
                .map<UserLikeForumBean, UserLikeForumPartialChange.Refresh> {
                    val forums = it.forumList.forumList
                    UserLikeForumPartialChange.Refresh.Success(
                        page = 1,
                        hasMore = it.hasMore == "1",
                        forums = forums,
                        // 用户隐藏「关注的吧」时接口会返回空列表，此时尝试兜底恢复部分数据
                        hidden = if (forums.isEmpty()) loadHiddenLikeForum(uid) else null,
                    )
                }
                .onStart { emit(UserLikeForumPartialChange.Refresh.Start) }
                .catch { emit(UserLikeForumPartialChange.Refresh.Failure(it)) }

        /**
         * 用户隐藏「关注的吧」时的兜底：
         * ① 资料页接口仍会返回 [User.like_forum]（吧名列表）；
         * ② 网页版面板接口返回 `honor.grade`（按吧内等级分组的吧名）。
         * 两者合并，等级分组里已有的吧名不重复计入「其它」。
         *
         * 任意一步失败都只是拿不到对应部分，不影响正常列表，因此全部做静默降级。
         */
        private suspend fun loadHiddenLikeForum(uid: Long): HiddenLikeForum? {
            val user = runCatching {
                TiebaApi.getInstance().userProfileFlow(uid).firstOrNull()?.data_?.user
            }.getOrNull() ?: return null

            val gradedForums = runCatching {
                TiebaApi.getInstance().userPanelFlow(user.name)
                    .firstOrNull()
                    ?.data
                    ?.honor
                    ?.grade
                    .orEmpty()
            }.getOrDefault(emptyMap())
                .map { (level, group) -> GradeGroup(level, group.forumList.toImmutableList()) }
                .filter { it.forums.isNotEmpty() }
                .sortedByDescending { it.level.toIntOrNull() ?: 0 }

            val gradedNames = gradedForums.flatMap { it.forums }.toSet()
            val plain = user.likeForum
                .map { it.forum_name }
                .filter { it.isNotEmpty() && it !in gradedNames }
                .distinct()

            if (gradedForums.isEmpty() && plain.isEmpty()) return null
            return HiddenLikeForum(
                grade = gradedForums.toImmutableList(),
                plain = plain.toImmutableList(),
            )
        }

        private fun UserLikeForumUiIntent.LoadMore.toPartialChangeFlow(): Flow<UserLikeForumPartialChange.LoadMore> =
            TiebaApi.getInstance()
                .userLikeForumFlow(uid.toString(), page + 1)
                .map<UserLikeForumBean, UserLikeForumPartialChange.LoadMore> {
                    UserLikeForumPartialChange.LoadMore.Success(
                        page = page + 1,
                        hasMore = it.hasMore == "1",
                        forums = it.forumList.forumList,
                    )
                }
                .onStart { emit(UserLikeForumPartialChange.LoadMore.Start) }
                .catch { emit(UserLikeForumPartialChange.LoadMore.Failure(it)) }
    }
}

sealed interface UserLikeForumUiIntent : UiIntent {
    data class Refresh(val uid: Long) : UserLikeForumUiIntent
    data class LoadMore(
        val uid: Long,
        val page: Int,
    ) : UserLikeForumUiIntent
}

sealed interface UserLikeForumPartialChange : PartialChange<UserLikeForumUiState> {
    sealed class Refresh : UserLikeForumPartialChange {
        override fun reduce(oldState: UserLikeForumUiState): UserLikeForumUiState = when (this) {
            is Start -> {
                oldState.copy(
                    isRefreshing = true,
                )
            }

            is Success -> {
                oldState.copy(
                    isRefreshing = false,
                    error = null,
                    currentPage = page,
                    hasMore = hasMore,
                    forums = forums.toImmutableList(),
                    hidden = hidden?.wrapImmutable(),
                )
            }

            is Failure -> {
                oldState.copy(
                    isRefreshing = false,
                    error = error.wrapImmutable(),
                )
            }
        }

        data object Start : Refresh()

        data class Success(
            val page: Int,
            val hasMore: Boolean,
            val forums: List<UserLikeForumBean.ForumBean>,
            val hidden: HiddenLikeForum? = null,
        ) : Refresh()

        data class Failure(val error: Throwable) : Refresh()
    }

    sealed class LoadMore : UserLikeForumPartialChange {
        override fun reduce(oldState: UserLikeForumUiState): UserLikeForumUiState = when (this) {
            is Start -> {
                oldState.copy(
                    isLoadingMore = true,
                )
            }

            is Success -> {
                val uniqueForums = (oldState.forums + forums).distinctBy { it.id }
                oldState.copy(
                    isLoadingMore = false,
                    error = null,
                    currentPage = page,
                    hasMore = hasMore,
                    forums = uniqueForums.toImmutableList(),
                )
            }

            is Failure -> {
                oldState.copy(
                    isLoadingMore = false,
                    error = error.wrapImmutable(),
                )
            }
        }

        data object Start : LoadMore()

        data class Success(
            val page: Int,
            val hasMore: Boolean,
            val forums: List<UserLikeForumBean.ForumBean>,
        ) : LoadMore()

        data class Failure(val error: Throwable) : LoadMore()
    }
}

@Immutable
data class UserLikeForumUiState(
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: ImmutableHolder<Throwable>? = null,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val forums: ImmutableList<UserLikeForumBean.ForumBean> = persistentListOf(),
    /** 关注的吧被隐藏时，从资料页 / 面板兜底恢复出来的数据 */
    val hidden: ImmutableHolder<HiddenLikeForum>? = null,
) : UiState

/**
 * 用户隐藏「关注的吧」时能恢复出来的信息，可能不完整。
 */
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