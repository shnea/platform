package kr.shnea.platform.project;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Defense in depth; callers must never send secrets or raw request bodies. */
final class LogSanitizer {
 private static final JsonMapper JSON = new JsonMapper();
 private static final Pattern KEY = Pattern.compile("(?i).*(password|passwd|secret|token|authorization|cookie|api.?key|credential|otp|verification.?code|email|phone|address|request.?body|response.?body).*", Pattern.DOTALL);
 private static final Pattern VALUE = Pattern.compile("(?i)(bearer\\s+[^\\s\"',;]+|pk_[a-z0-9-]+_[^\\s\"',;]+|sk-[a-z0-9_-]+|eyJ[a-z0-9_-]+\\.[a-z0-9_-]+\\.[a-z0-9_-]+|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,})");
 private static final Pattern PAIR = Pattern.compile("(?i)((?:password|passwd|secret|token|authorization|cookie|api[_-]?key|otp|verification[_-]?code)\\s*[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)");
 static String text(String value) {
  if(value==null)return null;
  return VALUE.matcher(PAIR.matcher(value).replaceAll("$1[REDACTED]")).replaceAll("[REDACTED]");
 }
 static JsonNode attributes(JsonNode input,int depth) {
  if(input==null||input.isNull())return depth==0?JSON.createObjectNode():JSON.getNodeFactory().nullNode();
  if(depth>5)throw ApiCode.INVALID_REQUEST.failure();
  if(input.isObject()) {
   if(input.size()>32)throw ApiCode.INVALID_REQUEST.failure();
   var result=JSON.createObjectNode();
   for(String key:input.propertyNames()) {
    if(key.length()>64)throw ApiCode.INVALID_REQUEST.failure();
    if(KEY.matcher(key).matches())result.put(key,"[REDACTED]");
    else result.set(key,attributes(input.get(key),depth+1));
   }
   return result;
  }
  if(input.isArray()) {
   if(input.size()>32)throw ApiCode.INVALID_REQUEST.failure();
   var result=JSON.createArrayNode();for(var value:input)result.add(attributes(value,depth+1));return result;
  }
  return input.isString()?JSON.getNodeFactory().stringNode(text(input.asText())):input;
 }
 private LogSanitizer() {}
}
