package com.huanchengfly.tieba.post.ui.page.user.post

import androidx.compose.runtime.Immutable
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.AgreeBean
import com.huanchengfly.tieba.post.api.models.UserLikeForumBean
import com.huanchengfly.tieba.post.api.models.protos.PostInfoList
import com.huanchengfly.tieba.post.api.models.protos.abstractText
import com.huanchengfly.tieba.post.api.models.protos.updateAgreeStatus
import com.huanchengfly.tieba.post.api.models.protos.userPost.UserPostResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@HiltViewModel
class UserPostViewModel @Inject constructor() :
    BaseViewModel<UserPostUiIntent, UserPostPartialChange, UserPostUiState, UiEvent>() {
    override fun createInitialState(): UserPostUiState = UserPostUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<UserPostUiIntent, UserPostPartialChange, UserPostUiState> =
        UserPostPartialChangeProducer

    override fun dispatchEvent(partialChange: UserPostPartialChange): UiEvent? =
        when (partialChange) {
            is UserPostPartialChange.Agree.Failure -> CommonUiEvent.Toast(
                App.INSTANCE.getString(
                    R.string.toast_agree_failed,
                    partialChange.error.getErrorMessage()
                )
            )

            else -> null
        }

    private object UserPostPartialChangeProducer :
        PartialChangeProducer<UserPostUiIntent, UserPostPartialChange, UserPostUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<UserPostUiIntent>): Flow<UserPostPartialChange> =
            merge(
                intentFlow.filterIsInstance<UserPostUiIntent.Refresh>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<UserPostUiIntent.LoadMore>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<UserPostUiIntent.LoadForums>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<UserPostUiIntent.Agree>()
                    .flatMapConcat { it.toPartialChangeFlow() }
            )

        private fun UserPostUiIntent.Refresh.toPartialChangeFlow(): Flow<UserPostPartialChange> =
            TiebaApi.getInstance()
                .userPostFlow(uid, 1, isThread, forumId)
                .map<UserPostResponse, UserPostPartialChange.Refresh> {
                    checkNotNull(it.data_)
                    val postList = it.data_.post_list.filterByForum(forumId)
                    UserPostPartialChange.Refresh.Success(
                        currentPage = 1,
                        hasMore = postList.isNotEmpty(),
                        posts = postList,
                        hidePost = it.data_.hide_post == 1,
                        forumFilter = forumId,
                    )
                }
                .onStart { emit(UserPostPartialChange.Refresh.Start) }
                .catch { emit(UserPostPartialChange.Refresh.Failure(it)) }

        private fun UserPostUiIntent.LoadMore.toPartialChangeFlow(): Flow<UserPostPartialChange> =
            TiebaApi.getInstance()
                .userPostFlow(uid, page + 1, isThread, forumId)
                .map<UserPostResponse, UserPostPartialChange.LoadMore> {
                    checkNotNull(it.data_)
                    val postList = it.data_.post_list.filterByForum(forumId)
                    UserPostPartialChange.LoadMore.Success(
                        currentPage = page + 1,
                        hasMore = postList.isNotEmpty(),
                        posts = postList
                    )
                }
                .onStart { emit(UserPostPartialChange.LoadMore.Start) }
                .catch { emit(UserPostPartialChange.LoadMore.Failure(it)) }

        /**
         * 拉取该用户关注的全部吧，供「回复」页签的筛选下拉使用。
         *
         * 关注吧接口按页返回（每页 50），这里顺次翻页并设页数上限，避免关注上千个吧时把请求打爆。
         */
        private fun UserPostUiIntent.LoadForums.toPartialChangeFlow(): Flow<UserPostPartialChange.LoadForums> =
            flow {
                emit(UserPostPartialChange.LoadForums.Start)
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
                emit(
                    UserPostPartialChange.LoadForums.Success(
                        collected.distinctBy { it.id }
                    )
                )
            }.catch { emit(UserPostPartialChange.LoadForums.Failure(it)) }

        private fun UserPostUiIntent.Agree.toPartialChangeFlow(): Flow<UserPostPartialChange.Agree> =
            TiebaApi.getInstance()
                .opAgreeFlow(
                    threadId.toString(), postId.toString(), hasAgree, objType = 3
                )
                .map<AgreeBean, UserPostPartialChange.Agree> {
                    UserPostPartialChange.Agree.Success(threadId, postId, hasAgree xor 1)
                }
                .onStart {
                    emit(
                        UserPostPartialChange.Agree.Start(
                            threadId,
                            postId,
                            hasAgree xor 1
                        )
                    )
                }
                .catch { emit(UserPostPartialChange.Agree.Failure(threadId, postId, hasAgree, it)) }
    }
}

sealed interface UserPostUiIntent : UiIntent {
    data class Refresh(
        val uid: Long,
        val isThread: Boolean,
        /** 只看该吧的发言，null 表示全部吧 */
        val forumId: Long? = null,
    ) : UserPostUiIntent

    data class LoadMore(
        val uid: Long,
        val isThread: Boolean,
        val page: Int,
        val forumId: Long? = null,
    ) : UserPostUiIntent

    /** 拉取用户关注的全部吧，用于筛选下拉 */
    data class LoadForums(
        val uid: Long,
    ) : UserPostUiIntent

    data class Agree(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int,
    ) : UserPostUiIntent
}

