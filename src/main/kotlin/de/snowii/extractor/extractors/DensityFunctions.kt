package de.snowii.extractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mojang.serialization.JsonOps
import de.snowii.extractor.Extractor
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.CubicSpline
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.DensityFunctions
import net.minecraft.world.level.levelgen.NoiseRouter

class DensityFunctions : Extractor.Extractor {
    override fun fileName(): String = "density_function.json"

    /**
     * Extracts the driving DensityFunction from a CubicSpline.Multipoint's location-function
     * field, handling every wrapping layer that Mojang uses across versions:
     *
     *   26.1  record Multipoint<C, I>(ToFloatFunction<C> locationFunction, …)
     *         where C = DensityFunctions.Spline.Point  and  I = DensityFunctions.Spline.Coordinate
     *         The field value is a DensityFunctions.Spline.Coordinate which exposes .function()
     *         returning a Holder<DensityFunction>.
     *
     *   Older NeoForge / Paper: same structure but sometimes the field is named "coordinate"
     *   and typed directly as Coordinate.
     *
     * Strategy: iterate ALL declared fields (including inherited via superclass chain),
     * skip primitive/array/String fields, and try every object field until one yields
     * a DensityFunction we can serialize.
     */
    private fun extractLocationFunction(spline: CubicSpline.Multipoint<*, *>): JsonElement {
        // Walk the whole class hierarchy so we don't miss fields on superclasses/records
        val allFields = buildList {
            var cls: Class<*>? = spline.javaClass
            while (cls != null) {
                addAll(cls.declaredFields)
                cls = cls.superclass
            }
        }

        for (field in allFields) {
            if (field.name.first().isUpperCase()) continue          // skip CODEC etc.
            val type = field.type
            if (type.isPrimitive || type.isArray) continue          // skip float[], etc.
            if (type == String::class.java) continue

            field.isAccessible = true
            val v = try { field.get(spline) } catch (_: Exception) { continue } ?: continue

            // Case 1: it IS a DensityFunction directly
            if (v is DensityFunction) {
                return serializeFunction(v)
            }

            // Case 2: it is a Spline.Coordinate wrapping a Holder<DensityFunction>
            if (v is DensityFunctions.Spline.Coordinate) {
                return serializeFunction(v.function().value())
            }

            // Case 3: unknown wrapper — try calling a no-arg "function" method on it
            // (handles future renames / extra indirection layers)
            try {
                val functionMethod = v.javaClass.getMethod("function")
                val result = functionMethod.invoke(v)
                if (result is DensityFunction) return serializeFunction(result)
                // Holder<DensityFunction>
                if (result is Holder<*>) {
                    val inner = result.value()
                    if (inner is DensityFunction) return serializeFunction(inner)
                }
            } catch (_: Exception) { /* method doesn't exist on this type */ }
        }

        throw IllegalStateException(
            "Could not extract locationFunction from ${spline.javaClass.name}. " +
                    "Fields: ${spline.javaClass.declaredFields.map { it.name + ":" + it.type.simpleName }}"
        )
    }

    private fun serializeSpline(spline: CubicSpline<*, *>): JsonObject {
        val obj = JsonObject()

        when (spline) {
            is CubicSpline.Multipoint<*, *> -> {
                obj.add("_type", JsonPrimitive("standard"))

                val value = JsonObject()
                value.add("locationFunction", extractLocationFunction(spline))

                val locationArr = JsonArray()
                spline.locations().forEach { locationArr.add(it) }
                value.add("locations", locationArr)

                val valueArr = JsonArray()
                spline.values().forEach { valueArr.add(serializeSpline(it)) }
                value.add("values", valueArr)

                val derivativeArr = JsonArray()
                spline.derivatives().forEach { derivativeArr.add(it) }
                value.add("derivatives", derivativeArr)

                obj.add("value", value)
            }

            is CubicSpline.Constant -> {
                obj.add("_type", JsonPrimitive("fixed"))
                val value = JsonObject()
                value.add("value", JsonPrimitive(spline.value()))
                obj.add("value", value)
            }
        }

        return obj
    }

    private fun noiseHolderPath(holder: DensityFunction.NoiseHolder): String {
        val key = holder.noiseData().unwrapKey().orElse(null)
        return key?.identifier()?.path ?: "inline"
    }

