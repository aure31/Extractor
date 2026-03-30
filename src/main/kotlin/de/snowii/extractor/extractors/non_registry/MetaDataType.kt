package de.snowii.extractor.extractors.non_registry

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import de.snowii.extractor.Extractor
import net.minecraft.network.syncher.EntityDataSerializer
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.server.MinecraftServer
import java.lang.reflect.Modifier

class MetaDataType : Extractor.Extractor {
    override fun fileName(): String {
        return "meta_data_type.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val jsonObject = JsonObject()

        val serializerClass = EntityDataSerializers::class.java

        for (field in serializerClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers) && EntityDataSerializer::class.java.isAssignableFrom(field.type)) {

                try {
                    field.isAccessible = true
                    val serializer = field.get(null) as EntityDataSerializer<*>

                    val id = EntityDataSerializers.getSerializedId(serializer)

                    if (id != -1) {
                        jsonObject.addProperty(field.name.lowercase(), id)
                    }
                } catch (e: Exception) {
                    // Skip internal fields that might not be initialized
                }
            }
        }

        return jsonObject
    }
}