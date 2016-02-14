package biz.neustar.clouds.chat.util;

import java.io.StringWriter;
import java.io.Writer;
import java.text.DateFormat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonWriter;

public class JsonUtil {

	private static final Gson gson = new GsonBuilder()
	.setDateFormat(DateFormat.FULL, DateFormat.FULL)
	.disableHtmlEscaping()
	.serializeNulls()
	.create();

	public static String toString(JsonElement jsonElement) {

		StringWriter stringWriter = new StringWriter();
		JsonWriter jsonWriter = new JsonWriter(stringWriter);
		gson.toJson(jsonElement, jsonWriter);
		return stringWriter.getBuffer().toString();
	}

	public static void write(Writer writer, JsonElement jsonElement) {

		JsonWriter jsonWriter = new JsonWriter(writer);
		jsonWriter.setIndent("  ");
		gson.toJson(jsonElement, jsonWriter);
	}
}