    private fun serializeFunction(function: DensityFunction): JsonObject {
        // Unwrap registry holders transparently
        if (function is DensityFunctions.HolderHolder) {
            return serializeFunction(function.function().value())
        }

        val obj = JsonObject()

        // ── Marker / Wrapping ──────────────────────────────────────────────────
        // All Marker subtypes (FlatCache, Cache2D, Interpolated, CacheOnce,
        // CacheAllInCell) serialize as "Wrapping" with a "type" discriminator.
        // Rust WrapperType renames:
        //   flat_cache        → "FlatCache"    (Rust: CacheFlat, rename = "FlatCache")
        //   cache_2d          → "Cache2D"      (Rust: Cache2D,   no rename needed)
        //   interpolated      → "Interpolated" (Rust: Interpolated)
        //   cache_once        → "CacheOnce"    (Rust: CacheOnce)
        //   cache_all_in_cell → "CellCache"    (Rust: CellCache, rename = "CellCache" implied)
        if (function is DensityFunctions.Marker) {
            val rustTypeName = when (function.type().serializedName) {
                "flat_cache"        -> "FlatCache"   // Rust rename = "FlatCache"
                "cache_2d"          -> "Cache2D"
                "interpolated"      -> "Interpolated"
                "cache_once"        -> "CacheOnce"
                "cache_all_in_cell" -> "CellCache"
                else -> function.type().serializedName
            }
            obj.add("_class", JsonPrimitive("Wrapping"))
            val value = JsonObject()
            value.add("type", JsonPrimitive(rustTypeName))
            // Rust field: #[serde(rename(deserialize = "wrapped"))] input
            value.add("wrapped", serializeFunction(function.wrapped()))
            obj.add("value", value)
            return obj
        }

        // ── Spline ────────────────────────────────────────────────────────────
        // Rust: Spline { spline: SplineRepr, #[serde(flatten)] data: SplineData }
        // where SplineData has minValue / maxValue.
        // With content = "value", the JSON value object must contain all three keys.
        if (function is DensityFunctions.Spline) {
            obj.add("_class", JsonPrimitive("Spline"))
            val value = JsonObject()
            value.add("minValue", JsonPrimitive(function.minValue()))
            value.add("maxValue", JsonPrimitive(function.maxValue()))
            value.add("spline", serializeSpline(function.spline()))
            obj.add("value", value)
            return obj
        }

        // ── Constant ──────────────────────────────────────────────────────────
        // Rust: Constant { value: HashableF64 }  (content = "value" → object with "value" key)
        if (function is DensityFunctions.Constant) {
            obj.add("_class", JsonPrimitive("Constant"))
            val value = JsonObject()
            value.add("value", JsonPrimitive(function.value()))
            obj.add("value", value)
            return obj
        }

        // ── Stateless singletons ──────────────────────────────────────────────
        // BlendAlpha, BlendOffset, Beardifier, EndIslands have no "value" object.
        if (function is DensityFunctions.BlendAlpha) {
            obj.add("_class", JsonPrimitive("BlendAlpha"))
            return obj
        }
        if (function is DensityFunctions.BlendOffset) {
            obj.add("_class", JsonPrimitive("BlendOffset"))
            return obj
        }
        if (function is DensityFunctions.BeardifierOrMarker) {
            obj.add("_class", JsonPrimitive("Beardifier"))
            return obj
        }
        if (function is DensityFunctions.EndIslandDensityFunction) {
            obj.add("_class", JsonPrimitive("EndIslands"))
            return obj
        }

        // ── BlendDensity ──────────────────────────────────────────────────────
        // Rust: BlendDensity { input: Box<Self> }
        if (function is DensityFunctions.BlendDensity) {
            obj.add("_class", JsonPrimitive("BlendDensity"))
            val value = JsonObject()
            value.add("input", serializeFunction(function.input()))
            obj.add("value", value)
            return obj
        }

        // ── TwoArgumentSimpleFunction (Binary / Linear) ───────────────────────
        // MulOrAdd  → LinearOperation  (fields: specificType, input, argument, minValue, maxValue)
        // Ap2       → BinaryOperation  (fields: type, argument1, argument2, minValue, maxValue)
        if (function is DensityFunctions.TwoArgumentSimpleFunction) {
            val value = JsonObject()

            // Check for the optimised MulOrAdd path first
            val mulOrAddClass = try {
                Class.forName("net.minecraft.world.level.levelgen.DensityFunctions\$MulOrAdd")
            } catch (_: ClassNotFoundException) { null }

            if (mulOrAddClass != null && mulOrAddClass.isInstance(function)) {
                // LinearOperation
                obj.add("_class", JsonPrimitive("LinearOperation"))

                val specificTypeField = mulOrAddClass.getDeclaredField("specificType")
                specificTypeField.isAccessible = true
                val specificType = specificTypeField.get(function) as Enum<*>
                value.add("specificType", JsonPrimitive(specificType.name)) // ADD or MUL

                val inputField = mulOrAddClass.getDeclaredField("input")
                inputField.isAccessible = true
                value.add("input", serializeFunction(inputField.get(function) as DensityFunction))

                val argumentField = mulOrAddClass.getDeclaredField("argument")
                argumentField.isAccessible = true
                value.add("argument", JsonPrimitive(argumentField.get(function) as Double))

                val minValueField = mulOrAddClass.getDeclaredField("minValue")
                minValueField.isAccessible = true
                value.add("minValue", JsonPrimitive(minValueField.get(function) as Double))

                val maxValueField = mulOrAddClass.getDeclaredField("maxValue")
                maxValueField.isAccessible = true
                value.add("maxValue", JsonPrimitive(maxValueField.get(function) as Double))

            } else {
                // BinaryOperation (Ap2)
                obj.add("_class", JsonPrimitive("BinaryOperation"))

                // type() is on the interface; safe to call directly
                val typeEnum = function.type() as Enum<*>
                value.add("type", JsonPrimitive(typeEnum.name)) // ADD, MUL, MIN, MAX

                value.add("argument1", serializeFunction(function.argument1()))
                value.add("argument2", serializeFunction(function.argument2()))

                // minValue / maxValue are stored on the concrete Ap2 record
                val ap2Class = function.javaClass
                val minF = ap2Class.getDeclaredField("minValue")
                minF.isAccessible = true
                value.add("minValue", JsonPrimitive(minF.get(function) as Double))

                val maxF = ap2Class.getDeclaredField("maxValue")
                maxF.isAccessible = true
                value.add("maxValue", JsonPrimitive(maxF.get(function) as Double))
            }

            obj.add("value", value)
            return obj
        }

        // ── Mapped (UnaryOperation) ───────────────────────────────────────────
        // Rust: Unary { input, #[serde(flatten)] data: UnaryData }
        // UnaryData.operation = UnaryOperation deserialized from "ABS", "SQUARE", …
        if (function is DensityFunctions.Mapped) {
            obj.add("_class", JsonPrimitive("UnaryOperation"))
            val value = JsonObject()
            // serializedName: abs, square, cube, half_negative, quarter_negative, squeeze
            value.add("type", JsonPrimitive(function.type().serializedName.uppercase()))
            value.add("input", serializeFunction(function.input()))
            value.add("minValue", JsonPrimitive(function.minValue()))
            value.add("maxValue", JsonPrimitive(function.maxValue()))
            obj.add("value", value)
            return obj
        }

        // ── Clamp ─────────────────────────────────────────────────────────────
        // Rust: Clamp { input, #[serde(flatten)] data: ClampData { minValue, maxValue } }
        if (function is DensityFunctions.Clamp) {
            obj.add("_class", JsonPrimitive("Clamp"))
            val value = JsonObject()
            value.add("input", serializeFunction(function.input()))
            value.add("minValue", JsonPrimitive(function.minValue()))
            value.add("maxValue", JsonPrimitive(function.maxValue()))
            obj.add("value", value)
            return obj
        }

        // ── RangeChoice ───────────────────────────────────────────────────────
        // Rust: RangeChoice { input, whenInRange, whenOutOfRange, minInclusive, maxExclusive }
        if (function is DensityFunctions.RangeChoice) {
            obj.add("_class", JsonPrimitive("RangeChoice"))
            val value = JsonObject()
            value.add("input", serializeFunction(function.input()))
            value.add("whenInRange", serializeFunction(function.whenInRange()))
            value.add("whenOutOfRange", serializeFunction(function.whenOutOfRange()))
            value.add("minInclusive", JsonPrimitive(function.minInclusive()))
            value.add("maxExclusive", JsonPrimitive(function.maxExclusive()))
            obj.add("value", value)
            return obj
        }

        // ── Noise ─────────────────────────────────────────────────────────────
        // Rust: Noise { #[serde(flatten)] data: NoiseData { noise, xzScale, yScale } }
        if (function is DensityFunctions.Noise) {
            obj.add("_class", JsonPrimitive("Noise"))
            val value = JsonObject()
            value.add("noise", JsonPrimitive(noiseHolderPath(function.noise())))
            value.add("xzScale", JsonPrimitive(function.xzScale()))
            value.add("yScale", JsonPrimitive(function.yScale()))
            obj.add("value", value)
            return obj
        }

        // ── ShiftA / ShiftB / Shift ───────────────────────────────────────────
        // Rust: ShiftA { offsetNoise: String }  (the path string)
        if (function is DensityFunctions.ShiftA) {
            obj.add("_class", JsonPrimitive("ShiftA"))
            val value = JsonObject()
            value.add("offsetNoise", JsonPrimitive(noiseHolderPath(function.offsetNoise())))
            obj.add("value", value)
            return obj
        }
        if (function is DensityFunctions.ShiftB) {
            obj.add("_class", JsonPrimitive("ShiftB"))
            val value = JsonObject()
            value.add("offsetNoise", JsonPrimitive(noiseHolderPath(function.offsetNoise())))
            obj.add("value", value)
            return obj
        }

        // ── ShiftedNoise ──────────────────────────────────────────────────────
        // Rust: ShiftedNoise { shiftX, shiftY, shiftZ, #[flatten] ShiftedNoiseData { xzScale, yScale, noise } }
        if (function is DensityFunctions.ShiftedNoise) {
            obj.add("_class", JsonPrimitive("ShiftedNoise"))
            val value = JsonObject()
            value.add("shiftX", serializeFunction(function.shiftX()))
            value.add("shiftY", serializeFunction(function.shiftY()))
            value.add("shiftZ", serializeFunction(function.shiftZ()))
            value.add("xzScale", JsonPrimitive(function.xzScale()))
            value.add("yScale", JsonPrimitive(function.yScale()))
            value.add("noise", JsonPrimitive(noiseHolderPath(function.noise())))
            obj.add("value", value)
            return obj
        }

        // ── WeirdScaledSampler ────────────────────────────────────────────────
        // Rust: WeirdScaled { input, #[flatten] WeirdScaledData { noise, rarityValueMapper } }
        // rarityValueMapper enum: TYPE1 → Tunnels, TYPE2 → Caves
        if (function is DensityFunctions.WeirdScaledSampler) {
            obj.add("_class", JsonPrimitive("WeirdScaledSampler"))
            val value = JsonObject()
            value.add("input", serializeFunction(function.input()))
            value.add("noise", JsonPrimitive(noiseHolderPath(function.noise())))
            value.add("rarityValueMapper", JsonPrimitive(function.rarityValueMapper().name))
            obj.add("value", value)
            return obj
        }

        // ── InterpolatedNoiseSampler (BlendedNoise / OldBlendedNoise) ────────
        // Rust InterpolatedNoiseSamplerData expects (via flatten):
        //   scaledXzScale, scaledYScale, xzFactor, yFactor, smearScaleMultiplier, maxValue
        //
        // Java BlendedNoise record fields are: xzScale, yScale, xzFactor, yFactor,
        // smearScaleMultiplier. The "scaled" variants are xzScale*xzFactor/80.0 and
        // yScale*yFactor/80.0 (matching the Java BlendedNoise constructor arithmetic).
        val className = function.javaClass.simpleName
        if (className == "OldBlendedNoise" || className == "BlendedNoise") {
            obj.add("_class", JsonPrimitive("InterpolatedNoiseSampler"))
            val value = JsonObject()

            fun getDouble(fieldName: String): Double {
                var cls: Class<*>? = function.javaClass
                while (cls != null) {
                    try {
                        val f = cls.getDeclaredField(fieldName)
                        f.isAccessible = true
                        return f.get(function) as Double
                    } catch (_: NoSuchFieldException) {}
                    cls = cls.superclass
                }
                val available = buildList {
                    var c: Class<*>? = function.javaClass
                    while (c != null) { addAll(c.declaredFields.map { it.name }); c = c.superclass }
                }
                throw NoSuchFieldException(
                    "Field \'$fieldName\' not found on ${function.javaClass.name}. Available: $available"
                )
            }

            val xzScale  = getDouble("xzScale")
            val yScale   = getDouble("yScale")
            val xzFactor = getDouble("xzFactor")
            val yFactor  = getDouble("yFactor")
            val smear    = getDouble("smearScaleMultiplier")

            value.add("scaledXzScale",        JsonPrimitive(xzScale * xzFactor / 80.0))
            value.add("scaledYScale",          JsonPrimitive(yScale  * yFactor  / 80.0))
            value.add("xzFactor",             JsonPrimitive(xzFactor))
            value.add("yFactor",              JsonPrimitive(yFactor))
            value.add("smearScaleMultiplier", JsonPrimitive(smear))
            value.add("maxValue",             JsonPrimitive(function.maxValue()))

            obj.add("value", value)
            return obj
        }

        // ── YClampedGradient ──────────────────────────────────────────────────
        // Rust: ClampedYGradient { #[flatten] ClampedYGradientData { fromY, toY, fromValue, toValue } }
        if (function is DensityFunctions.YClampedGradient) {
            obj.add("_class", JsonPrimitive("YClampedGradient"))
            val value = JsonObject()
            value.add("fromY", JsonPrimitive(function.fromY()))
            value.add("toY", JsonPrimitive(function.toY()))
            value.add("fromValue", JsonPrimitive(function.fromValue()))
            value.add("toValue", JsonPrimitive(function.toValue()))
            obj.add("value", value)
            return obj
        }

        // ── FindTopSurface ────────────────────────────────────────────────────
        // New in 26.1. Rust parser has no variant for this yet; serialize it
        // so the data is present and the Rust side can be extended later.
        // Fields (private record): density, upperBound, lowerBound, cellHeight
        val simpleName = function.javaClass.simpleName
        if (simpleName == "FindTopSurface") {
            obj.add("_class", JsonPrimitive("FindTopSurface"))
            val value = JsonObject()
            val cls = function.javaClass
            for (fieldName in listOf("density", "upperBound", "lowerBound", "cellHeight")) {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                when (val v = f.get(function)) {
                    is DensityFunction -> value.add(fieldName, serializeFunction(v))
                    is Int             -> value.add(fieldName, JsonPrimitive(v))
                    is Double          -> value.add(fieldName, JsonPrimitive(v))
                    else               -> value.add(fieldName, JsonPrimitive(v.toString()))
                }
            }
            obj.add("value", value)
            return obj
        }

        throw IllegalArgumentException(
            "Unhandled DensityFunction type: ${function.javaClass.name}"
        )
    }

