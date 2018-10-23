package net.shadowmage.ancientwarfare.core.util.parsing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.JsonUtils;
import net.minecraft.util.NonNullList;
import net.minecraft.util.Tuple;
import net.shadowmage.ancientwarfare.core.AncientWarfareCore;
import net.shadowmage.ancientwarfare.core.util.BlockTools;
import net.shadowmage.ancientwarfare.core.util.RegistryTools;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

public class JsonHelper {

	public static IBlockState getBlockState(JsonObject parent, String elementName) {
		return getBlockState(parent, elementName, Block::getDefaultState, BlockTools::updateProperty);
	}

	public static BlockStateMatcher getBlockStateMatcher(JsonElement stateJson) {
		return getBlockStateMatcher(JsonUtils.getJsonObject(stateJson, ""));
	}

	public static BlockStateMatcher getBlockStateMatcher(JsonObject stateJson) {
		//noinspection ConstantConditions
		return getBlockState(stateJson, BlockStateMatcher::new, BlockStateMatcher::addProperty);
	}

	public static BlockStateMatcher getBlockStateMatcher(JsonObject parent, String elementName) {
		//noinspection ConstantConditions
		return getBlockState(parent, elementName, BlockStateMatcher::new, BlockStateMatcher::addProperty);
	}

	public static ItemStack getItemStack(JsonElement json) {
		return getItemStack(json, (i, c, m, t) -> {
			ItemStack stack = new ItemStack(i, c, m);
			stack.setTagCompound(t);
			return stack;
		});
	}

	public static ItemStack getItemStack(JsonObject json, String elementName) {
		if (!JsonUtils.hasField(json, elementName)) {
			throw new JsonParseException("Expected " + elementName + " member in " + json.toString());
		}

		return getItemStack(json.get(elementName));
	}

	private static <T> T getItemStack(JsonElement element, ItemStackCreator<T> creator) {
		if (element.isJsonPrimitive()) {
			return creator.instantiate(RegistryTools.getItem(element.getAsString()), 1, -1, null);
		}

		JsonObject obj = element.getAsJsonObject();
		String registryName = JsonUtils.getString(obj, "name");
		Item item = RegistryTools.getItem(registryName);

		int count = JsonUtils.hasField(obj, "count") ? JsonUtils.getInt(obj, "count") : 1;

		int meta = -1;
		if (JsonUtils.hasField(obj, "data")) {
			meta = JsonUtils.getInt(obj, "data");
		}
		NBTTagCompound tagCompound = null;
		if (JsonUtils.hasField(obj, "nbt")) {
			try {
				tagCompound = JsonToNBT.getTagFromJson(JsonUtils.getString(obj, "nbt"));
			}
			catch (NBTException e) {
				AncientWarfareCore.LOG.error("Error reading item stack nbt ", JsonUtils.getJsonObject(obj, "nbt"));
			}
		}

		return creator.instantiate(item, count, meta, tagCompound);
	}

	public static ItemStackMatcher getItemStackMatcher(JsonElement element) {
		return getItemStack(element, (i, c, m, t) -> new ItemStackMatcher.Builder(i).setMeta(m).setTagCompound(t).build());
	}

	public static ItemStackMatcher getItemStackMatcher(JsonObject parent, String elementName) {
		if (!JsonUtils.hasField(parent, elementName)) {
			throw new JsonParseException("Expected " + elementName + " member in " + parent.toString());
		}

		return getItemStackMatcher(parent.get(elementName));
	}

	private static <T> T getBlockState(JsonObject stateJson, Function<Block, T> init, BlockTools.AddPropertyFunction<T> addProperty) {
		return BlockTools.getBlockState(getBlockNameAndProperties(stateJson), init, addProperty);
	}

	private static <T> T getBlockState(JsonObject parent, String elementName, Function<Block, T> init, BlockTools.AddPropertyFunction<T> addProperty) {
		return BlockTools.getBlockState(getBlockNameAndProperties(parent, elementName), init, addProperty);
	}

	private static Tuple<String, Map<String, String>> getBlockNameAndProperties(JsonObject stateJson) {
		Map<String, String> properties = new HashMap<>();

		if (JsonUtils.hasField(stateJson, "properties")) {
			JsonUtils.getJsonObject(stateJson, "properties").entrySet().forEach(p -> properties.put(p.getKey(), p.getValue().getAsString()));
		}

		return new Tuple<>(JsonUtils.getString(stateJson, "name"), properties);
	}

