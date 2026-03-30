package de.snowii.extractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import de.snowii.extractor.Extractor
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.enchantment.Enchantment

class Enchantments : Extractor.Extractor {
    override fun fileName(): String {
        return "enchantments.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val finalJson = JsonObject()

        val registry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)

        val ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE)

        registry.listElements().forEach { holder ->
            val enchantment = holder.value()
            val key = holder.key()

            val json = Enchantment.DIRECT_CODEC.encodeStart(
                ops,
                enchantment
            ).getOrThrow().asJsonObject

            json.addProperty("id", registry.getId(enchantment))

            finalJson.add(
                key.identifier().toString(),
                json
            )
        }

        return finalJson
    }
}