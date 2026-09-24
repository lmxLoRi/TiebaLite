package com.huanchengfly.tieba.post.api.models

import com.google.gson.annotations.SerializedName

/**
 * 贴吧网页版「用户面板」接口（`/home/get/panel`）的响应。
 *
 * 当用户把「关注的吧」设为隐藏时，[UserLikeForumBean] 对应的接口会返回空列表，
 * 这里仍能拿到**部分**关注吧信息：[PanelData.honor] 里的 `grade`（按吧内等级分组的吧名）
 * 与 `manager`（吧务身份对应的吧名），可用于兜底展示。
 *
 * 注意该接口是网页接口，请求必须带浏览器 User-Agent，否则会返回 HTML 页面。
 */
data class UserPanelBean(
    @SerializedName("no")
    val no: Int = -1,

    @SerializedName("error")
    val errorMsg: String? = null,

    @SerializedName("data")
    val data: PanelData? = null,
) {
    val isSuccess: Boolean
        get() = no == 0 && data != null

    data class PanelData(
        @SerializedName("name")
        val name: String? = null,

        @SerializedName("name_show")
        val nameShow: String? = null,

        @SerializedName("honor")
        val honor: Honor? = null,
    )

    data class Honor(
        /** 吧内等级 → 该等级下的吧名（面板只展示其中一部分） */
        @SerializedName("grade")
        val grade: Map<String, ForumGroup> = emptyMap(),

        /** 吧务身份（`manager` / `assist` 等）→ 吧名 */
        @SerializedName("manager")
        val manager: Map<String, ForumGroup>? = null,
    )

    data class ForumGroup(
        @SerializedName("count")
        val count: Int = 0,

        @SerializedName("forum_list")
        val forumList: List<String> = emptyList(),
    )
}
