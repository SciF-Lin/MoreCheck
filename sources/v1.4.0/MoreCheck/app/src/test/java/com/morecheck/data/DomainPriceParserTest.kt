package com.morecheck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DomainPriceRepository] 里各官网解析器的纯 JVM 回归测试。
 *
 * ## 为什么用"页面片段"而不是抓真页面
 *
 * 单测不该依赖网络（会随官网改版 / 网络抖动变红），但解析器又**只**对真实
 * 结构有效。折中做法：把实测抓到的 HTML 片段原样内联在这里，去掉与解析无关
 * 的部分。官网改结构时这些片段本身也要更新——那正是"解析器需要改"的信号。
 *
 * 另一个必须守住的口径：**解析不出价格时返回空列表**，绝不返回"记忆价"。
 */
class DomainPriceParserTest {

    private val aliyunSource = RegistrarSource(
        id = "aliyun", name = "阿里云 · 万网", short = "万网", region = "国内",
        currency = "CNY", pageUrl = "https://wanwang.aliyun.com/domain/", note = ""
    )
    private val westSource = aliyunSource.copy(id = "west", name = "西部数码", short = "西部数码")
    private val porkbunSource = aliyunSource.copy(id = "porkbun", name = "Porkbun", short = "Porkbun", currency = "USD")
    private val dynadotSource = porkbunSource.copy(id = "dynadot", name = "Dynadot", short = "Dynadot")
    private val gandiSource = aliyunSource.copy(id = "gandi", name = "Gandi", short = "Gandi", currency = "EUR")
    private val nameComSource = porkbunSource.copy(id = "namecom", name = "Name.com", short = "Name.com")

    /* ------------------------------ 阿里云 ------------------------------ */

    @Test
    fun `阿里云 热门后缀卡片解析出首年价续费价与日常价`() {
        val html = """
            <div><div class="recommendation__commodity"><div class="recommendation__commodity__info">
            <h4 class="suffix">.com <span class="tag-HOT">HOT</span></h4>
            <div class="price-info"><div class="price-info__again"><div>¥95/年<p>续费价格</p></div>
            <div>¥85/年<p>转入价格</p></div></div>
            <div class="price-info__begin"><span class="price-info__price">¥<i class="integer">85</i></span><span>/首年</span></div>
            <p class="price-info__ordinary"><span>日常价:</span>¥90/年</p></div></div>
            <a href="https://wanwang.aliyun.com/domain/searchresult/#/?suffix=com">立即注册</a></div>
        """.trimIndent()

        val rows = parseAliyun(html, aliyunSource)

        assertEquals(1, rows.size)
        val com = rows.first()
        assertEquals("com", com.tld)
        assertEquals(85.0, com.register ?: 0.0, 0.001)
        assertEquals(95.0, com.renew ?: 0.0, 0.001)
        assertEquals(85.0, com.transfer ?: 0.0, 0.001)
        assertEquals(90.0, com.regular ?: 0.0, 0.001)
        assertEquals("CNY", com.currency)
        assertTrue("有首年价要打促销标", com.firstYear)
        assertTrue(com.buyUrl.contains("suffix=com"))
    }

    @Test
    fun `阿里云 页面改版时返回空列表而不是编一个价格`() {
        assertTrue(parseAliyun("<html><body>nothing here</body></html>", aliyunSource).isEmpty())
    }

    /* ------------------------------ 西部数码 ------------------------------ */

