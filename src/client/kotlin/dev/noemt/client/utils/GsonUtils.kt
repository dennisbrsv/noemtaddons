package dev.noemt.client.utils

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import net.minecraft.core.BlockPos
import java.awt.Color
import java.lang.reflect.Type

object GsonUtils {
    val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .registerTypeAdapter(BlockPos::class.java, BlockPosAdapter())
        .registerTypeAdapter(Color::class.java, ColorAdapter())
        .registerTypeAdapter(Regex::class.java, RegexAdapter())
        .create()

    inline fun <reified T : Any> decode(json: String): T = gson.fromJson(json, object : TypeToken<T>() {}.type)
    fun encode(obj: Any): String = gson.toJson(obj)

    class ColorAdapter : JsonSerializer<Color>, JsonDeserializer<Color> {
        override fun serialize(src: Color, type: Type, ctx: JsonSerializationContext) = JsonPrimitive(src.rgb)
        override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext) = Color(json.asInt, true)
    }

    class RegexAdapter : JsonSerializer<Regex>, JsonDeserializer<Regex> {
        override fun serialize(src: Regex, type: Type, ctx: JsonSerializationContext) = JsonPrimitive(src.pattern)
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext) = Regex(json.asString)
    }

    class BlockPosAdapter : JsonSerializer<BlockPos>, JsonDeserializer<BlockPos> {
        override fun serialize(src: BlockPos, type: Type, ctx: JsonSerializationContext) = JsonObject().apply {
            addProperty("x", src.x)
            addProperty("y", src.y)
            addProperty("z", src.z)
        }

        override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): BlockPos {
            val obj = json.asJsonObject
            return BlockPos(obj.get("x").asInt, obj.get("y").asInt, obj.get("z").asInt)
        }
    }
}