    private fun serializeRouter(router: NoiseRouter): JsonObject {
        val obj = JsonObject()

        // NoiseRouter is a Java record — use its public accessor methods so we are
        // not sensitive to field-name obfuscation or reordering between versions.
        // JSON keys must exactly match the Rust NoiseRouterRepr field names
        // (accounting for serde rename annotations on the Rust side).
        fun add(jsonKey: String, fn: DensityFunction) =
            obj.add(jsonKey, serializeFunction(fn))

        add("barrierNoise",                   router.barrierNoise())
        add("fluidLevelFloodednessNoise",      router.fluidLevelFloodednessNoise())
        add("fluidLevelSpreadNoise",           router.fluidLevelSpreadNoise())
        add("lavaNoise",                       router.lavaNoise())
        add("temperature",                     router.temperature())
        add("vegetation",                      router.vegetation())
        add("continents",                      router.continents())
        add("erosion",                         router.erosion())
        add("depth",                           router.depth())
        add("ridges",                          router.ridges())
        add("preliminarySurfaceLevel",         router.preliminarySurfaceLevel())
        add("finalDensity",                    router.finalDensity())
        add("veinToggle",                      router.veinToggle())
        add("veinRidged",                      router.veinRidged())
        add("veinGap",                         router.veinGap())

        return obj
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()
        val registry = server.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS)

