package com.huanchengfly.tieba.post.ui.page.user.likeforum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.models.UserLikeForumBean
import com.huanchengfly.tieba.post.arch.GlobalEvent
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.getOrNull
import com.huanchengfly.tieba.post.arch.onGlobalEvent
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.common.theme.compose.pullRefreshIndicator
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.ForumPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.Chip
import com.huanchengfly.tieba.post.ui.widgets.compose.Container
import com.huanchengfly.tieba.post.ui.widgets.compose.ErrorScreen
import com.huanchengfly.tieba.post.ui.widgets.compose.LazyLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.LoadMoreLayout
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.states.StateScreen
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun UserLikeForumPage(
    uid: Long,
    fluid: Boolean = false,
    enablePullRefresh: Boolean = false,
    viewModel: UserLikeForumViewModel = pageViewModel(),
) {
    val navigator = LocalNavigator.current

    LazyLoad(loaded = viewModel.initialized) {
        viewModel.send(UserLikeForumUiIntent.Refresh(uid))
        viewModel.initialized = true
    }

    val isRefreshing by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::isRefreshing,
        initial = true
    )
    val isLoadingMore by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::isLoadingMore,
        initial = false
    )
    val error by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::error,
        initial = null
    )
    val currentPage by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::currentPage,
        initial = 1
    )
    val hasMore by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::hasMore,
        initial = false
    )
    val forums by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::forums,
        initial = persistentListOf()
    )
    val hidden by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::hidden,
        initial = null
    )

    val hiddenForums = hidden.getOrNull()
    // 注意：不能写成 remember { derivedStateOf { ... hiddenForums ... } }，
    // 那样闭包会捕获首次组合时的 hiddenForums（此时兜底数据还没回来），导致永远判定为空。
    val isEmpty = forums.isEmpty() && hiddenForums?.isEmpty != false
    val isError by remember {
        derivedStateOf { error != null }
    }

    onGlobalEvent<GlobalEvent.Refresh>(
        filter = { it.key == "user_profile" }
    ) {
        viewModel.send(UserLikeForumUiIntent.Refresh(uid))
    }

    StateScreen(
        isEmpty = isEmpty,
        isError = isError,
        isLoading = isRefreshing,
        onReload = {
            viewModel.send(UserLikeForumUiIntent.Refresh(uid))
        },
        errorScreen = { ErrorScreen(error = error.getOrNull()) },
    ) {
        val pullRefreshState = rememberPullRefreshState(
            refreshing = isRefreshing,
            onRefresh = ::reload
        )

        val lazyListState = rememberLazyListState()

        val pullRefreshModifier =
            if (enablePullRefresh) Modifier.pullRefresh(pullRefreshState) else Modifier

        Box(modifier = pullRefreshModifier) {
            LoadMoreLayout(
                isLoading = isLoadingMore,
                onLoadMore = {
                    viewModel.send(UserLikeForumUiIntent.LoadMore(uid, currentPage))
                },
                loadEnd = !hasMore,
                lazyListState = lazyListState
            ) {
                UserLikeForumList(
                    data = forums,
                    hidden = hiddenForums,
                    fluid = fluid,
                    onClickForum = { forumBean ->
                        forumBean.name?.let {
                            navigator.navigate(ForumPageDestination(it))
                        }
                    },
                    onClickForumName = { name ->
                        navigator.navigate(ForumPageDestination(name))
                    },
                    lazyListState = lazyListState
                )
            }

            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = ExtendedTheme.colors.pullRefreshIndicator,
                contentColor = ExtendedTheme.colors.primary,
            )
        }
    }
}

@Composable
private fun UserLikeForumList(
    data: ImmutableList<UserLikeForumBean.ForumBean>,
    onClickForum: (UserLikeForumBean.ForumBean) -> Unit,
    fluid: Boolean = false,
    hidden: HiddenLikeForum? = null,
    onClickForumName: (String) -> Unit = {},
    lazyListState: LazyListState = rememberLazyListState(),
) {
    MyLazyColumn(state = lazyListState) {
        if (hidden != null && !hidden.isEmpty) {
            item(key = "hidden_like_forum") {
                Container(fluid = fluid) {
                    HiddenLikeForumSection(
                        hidden = hidden,
                        onClickForum = onClickForumName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
        items(
            items = data,
            key = { it.id }
        ) {
            Container(fluid = fluid) {
                UserLikeForumItem(
                    item = it,
                    onClick = {
                        onClickForum(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * 关注的吧被隐藏时的兜底展示：
 * 面板能给出「按吧内等级分组」的那部分，资料页还能补充一些只在资料里出现的吧名。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HiddenLikeForumSection(
    hidden: HiddenLikeForum,
    onClickForum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(id = R.string.title_user_hide_like_forum),
                style = MaterialTheme.typography.subtitle1,
            )
            Text(
                text = stringResource(id = R.string.summary_user_hide_like_forum),
                style = MaterialTheme.typography.caption,
                color = ExtendedTheme.colors.textSecondary,
            )
        }

        hidden.grade.fastForEach { group ->
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(
                        id = R.string.text_user_hide_like_forum_grade,
                        group.level
                    ),
                    style = MaterialTheme.typography.caption,
                    color = ExtendedTheme.colors.textSecondary,
                )
                ForumChips(forums = group.forums, onClickForum = onClickForum)
            }
        }

        if (hidden.plain.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.text_user_hide_like_forum_plain),
                    style = MaterialTheme.typography.caption,
                    color = ExtendedTheme.colors.textSecondary,
                )
                ForumChips(forums = hidden.plain, onClickForum = onClickForum)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForumChips(
    forums: ImmutableList<String>,
    onClickForum: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        forums.fastForEach { name ->
            Chip(
                text = name,
                onClick = { onClickForum(name) },
            )
        }
    }
}

@Composable
private fun UserLikeForumItem(
    item: UserLikeForumBean.ForumBean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Avatar(
            data = item.avatar,
            size = Sizes.Medium,
            contentDescription = null
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = item.name.orEmpty(), style = MaterialTheme.typography.subtitle1)
            item.slogan.takeUnless { it.isNullOrEmpty() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.body2,
                    color = ExtendedTheme.colors.textSecondary
                )
            }
        }
    }
}