package com.huanchengfly.tieba.post.api.retrofit.interfaces

import com.huanchengfly.tieba.post.api.models.UserPanelBean
import kotlinx.coroutines.flow.Flow
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 贴吧网页版补充接口。
 *
 * `/home/get/panel` 会按 User-Agent 分流：贴吧客户端 UA / 移动端 WebView UA 会拿到 HTML 页面，
 * 只有桌面浏览器 UA 才返回 JSON，所以本接口在
 * [com.huanchengfly.tieba.post.api.retrofit.RetrofitTiebaApi.TIEBA_PANEL_API] 中单独配置 UA。
 */
interface TiebaPanelApi {
    @GET("/home/get/panel")
    fun panelFlow(
        @Query("un") un: String,
    ): Flow<UserPanelBean>
}
