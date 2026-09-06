package com.liban.android.model

import java.text.Normalizer

/** Shared vocabulary, not a mandatory template for every product. */
object ProductText {
    val labels = linkedMapOf(
        "model" to "型号", "style" to "款式", "material" to "材质", "gemstone" to "镶嵌",
        "storage_capacity" to "存储容量", "memory_capacity" to "运行内存", "color" to "颜色",
        "size" to "尺码", "net_content" to "净含量", "pack_count" to "包装数量",
        "duration" to "时长", "plan" to "套餐", "user_count" to "适用人数", "feature" to "特性",
    )
    val matchingKeys = labels.keys - "feature"
    private val aliases = mapOf("storage" to "storage_capacity", "ram" to "memory_capacity",
        "weight" to "net_content", "quantity" to "pack_count", "colour" to "color")
    private val types = listOf("智能手机", "笔记本电脑", "洗衣机", "电冰箱", "连衣裙", "运动鞋",
        "戒指", "项链", "手镯", "耳环", "耳机", "手机", "电脑", "相机", "电视", "平板", "冰箱",
        "空调", "牛奶", "酸奶", "饮料", "饼干", "食品", "衬衫", "裤子", "衣服", "鞋", "背包",
        "会员", "课程", "套餐", "服务", "智能设备")
    private val marketing = listOf("退货包运费", "七夕生日礼物送女友", "生日礼物送女友",
        "送男友", "送女友", "七夕礼物", "生日礼物", "限时抢购", "原创设计", "专卖店",
        "先用后付", "好评率", "本店已拼", "收藏", "客服", "购物车", "快抢光", "支持0元下单")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .uppercase().filter(Char::isLetterOrDigit)
    fun key(raw: String): String = aliases[raw.lowercase()] ?: raw.lowercase()
    fun inferType(title: String): String? = types.firstOrNull(title::contains)
    fun hasMarketing(text: String): Boolean = marketing.any(text::contains)
    fun cleanTitle(text: String): String {
        var result = text
        listOf("退货包运费", "七夕生日礼物送女友", "生日礼物送女友", "七夕礼物", "生日礼物",
            "送男友", "送女友", "专卖店", "包邮").forEach { result = result.replace(it, "") }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    /** Whole evidenced phrases may be reordered, but arbitrary character bags are not evidence. */
    fun composedOf(value: String, parts: List<String>): Boolean {
        val target = normalize(value)
        val words = parts.map(::normalize).filter(String::isNotEmpty).distinct()
        if (target.isEmpty() || target.length > 200) return false
        val reachable = BooleanArray(target.length + 1).also { it[0] = true }
        for (i in target.indices) if (reachable[i]) {
            words.forEach { word -> if (target.startsWith(word, i)) reachable[i + word.length] = true }
        }
        return reachable.last()
    }

    fun searchAttributes(product: Product): List<ProductAttribute> = product.attributes.filter {
        it.key in matchingKeys && it.scope in setOf(AttributeScope.PRODUCT, AttributeScope.SELECTED)
    }

    fun summary(product: Product): String = buildList {
        product.brand?.let { add("页面品牌：$it") }
        product.attributes.take(6).forEach {
            val scope = when (it.scope) {
                AttributeScope.MENTIONED -> "（可选，未确认选择）"
                AttributeScope.UNKNOWN -> "（归属不明）"
                AttributeScope.SELECTED -> "（已选）"
                AttributeScope.PRODUCT -> ""
            }
            add("${it.label}：${it.value}$scope")
        }
    }.joinToString(" · ")
}