    @Test
    fun `西部数码 内嵌价目表按一年一个的口径取值`() {
        val payload = """
            [{"suffix":".com","oldprice":"86","buy":{"1":{"1":"86","3":"274"},"7":{"1":"80"}},
              "renew":{"1":{"1":"94"}},"turnprice":{"buyprice":"83"},"link":"https://www.west.cn/domains/com.asp"},
             {"suffix":".xyz","buy":{"1":{"1":"18"}},"renew":{"1":{"1":"45"}}}]
        """.trimIndent()
        val html = "<script>var enData = JSON.parse('$payload');</script>"

        val rows = parseWest(html, westSource)

        assertEquals(2, rows.size)
        val com = rows.first { it.tld == "com" }
        assertEquals(86.0, com.register ?: 0.0, 0.001)
        assertEquals(94.0, com.renew ?: 0.0, 0.001)
        assertEquals(83.0, com.transfer ?: 0.0, 0.001)
        // 老价（划线价）86 与现价相同，也照实带出来，界面自己决定显不显示
        assertEquals(86.0, com.regular ?: 0.0, 0.001)
        assertEquals("https://www.west.cn/domains/com.asp", com.buyUrl)
    }

    @Test
    fun `西部数码 缺了 enData 时返回空列表`() {
        assertTrue(parseWest("<html>服务繁忙</html>", westSource).isEmpty())
    }

    @Test
    fun `JS 字符串反转义支持 unicode 与引号`() {
        assertEquals("中文'\"", decodeJsString("\\u4e2d\\u6587\\'\\\""))
    }

    /* ------------------------------- Porkbun ------------------------------ */

    @Test
    fun `Porkbun 官方价目表接口解析`() {
        val json = """
            {"status":"SUCCESS","pricing":{
              "com":{"registration":"11.81","renewal":"11.81","transfer":"11.81"},
              "xyz":{"registration":"2.06","renewal":"13.39","transfer":"11.81"}
            }}
        """.trimIndent()

        val rows = parsePorkbun(json, porkbunSource)

        assertEquals(2, rows.size)
        val xyz = rows.first { it.tld == "xyz" }
        assertEquals(2.06, xyz.register ?: 0.0, 0.001)
        assertEquals(13.39, xyz.renew ?: 0.0, 0.001)
        assertEquals("USD", xyz.currency)
    }

    /* ------------------------------- Dynadot ------------------------------ */

    @Test
    fun `Dynadot 数据池里的整数是下标引用`() {
        // 真实结构：对象里的 name/reg_price 都是 pool 的下标（数字），
        // 解引用之后才是 "$11.62" 这样的字符串。
        val json = """["com","${'$'}11.62","${'$'}13.00","${'$'}11.62",
            {"name":0,"reg_price":1,"renew_price":2,"tr_price":3}]"""
        val html = """<script type="application/json" id="__NUXT_DATA__">$json</script>"""

        val rows = parseDynadot(html, dynadotSource)

        assertEquals(1, rows.size)
        val com = rows.first()
        assertEquals("com", com.tld)
        assertEquals(11.62, com.register ?: 0.0, 0.001)
        assertEquals(13.0, com.renew ?: 0.0, 0.001)
        assertEquals("USD", com.currency)
    }

    /* -------------------------------- Gandi ------------------------------- */

    @Test
    fun `Gandi 对比表按 注册 续费 转入 顺序取三列`() {
        val html = """
            <tr class="comparative-table__row">
              <th class="comparative-table__cell"><a href="/en/domain/tld/com">.com</a></th>
              <td><span class="comparative-table__price">€11.65</span></td>
              <td><span class="comparative-table__price">€31.98</span></td>
              <td><span class="comparative-table__price">€11.65</span></td>
            </tr>
        """.trimIndent()

        val rows = parseGandi(html, gandiSource)

        assertEquals(1, rows.size)
        val com = rows.first()
        assertEquals(11.65, com.register ?: 0.0, 0.001)
        assertEquals(31.98, com.renew ?: 0.0, 0.001)
        assertEquals("EUR", com.currency)
    }

    /* ------------------------------- Name.com ----------------------------- */

    @Test
    fun `Namecom 锚点内第一个价是促销价 第二个不同的价是划线价`() {
        val html = """
            <a href="/domains/io" class="nounderline">
              <p>.io</p>
              <p><span>${'¥'}371.15</span> <span>${'¥'}549.89</span></p>
            </a>
        """.trimIndent()

        val rows = parseNameCom(html, nameComSource)

        assertEquals(1, rows.size)
        val io = rows.first()
        assertEquals(371.15, io.register ?: 0.0, 0.001)
        assertEquals(549.89, io.regular ?: 0.0, 0.001)
        assertEquals("CNY", io.currency)
        assertTrue("首年促销标要打上", io.firstYear)
    }