        registry.listElements().forEach { entry ->
            val settings = entry.value()
            val path = entry.key().identifier().path
            topLevelJson.add(path, serializeRouter(settings.noiseRouter()))
        }
        return topLevelJson
    }

    inner class Tests : Extractor.Extractor {
        override fun fileName(): String = "density_function_tests.json"

        override fun extract(server: MinecraftServer): JsonElement {
            val topLevelJson = JsonObject()
            val registryAccess = server.registryAccess()

            val functionNames = arrayOf(
                "overworld/base_3d_noise",
                "overworld/caves/entrances",
                "overworld/caves/noodle",
                "overworld/caves/pillars",
                "overworld/caves/spaghetti_2d",
                "overworld/caves/spaghetti_2d_thickness_modulator",
                "overworld/caves/spaghetti_roughness_function",
                "overworld/offset",
                "overworld/depth",
                "overworld/factor",
                "overworld/sloped_cheese"
            )

            val functionLookup = registryAccess.lookupOrThrow(Registries.DENSITY_FUNCTION)

            for (functionName in functionNames) {
                val functionKey = ResourceKey.create(
                    Registries.DENSITY_FUNCTION,
                    Identifier.withDefaultNamespace(functionName)
                )

                val holder = functionLookup.get(functionKey).orElse(null)
                if (holder != null) {
                    topLevelJson.add(functionName, serializeFunction(holder.value()))
                } else {
                    println("Warning: Density function $functionName not found in registry.")
                }
            }

            return topLevelJson
        }
    }
}