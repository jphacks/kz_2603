package jp.climafit.backend

import jp.climafit.backend.ai.AdviceFacts
import jp.climafit.backend.ai.Box
import jp.climafit.backend.ai.Coach
import jp.climafit.backend.ai.Comfort
import jp.climafit.backend.ai.Garment
import jp.climafit.backend.ai.General
import jp.climafit.backend.ai.OutdoorInfo
import jp.climafit.backend.ai.Preference
import jp.climafit.backend.ai.Slots
import jp.climafit.backend.ai.Suggest
import jp.climafit.backend.ai.WornItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals

/**
 * 移植した判定ロジックが、以前の Python 版 AI と同じ結果を返すか。
 * 答えは Python 版から作って固定した python_parity.json（約2,600件。Python 版は削除済みなので作り直さない）。
 */
class PythonParityTest {
    private val data: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("python_parity.json")!!.readText(),
    ).jsonObject

    private fun cases(name: String): List<JsonObject> = data.getValue(name).jsonArray.map { it.jsonObject }

    private fun JsonObject.d(k: String): Double = num(k)!!
    private fun JsonObject.dOrNull(k: String): Double? = num(k)
    private fun JsonObject.s(k: String): String = str(k)!!

    private fun wornItem(o: JsonObject) = WornItem(
        o.s("type"), o.str("thickness") ?: "normal", o.str("color") ?: "", o.str("season") ?: "all", o.num("confidence") ?: 0.0,
    )

    private fun garment(o: JsonObject) = Garment(
        id = o.long("id")!!, slot = o.s("slot"), type = o.s("type"), color = o.s("color"), clo = o.d("clo"),
        wholeBody = o.bool("whole_body")!!, up = o.int("up")!!, down = o.int("down")!!, wearCount = o.int("wear_count")!!,
    )

    private fun strings(e: JsonElement?) = (e as JsonArray).map { (it as JsonPrimitive).content }

    @Test
    fun `服の暖かさ（clo）`() = cases("item_clo").forEach {
        assertEquals(it.d("clo"), Comfort.itemClo(it.s("type"), it.s("thickness")), it.toString())
    }

    @Test
    fun `必要な暖かさ（室内・屋外）`() {
        cases("required_clo").forEach {
            assertEquals(it.d("need"), Comfort.requiredClo(it.d("temp"), it.dOrNull("di"), it.bool("dummy")!!), it.toString())
        }
        cases("required_clo_outdoor").forEach { assertEquals(it.d("need"), Comfort.requiredCloOutdoor(it.d("temp")), it.toString()) }
    }

    @Test
    fun `不快指数とその言葉・一般的な目安`() {
        cases("discomfort").forEach {
            val di = Comfort.discomfortIndex(it.d("temp"), it.d("humidity"))
            assertEquals(it.d("di"), di, it.toString())
            assertEquals(it.s("label"), Comfort.diLabel(di), it.toString())
        }
        cases("general_tip").forEach { assertEquals(it.s("tip"), Comfort.generalTip(it.d("need")), it.toString()) }
    }

    @Test
    fun `判定（暑い・ちょうど・寒い・上着を脱げば）`() = cases("judge").forEach { c ->
        val items = c.arr("items").map { wornItem(it.jsonObject) }
        val r = Comfort.judge(items, c.d("need"))
        val e = c.obj("result")!!
        val msg = c.toString()
        assertEquals(e.s("verdict"), r.verdict, msg)
        assertEquals(e.s("verdict_ja"), r.verdictJa, msg)
        assertEquals(e.int("score"), r.score, msg)
        assertEquals(e.d("outfit_clo"), r.outfitClo, msg)
        assertEquals(e.d("diff"), r.diff, msg)
        assertEquals(e.d("without_outer_clo"), r.withoutOuterClo, msg)
        assertEquals(strings(e["removable"]), r.removable, msg)
    }

    @Test
    fun `外での判定`() = cases("judge_outdoor").forEach { c ->
        val r = Comfort.judgeOutdoor(c.d("total"), Comfort.requiredCloOutdoor(c.d("temp")))
        val e = c.obj("result")!!
        assertEquals(e.s("verdict"), r.verdict, c.toString())
        assertEquals(e.d("need_clo"), r.needClo, c.toString())
    }

    @Test
    fun `気温だけの一般的な提案`() = cases("suggest_general").forEach { c ->
        val r = General.suggest(c.d("need"), c.d("indoor"), c.dOrNull("outdoor"))
        val e = c.arr("result").map { it.jsonObject }
        assertEquals(e.size, r.size, c.toString())
        e.zip(r).forEach { (x, y) ->
            assertEquals(x.s("title"), y.title, c.toString())
            assertEquals(x.s("image_key"), y.imageKey, c.toString())
            assertEquals(x.d("total_clo"), y.totalClo, c.toString())
            assertEquals(x.d("gap"), y.gap, c.toString())
            assertEquals(x.s("description"), y.description, c.toString())
            assertEquals(x.obj("bring")?.str("type"), y.bring?.type, c.toString())
        }
    }

    @Test
    fun `保存する上・下の選び方`() = cases("pick_slots").forEach { c ->
        val r = Slots.pick(c.arr("items").map { wornItem(it.jsonObject) })
        val e = c.arr("result").map { it.jsonObject }
        assertEquals(e.size, r.size, c.toString())
        e.zip(r).forEach { (x, y) ->
            assertEquals(x.s("slot"), y.slot, c.toString())
            assertEquals(wornItem(x.obj("item")!!), y.item, c.toString())
            assertEquals(x.d("clo"), y.clo, c.toString())
            assertEquals(x.s("detail"), y.detail, c.toString())
            assertEquals(x.bool("whole_body"), y.wholeBody, c.toString())
        }
    }

    @Test
    fun `保存した服からの提案（組み合わせ・順番・昨日の服の除外）`() = cases("suggest_outfits").forEach { c ->
        val gs = c.arr("garments").map { garment(it.jsonObject) }
        val excl = c.arr("exclude").map { e -> strings(e).let { Triple(it[0], it[1], it[2]) } }.toSet()
        val r = Suggest.outfits(gs, c.d("need"), excl, Preference.build(gs))
        val e = c.arr("result").map { it.jsonObject }
        assertEquals(e.map { it.d("total_clo") to it.arr("ids").map { i -> (i as JsonPrimitive).content.toLong() } },
            r.map { it.totalClo to it.garments.map(Garment::id) }, c.toString())
    }

    @Test
    fun `好みの集計`() = cases("preference").forEach { c ->
        val p = Preference.build(c.arr("garments").map { garment(it.jsonObject) })
        assertEquals(c.s("text"), p.text, c.toString())
        assertEquals(c.obj("colors")!!.mapValues { (it.value as JsonPrimitive).content.toDouble() }, p.colors, c.toString())
        assertEquals(c.obj("types")!!.mapValues { (it.value as JsonPrimitive).content.toDouble() }, p.types, c.toString())
    }

    @Test
    fun `定型のアドバイス文`() = cases("template_advice").forEach { c ->
        val f = c.obj("facts")!!
        val out = f.obj("outdoor")
        val facts = AdviceFacts(
            "general", f.s("classroom"), f.d("indoor_temp"), null, null, null, "", true, f.s("verdict"), "",
            f.d("need_clo"), 0.0, strings(f["removable"]), emptyList(),
            out?.let { OutdoorInfo(it.d("temp"), null, null, it.s("verdict"), "", 0.0) }, "",
        )
        val r = Coach.template(facts)
        assertEquals(c.obj("result")!!.s("advice"), r.advice, c.toString())
        assertEquals(c.obj("result")!!.s("tip"), r.tip, c.toString())
    }

    @Test
    fun `服の四角の切り抜き範囲`() = cases("clamp_box").forEach { c ->
        val b = c.obj("bbox")!!
        val r = jp.climafit.backend.image.Crop.clampBox(Box(b.d("x"), b.d("y"), b.d("w"), b.d("h")))
        val e = c["box"]
        if (e is JsonNull) assertEquals(null, r?.toList(), c.toString())
        else assertEquals(e!!.jsonArray.map { (it as JsonPrimitive).content.toDouble() }, r!!.toList(), c.toString())
    }
}
