/**
 * 腾讯视频「整片地址」——**我们自己算签名去要**（2026-10-18 晚定案）。
 *
 * 走过并排除的路（都记在台账里，别再回头试）：
 *   · 网页分片接力：腾讯把片子切十几秒一段、**一段一签**，只能顺播、拖不动；
 *   · 老接口 `vv.video.qq.com/getinfo`：不要签名，但**对会员片永远只给 180 秒试看**
 *     （实测连登录着的浏览器去问也只有 180 秒，服务端同时拒绝越过该点的分段请求）；
 *   · 借腾讯的签名小库：库能加载，但它还要另一套腾讯组件（QIMEI）才肯出签名。
 *
 * 现在这条路：签名 = md5(盐 + 片号 + 服务器时间 + "*#06#" + 平台号)，见 [TencentSign]。
 * 实测（.18）：自己算的签名被接口接受（em=0），标题、时长、清晰度都对；
 * 再叠加上网页那份登录身份，`preview` 由 180 秒变成整集时长，越过试看点拉流返回 206。
 *
 * 用法：`fetch(vid, cookie)` → 拿到地址与档位，交给自己的播放器。
 */
object TencentWholeUrl {

    private const val TAG = "B0BEmbyVR-Vkey"

    data class Hit(val url: String, val defn: String, val quality: String, val size: Long = 0L)

    /**
     * 清晰度档位，从高到低问。平台号 10201 这一套签名实测可用；
     * 未带登录身份时接口只给到 720P 并且只准看 180 秒（preview=180），
     * 带上身份后 preview 会等于整集时长（实测 2819 秒），拉流时越过试看点也返回 206。
     */
    private val DEFNS = listOf("uhd", "fhd", "shd", "hd")

    /** 从网页地址里抠出片号（腾讯的片号形如 e4102s0t1jg）。 */
    fun vidFromFrame(frame: String): String? {
        if (frame.isEmpty()) return null
        if (!frame.contains("qq.com")) return null
        Regex("[?&]vid=([A-Za-z0-9]{8,16})").find(frame)?.let { return it.groupValues[1] }
        Regex("/([A-Za-z0-9]{10,12})\\.html").find(frame)?.let { return it.groupValues[1] }
        return null
    }

    /**
     * 要"整集地址"：先问服务器时间，再用**我们自己算的签名**去问接口。
     * deep=true 时把所有档位都问一遍并取文件最大的一档（探针用），false 时问到一档就回。
     */
    fun fetch(vid: String, cookie: String?, deep: Boolean = false): Hit? {
        val tm = TencentSign.serverTime(cookie)
        if (tm == null) {
            Log.w(TAG, "整片地址：没问到服务器时间，放弃")
            return null
        }
        val ver = TencentSign.encryptVer()
        val ck = TencentSign.ckey(vid, tm)
        Log.i(TAG, "整片地址：签名版本 $ver，票=${if (cookie.isNullOrBlank()) "无" else "有"}，开始问地址")
        var best: Hit? = null
        for (defn in if (deep) DEFNS else DEFNS.take(2)) {
            val hit = tryOnce(vid, defn, ck, ver, tm, cookie) ?: continue
            if (best == null || hit.size > best.size) best = hit
            if (!deep) break
        }
        return best
    }

    private fun tryOnce(vid: String, defn: String, ck: String, ver: String, tm: String, cookie: String?): Hit? {
        val url = "https://vv6.video.qq.com/getvinfo?otype=json&vid=$vid&platform=${TencentSign.PLATFORM}" +
            "&sdtfrom=v1010&defn=$defn&vt=3&charge=0&cKey=$ck&encryptVer=$ver&tm=$tm"
        val text = get(url, cookie) ?: return null
        return try {
            val json = org.json.JSONObject(stripWrapper(text))
            val vi = json.optJSONObject("vl")?.optJSONArray("vi")?.optJSONObject(0) ?: return null
            val ui = vi.optJSONObject("ul")?.optJSONArray("ui")?.optJSONObject(0) ?: return null
            val base = ui.optString("url")
            val fn = vi.optString("fn")
            val vkey = vi.optString("fvkey")
            if (base.isEmpty() || fn.isEmpty()) {
                Log.w(TAG, "整片地址[$defn]：接口没给地址（em=${json.optInt("em", -1)}）")
                return null
            }
            val play = base + fn + if (vkey.isNotEmpty()) "?vkey=$vkey" else ""
            val h = vi.optInt("vh", 0)
            val td = vi.optInt("td", 0)
            val preview = json.optInt("preview", -1)
            val size = vi.optLong("fs", 0L)
            /*
             * preview 是"允许看多少秒"：未带身份时是 180（就是那 3 分钟试看），
             * 带上身份后等于整集时长。这条日志是判断"身份有没有生效"的唯一依据。
             */
            Log.i(
                TAG,
                "整片地址[$defn] → ${if (h > 0) "${h}P" else defn} 文件 ${size / 1048576}MB" +
                    "，允许看 ${preview} 秒 / 整集 ${td} 秒，片名 ${vi.optString("ti")}" +
                    "，票=${if (cookie.isNullOrBlank()) "无" else "有"}",
            )
            Hit(play, defn, if (h > 0) "${h}P" else defn, size)
        } catch (e: Throwable) {
            Log.w(TAG, "整片地址[$defn] 解析失败：$e")
            null
        }
    }

    /** 接口回的是 `QZOutputJson={…};` 这种壳，剥掉再当 JSON 解。 */
    private fun stripWrapper(t: String): String =
        t.trim().removePrefix("QZOutputJson=").trim().trimEnd(';').trim()

    private fun get(url: String, cookie: String?): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "整片地址：HTTP $code")
                return null
            }
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (t: Throwable) {
            Log.w(TAG, "整片地址：请求失败 ${t.message}")
            null
        }
    }
}
