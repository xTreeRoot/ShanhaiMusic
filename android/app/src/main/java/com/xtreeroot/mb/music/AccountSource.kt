package com.xtreeroot.mb.music

data class SourceAccount(val id: String, val name: String, val avatarUrl: String?, val page: String? = null,
                         val avatarHeaders: Map<String, String> = emptyMap())

enum class SignInStatus { Scan, Confirm, Refreshed, Message }

data class SignInPrompt(val qrUrl: String?, val status: SignInStatus = SignInStatus.Message, val message: String = "")

data class SignInMethod(val id: String, val label: String)

/** 可选登录方式；插件声明选项，通用账号页在开始请求前选择。 */
interface SignInMethodSource : AccountSource {
    val signInMethods: List<SignInMethod>
    suspend fun signIn(method: String, prompt: suspend (SignInPrompt) -> Unit): SourceAccount
}

interface AccountSource : MusicSource {
    suspend fun supportsSignIn(): Boolean = true
    suspend fun supportsSignOut(): Boolean = true
    suspend fun account(): SourceAccount?
    suspend fun signIn(prompt: suspend (SignInPrompt) -> Unit): SourceAccount
    suspend fun signOut()
}

/** 显式清理当前插件登录会话；普通退出仍保留可复用凭据。 */
interface AccountCacheResetSource : AccountSource {
    suspend fun supportsAccountCacheReset(): Boolean
    suspend fun signOutAndClearCache()
}
