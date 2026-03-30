package de.snowii.extractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import de.snowii.extractor.Extractor
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.alchemy.PotionBrewing
import net.minecraft.world.item.crafting.Ingredient

class PotionBrewing : Extractor.Extractor {
    override fun fileName(): String {
        return "potion_brewing.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()
        val reg = server.potionBrewing()
        val clazz = PotionBrewing::class.java

        // 1. Potion Types (containers)
        val containersField = clazz.getDeclaredField("containers").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val containers = containersField.get(reg) as List<Ingredient>
        val typesArray = JsonArray()
        for (ingredient in containers) {
            typesArray.add(ingredientToJson(ingredient))
        }
        json.add("potion_types", typesArray)

        // 2. Potion Recipes (potionMixes)
        val potionMixesField = clazz.getDeclaredField("potionMixes").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val potionMixes = potionMixesField.get(reg) as List<*>
        json.add("potion_recipes", encodeMixList(potionMixes))

        // 3. Item Recipes (containerMixes)
        val containerMixesField = clazz.getDeclaredField("containerMixes").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val containerMixes = containerMixesField.get(reg) as List<*>
        json.add("item_recipes", encodeMixList(containerMixes))

        return json
    }

    private fun encodeMixList(mixes: List<*>): JsonArray {
        val array = JsonArray()
        for (mix in mixes) {
            if (mix == null) continue
            val mixJson = JsonObject()
            val clazz = mix.javaClass

            // Accessing record components: from, ingredient, to
            try {
                // We use getDeclaredField even for records to ensure we find the private final fields
                val fromField = clazz.getDeclaredField("from").apply { isAccessible = true }
                val ingredientField = clazz.getDeclaredField("ingredient").apply { isAccessible = true }
                val toField = clazz.getDeclaredField("to").apply { isAccessible = true }

                val from = fromField.get(mix) as Holder<*>
                val ingredient = ingredientField.get(mix) as Ingredient
                val to = toField.get(mix) as Holder<*>

                // Add to JSON
                mixJson.addProperty("from", getHolderId(from))
                mixJson.add("ingredient", ingredientToJson(ingredient))
                mixJson.addProperty("to", getHolderId(to))

                array.add(mixJson)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return array
    }

    private fun getHolderId(holder: Holder<*>): String {
        return holder.unwrapKey().map { it.identifier().toString() }.orElse("unknown")
    }

    private fun ingredientToJson(ingredient: Ingredient): JsonArray {
        val array = JsonArray()
        // In 1.21.4 Mojmap, use .items to get Collection<Holder<Item>>
        ingredient.items().forEach { holder ->
            val id = BuiltInRegistries.ITEM.getKey(holder.value()).toString()
            if (!array.contains(com.google.gson.JsonPrimitive(id))) {
                array.add(id)
            }
        }
        return array
    }
}