    @Test
    fun `Namecom 语言前缀不固定 也要能解析`() {
        val html = """
            <a href="/zh-cn/domains/io" class="nounderline">
              <p>.io</p>
              <p><span>${'¥'}371.15</span> <span>${'¥'}549.89</span></p>
            </a>
        """.trimIndent()

        val rows = parseNameCom(html, nameComSource)

        assertEquals(1, rows.size)
        assertEquals("io", rows.first().tld)
    }

    /* -------------------------------- 金额 -------------------------------- */

    @Test
    fun `金额解析识别币种 缺符号时币种为空`() {
        assertEquals(11.81, parseMoney("${'$'}11.81").first ?: 0.0, 0.001)
        assertEquals("USD", parseMoney("${'$'}11.81").second)
        assertEquals("EUR", parseMoney("€11,65").second)
        assertEquals("CNY", parseMoney("¥1,299").second)
        assertEquals(1299.0, parseMoney("¥1,299").first ?: 0.0, 0.001)
        assertEquals("11.81", parseMoney("11.81").first?.toString())
        assertNull(parseMoney("").first)
        assertNull(parseMoney("-").first)
        assertNull(parseMoney(null).first)
    }

    /* ------------------------------ 汇率换算 ------------------------------ */

    @Test
    fun `汇率可用时跨币种换算 不可用时不换`() {
        val fx = FxRates(ok = true, usdCny = 7.1, eurCny = 7.7, gbpCny = 9.0, updatedAt = null, error = null)
        assertEquals(71.0, fx.cnyOf(10.0, "USD") ?: 0.0, 0.001)
        assertEquals(85.0, fx.cnyOf(85.0, "CNY") ?: 0.0, 0.001)
        assertNull("币种未知时不能瞎算", fx.cnyOf(10.0, "JPY"))

        val broken = fx.copy(ok = false, usdCny = null, eurCny = null, gbpCny = null)
        assertNull("汇率失败时不做任何换算", broken.cnyOf(11.85, "USD"))
    }

    /* ------------------------------ 输入解析 ------------------------------ */

    @Test
    fun `输入解析 带协议带路径带大写也认`() {
        val q = parseDomainInput("  HTTPS://www.Example.COM/path?x=1 ")
        assertEquals("example.com", q.domain)
        assertEquals("com", q.tld)
        assertEquals("example", q.sld)
        assertTrue(q.explicit)
    }

    @Test
    fun `输入解析 多段后缀优先匹配`() {
        val q = parseDomainInput("example.com.cn")
        assertEquals("com.cn", q.tld)
        assertEquals("example", q.sld)
    }

    @Test
    fun `输入解析 只写主体时按 com 算并标记非显式`() {
        val q = parseDomainInput("mybrand")
        assertEquals("com", q.tld)
        assertEquals("mybrand", q.sld)
        assertTrue(!q.explicit)
    }

    @Test
    fun `输入解析 只写后缀时按纯后缀查询处理`() {
        val q = parseDomainInput("xyz")
        assertEquals("xyz", q.tld)
        assertEquals("", q.sld)
        assertEquals(".xyz", q.domain)
        assertTrue("纯后缀也要标成显式查询，否则会去跑概览", q.explicit)
    }

    @Test
    fun `输入解析 全角句点也当作后缀分隔符`() {
        val q = parseDomainInput("Example。COM")
        assertEquals("example.com", q.domain)
        assertEquals("example", q.sld)
    }

    @Test
    fun `输入解析 非法主体直接报错`() {
        val error = runCatching { parseDomainInput("中文域名.com") }.exceptionOrNull()
        assertNotNull("中文主体应被拒绝（未做 punycode 转换）", error)
    }
}