sealed interface UserPostPartialChange : PartialChange<UserPostUiState> {
    sealed class Refresh : UserPostPartialChange {
        override fun reduce(oldState: UserPostUiState): UserPostUiState = when (this) {
            is Start -> oldState.copy(
                isRefreshing = true,
            )

            is Success -> {
                val uniquePosts = posts.distinctBy {
                    "${it.thread_id}_${it.post_id}"
                }.toData()
                oldState.copy(
                    isRefreshing = false,
                    error = null,
                    currentPage = currentPage,
                    hasMore = hasMore,
                    hidePost = hidePost,
                    forumFilter = forumFilter,
                    posts = uniquePosts.toImmutableList()
                )
            }

            is Failure -> oldState.copy(
                isRefreshing = false,
                error = error.wrapImmutable()
            )
        }

        data object Start : Refresh()

        data class Success(
            val currentPage: Int,
            val hasMore: Boolean,
            val posts: List<PostInfoList>,
            val hidePost: Boolean,
            val forumFilter: Long? = null,
        ) : Refresh()

        data class Failure(
            val error: Throwable,
        ) : Refresh()
    }

    sealed class LoadMore : UserPostPartialChange {
        override fun reduce(oldState: UserPostUiState): UserPostUiState = when (this) {
            is Start -> oldState.copy(
                isLoadingMore = true,
            )

            is Success -> {
                val uniquePosts = (oldState.posts + posts.toData()).distinctBy {
                    "${it.data.get { thread_id }}_${it.data.get { post_id }}"
                }.toImmutableList()
                oldState.copy(
                    isLoadingMore = false,
                    error = null,
                    currentPage = currentPage,
                    hasMore = hasMore,
                    posts = uniquePosts
                )
            }

            is Failure -> oldState.copy(
                isLoadingMore = false,
                error = error.wrapImmutable()
            )
        }

        data object Start : LoadMore()

        data class Success(
            val currentPage: Int,
            val hasMore: Boolean,
            val posts: List<PostInfoList>,
        ) : LoadMore()

        data class Failure(
            val error: Throwable,
        ) : LoadMore()
    }

    sealed class LoadForums : UserPostPartialChange {
        override fun reduce(oldState: UserPostUiState): UserPostUiState = when (this) {
            is Start -> oldState

            is Success -> oldState.copy(forums = forums.toImmutableList())

            is Failure -> oldState
        }

        data object Start : LoadForums()

        data class Success(
            val forums: List<UserLikeForumBean.ForumBean>,
        ) : LoadForums()

        data class Failure(
            val error: Throwable,
        ) : LoadForums()
    }

    sealed class Agree : UserPostPartialChange {
        private fun List<PostListItemData>.updateAgreeStatus(
            threadId: Long,
            postId: Long,
            hasAgree: Int,
        ): ImmutableList<PostListItemData> {
            return map {
                val (postInfo) = it
                it.copy(
                    data = if (postInfo.get { thread_id } == threadId && postInfo.get { post_id } == postId) {
                        postInfo.getImmutable { updateAgreeStatus(hasAgree) }
                    } else {
                        postInfo
                    }
                )
            }.toImmutableList()
        }

        override fun reduce(oldState: UserPostUiState): UserPostUiState =
            when (this) {
                is Start -> {
                    oldState.copy(
                        posts = oldState.posts.updateAgreeStatus(
                            threadId,
                            postId,
                            hasAgree
                        )
                    )
                }

                is Success -> {
                    oldState.copy(
                        posts = oldState.posts.updateAgreeStatus(
                            threadId,
                            postId,
                            hasAgree
                        )
                    )
                }

                is Failure -> {
                    oldState.copy(
                        posts = oldState.posts.updateAgreeStatus(
                            threadId,
                            postId,
                            hasAgree
                        )
                    )
                }
            }

        data class Start(
            val threadId: Long,
            val postId: Long,
            val hasAgree: Int,
        ) : Agree()

        data class Success(
            val threadId: Long,
            val postId: Long,
            val hasAgree: Int,
        ) : Agree()

        data class Failure(
            val threadId: Long,
            val postId: Long,
            val hasAgree: Int,
            val error: Throwable,
        ) : Agree()
    }
}

data class UserPostUiState(
    val isRefreshing: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: ImmutableHolder<Throwable>? = null,

    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val posts: ImmutableList<PostListItemData> = persistentListOf(),
    val hidePost: Boolean = false,
    /** 当前筛选的吧，null 表示全部吧 */
    val forumFilter: Long? = null,
    /** 用户关注的全部吧，供筛选下拉使用 */
    val forums: ImmutableList<UserLikeForumBean.ForumBean> = persistentListOf(),
) : UiState

/** 关注吧列表最多翻多少页（每页 50） */
private const val MAX_FORUM_PAGES = 6

/**
 * 按吧筛选时的兜底：请求里已经带了 `forum_id`，正常情况下服务端就会过滤；
 * 万一服务端忽略该参数，这里再过滤一次，保证列表和筛选条显示一致。
 */
private fun List<PostInfoList>.filterByForum(forumId: Long?): List<PostInfoList> =
    if (forumId == null) this else filter { it.forum_id == forumId }

private fun List<PostInfoList>.toData(): ImmutableList<PostListItemData> {
    return map { postInfo ->
        PostListItemData(
            data = postInfo.wrapImmutable(),
            contents = postInfo.content.map {
                PostContentData(
                    contentText = it.post_content.abstractText,
                    createTime = it.create_time,
                    postId = it.post_id,
                    isSubPost = (it.post_type == 1L),
                )
            }.toImmutableList()
        )
    }.toImmutableList()
}

@Immutable
data class PostListItemData(
    val data: ImmutableHolder<PostInfoList>,
//    val blocked: Boolean,
    val isThread: Boolean = data.get { is_thread } == 1,
    val contents: ImmutableList<PostContentData> = persistentListOf(),
)

@Immutable
data class PostContentData(
    val contentText: String,
    val createTime: Long,
    val postId: Long,
    val isSubPost: Boolean,
)