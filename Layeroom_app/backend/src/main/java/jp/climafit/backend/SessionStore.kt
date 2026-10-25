package jp.climafit.backend

import android.content.Context

/** ログイン情報（ニックネームと db_server のトークン）の保存先。 */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

/** 端末の SharedPreferences に保存する（アプリを消すまで残る）。 */
class SharedPreferencesSessionStore(context: Context) : SessionStore {
    private val prefs = context.applicationContext.getSharedPreferences("layeroom_session", Context.MODE_PRIVATE)

    override fun load(): Session? {
        val nick = prefs.getString("nickname", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val id = prefs.getLong("user_id", -1).takeIf { it >= 0 } ?: return null
        return Session(nick, id, token)
    }

    override fun save(session: Session) {
        prefs.edit().putString("nickname", session.nickname).putLong("user_id", session.userId)
            .putString("token", session.token).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}

/** メモリだけ（テスト用）。 */
class InMemorySessionStore(private var session: Session? = null) : SessionStore {
    override fun load() = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
}