	private static Tuple<String, Map<String, String>> getBlockNameAndProperties(JsonObject parent, String elementName) {
		if (!JsonUtils.hasField(parent, elementName)) {
			throw new JsonParseException("Expected " + elementName + " member in " + parent.toString());
		}

		if (JsonUtils.isJsonPrimitive(parent, elementName)) {
			return new Tuple<>(JsonUtils.getString(parent, elementName), new HashMap<>());
		}

		return getBlockNameAndProperties(JsonUtils.getJsonObject(parent, elementName));
	}

	public static Predicate<IBlockState> getBlockStateMatcher(JsonObject json, String arrayElement, String individualElement) {
		if (json.has(arrayElement)) {
			JsonArray stateMatchers = JsonUtils.getJsonArray(json, arrayElement);
			return new MultiBlockStateMatcher(StreamSupport.stream(stateMatchers.spliterator(), false)
					.map(e -> getBlockStateMatcher(JsonUtils.getJsonObject(e, individualElement)))
					.toArray(BlockStateMatcher[]::new));
		}
		return getBlockStateMatcher(json, individualElement);
	}

	public static PropertyState getPropertyState(IBlockState state, JsonObject parent, String elementName) {
		JsonObject jsonProperty = JsonUtils.getJsonObject(parent, elementName);

		if (jsonProperty.entrySet().isEmpty()) {
			throw new JsonParseException("Expected at least one property defined for " + elementName + " in " + parent.toString());
		}

		Entry<String, JsonElement> propJson = jsonProperty.entrySet().iterator().next();
		String propName = propJson.getKey();
		String propValue = propJson.getValue().getAsString();

		return BlockTools.getPropertyState(state.getBlock(), state.getBlock().getBlockState(), propName, propValue);
	}

	public static PropertyStateMatcher getPropertyStateMatcher(IBlockState state, JsonObject parent, String elementName) {
		return new PropertyStateMatcher(getPropertyState(state, parent, elementName));
	}

	public static <K, V> Map<K, V> mapFromJson(JsonObject json, String propertyName, Function<Entry<String, JsonElement>, K> parseKey,
			Function<Entry<String, JsonElement>, V> parseValue) {
		return mapFromObjectProperties(JsonUtils.getJsonObject(json, propertyName), new HashMap<>(), parseKey, parseValue);
	}

	public static <K, V> Map<K, V> mapFromJson(JsonElement json, Function<Entry<String, JsonElement>, K> parseKey,
			Function<Entry<String, JsonElement>, V> parseValue) {
		return mapFromObjectProperties(JsonUtils.getJsonObject(json, ""), new HashMap<>(), parseKey, parseValue);
	}

	public static <K, V> void mapFromJson(JsonObject json, String propertyName, Map<K, V> ret, Function<Entry<String, JsonElement>, K> parseKey,
			Function<Entry<String, JsonElement>, V> parseValue) {
		mapFromObjectProperties(JsonUtils.getJsonObject(json, propertyName), ret, parseKey, parseValue);
	}

	public static <K, V> Map<K, V> mapFromObjectArray(JsonArray jsonArray, String keyName, String valueName, Function<JsonElement, K> parseKey,
			Function<JsonElement, V> parseValue) {
		Map<K, V> ret = new HashMap<>();
		for (JsonElement element : jsonArray) {
			JsonObject entry = JsonUtils.getJsonObject(element, "");
			ret.put(parseKey.apply(entry.get(keyName)), parseValue.apply(entry.get(valueName)));
		}
		return ret;
	}

	private static <K, V> Map<K, V> mapFromObjectProperties(JsonObject jsonObject, Map<K, V> ret, Function<Entry<String, JsonElement>, K> parseKey,
			Function<Entry<String, JsonElement>, V> parseValue) {

		for (Map.Entry<String, JsonElement> pair : jsonObject.entrySet()) {
			ret.put(parseKey.apply(pair), parseValue.apply(pair));
		}

		return ret;
	}

	public static List<ItemStack> getItemStacks(JsonArray stacks) {
		List<ItemStack> ret = NonNullList.create();
		for (JsonElement stackElement : stacks) {
			ret.add(getItemStack(JsonUtils.getJsonObject(stackElement, "itemstack")));
		}
		return ret;
	}

	public static <V> Set<V> setFromJson(JsonElement element, Function<JsonElement, V> getElement) {
		return setFromJson(JsonUtils.getJsonArray(element, ""), getElement);
	}

	private static <V> Set<V> setFromJson(JsonArray array, Function<JsonElement, V> getElement) {
		Set<V> ret = new HashSet<>();

		for (JsonElement element : array) {
			ret.add(getElement.apply(element));
		}

		return ret;
	}

	private interface ItemStackCreator<R> {
		R instantiate(Item item, int count, int meta, @Nullable NBTTagCompound tagCompound);
	}
}
