package kr.shnea.platform.project;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class CommonLogs {
 record Entry(Instant timestamp,String service,String level,String message,String requestId,String traceId,String errorCode,JsonNode attributes,String exception) {}
 record Batch(List<Entry> entries) {}
 record Accepted(int accepted) {}
 record Page(List<Entry> items,boolean limited,Instant from,Instant to,int retentionDays,long dailyBytesLimit) {}
 static final long DAILY_BYTES=10*1024*1024;
 private static final List<String> LEVELS=List.of("TRACE","DEBUG","INFO","WARN","ERROR","FATAL");
 private final JdbcTemplate db;private final ProjectService projects;private final URI base;
 private final JsonMapper json=new JsonMapper();
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 private final Semaphore slots=new Semaphore(8);
 CommonLogs(JdbcTemplate db,ProjectService projects,@Value("${platform.logs.url:http://loki:3100}")String url){this.db=db;this.projects=projects;this.base=URI.create(url);}
 private static void length(String text,int limit){if(text!=null&&text.length()>limit)throw ApiCode.PAYLOAD_TOO_LARGE.failure();}
 private Entry sanitize(Entry entry,Instant now) {
  if(entry==null||entry.message()==null||entry.level()==null||!LEVELS.contains(entry.level()))throw ApiCode.INVALID_REQUEST.failure();
  ExternalJobs.name(entry.service(),64);length(entry.message(),8192);length(entry.exception(),8192);
  for(String id:Arrays.asList(entry.requestId(),entry.traceId(),entry.errorCode()))if(id!=null)ExternalJobs.name(id,128);
  Instant timestamp=entry.timestamp()==null?now:entry.timestamp();
  if(timestamp.isBefore(now.minusSeconds(3600))||timestamp.isAfter(now.plusSeconds(60)))throw ApiCode.INVALID_REQUEST.failure();
  if(entry.attributes()!=null&&!entry.attributes().isObject())throw ApiCode.INVALID_REQUEST.failure();
  return new Entry(timestamp,entry.service(),entry.level(),LogSanitizer.text(entry.message()),entry.requestId(),entry.traceId(),entry.errorCode(),LogSanitizer.attributes(entry.attributes(),0),LogSanitizer.text(entry.exception()));
 }
 Accepted ingest(UUID env,Batch batch) {
  if(batch==null||batch.entries()==null||batch.entries().isEmpty()||batch.entries().size()>100)throw ApiCode.INVALID_REQUEST.failure();
  Instant now=Instant.now();var groups=new LinkedHashMap<String,List<List<String>>>();
  for(var raw:batch.entries()) {
   var entry=sanitize(raw,now);String line=json.writeValueAsString(entry);
   if(line.getBytes(StandardCharsets.UTF_8).length>16384)throw ApiCode.PAYLOAD_TOO_LARGE.failure();
   groups.computeIfAbsent(entry.level(),key->new ArrayList<>()).add(List.of(String.valueOf(entry.timestamp().getEpochSecond()*1_000_000_000L+entry.timestamp().getNano()),line));
  }
  var streams=new ArrayList<Object>();groups.forEach((level,values)->{
   values.sort(Comparator.comparingLong(value->Long.parseLong(value.getFirst())));
   // Keep label cardinality fixed: service/request IDs remain searchable JSON fields.
   streams.add(Map.of("stream",Map.of("app","external","level",level),"values",values));
  });
  byte[] body=json.writeValueAsBytes(Map.of("streams",streams));
  if(body.length>262144)throw ApiCode.PAYLOAD_TOO_LARGE.failure();
  // Atomic reservation is committed before transport. Ambiguous sends consume quota too.
  int accepted=db.update("""
   INSERT INTO log_ingestion_budgets(environment_id,day,reserved_bytes,requests) VALUES (?,(now() AT TIME ZONE 'UTC')::date,?,1)
   ON CONFLICT(environment_id,day) DO UPDATE SET reserved_bytes=log_ingestion_budgets.reserved_bytes+excluded.reserved_bytes,
   requests=log_ingestion_budgets.requests+1,minute_bucket=date_trunc('minute',now()),
   minute_requests=CASE WHEN log_ingestion_budgets.minute_bucket=date_trunc('minute',now()) THEN log_ingestion_budgets.minute_requests+1 ELSE 1 END
   WHERE log_ingestion_budgets.reserved_bytes+excluded.reserved_bytes<=? AND log_ingestion_budgets.requests<10000
   AND (log_ingestion_budgets.minute_bucket<>date_trunc('minute',now()) OR log_ingestion_budgets.minute_requests<120)
   """,env,body.length,DAILY_BYTES);
  if(accepted!=1)throw ApiCode.LOG_QUOTA_EXCEEDED.failure();
  send(env,"/loki/api/v1/push",body);return new Accepted(batch.entries().size());
 }
 Page query(UUID env,Instant from,Instant to,String service,String level,String text,String requestId,String traceId,int limit) {
  projects.findEnvironment(env);Instant now=Instant.now();to=to==null?now:to;from=from==null?to.minusSeconds(3600):from;
  if(limit<1||limit>200||from.isBefore(now.minus(Duration.ofDays(7)))||!from.isBefore(to)||to.isAfter(now.plusSeconds(60))||Duration.between(from,to).compareTo(Duration.ofHours(24))>0)throw ApiCode.INVALID_REQUEST.failure();
  var selector=new StringBuilder("{app=\"external\"");
  if(level!=null){if(!LEVELS.contains(level))throw ApiCode.INVALID_REQUEST.failure();selector.append(",level=").append(json.writeValueAsString(level));}
  selector.append("} | json");
  String[] names={"service","requestId","traceId"};String[] values={service,requestId,traceId};
  for(int i=0;i<names.length;i++)if(values[i]!=null){ExternalJobs.name(values[i],i==0?64:128);selector.append(" | ").append(names[i]).append("=").append(json.writeValueAsString(values[i]));}
  if(text!=null&&!text.isBlank()){length(text,200);selector.append(" |= ").append(json.writeValueAsString(text));}
  String path="/loki/api/v1/query_range?query="+URLEncoder.encode(selector.toString(),StandardCharsets.UTF_8)+"&start="+URLEncoder.encode(from.toString(),StandardCharsets.UTF_8)+"&end="+URLEncoder.encode(to.toString(),StandardCharsets.UTF_8)+"&direction=backward&limit="+limit;
  var response=send(env,path,null);var entries=new ArrayList<Entry>();
  try {
   var data=json.readTree(response);
   if(!data.path("status").asText().equals("success"))throw ApiCode.LOG_BACKEND_UNAVAILABLE.failure();
   for(var stream:data.path("data").path("result"))for(var value:stream.path("values"))entries.add(json.readValue(value.get(1).asText(),Entry.class));
  }catch(ApiCode.Failure failure){throw failure;}catch(Exception failure){throw ApiCode.LOG_BACKEND_UNAVAILABLE.failure();}
  entries.sort(Comparator.comparing(Entry::timestamp).reversed());return new Page(entries,entries.size()>=limit,from,to,7,DAILY_BYTES);
 }
 private byte[] send(UUID env,String path,byte[] body) {
  if(!slots.tryAcquire())throw ApiCode.RATE_LIMITED.failure();
  try {
   var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5)).header("X-Scope-OrgID",env.toString());
   if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
   var response=http.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
    if(response.statusCode()==429)throw ApiCode.LOG_QUOTA_EXCEEDED.failure();
    if(response.statusCode()!=200&&response.statusCode()!=204)throw ApiCode.LOG_BACKEND_UNAVAILABLE.failure();
    byte[] result=response.body();if(result.length>8*1024*1024)throw ApiCode.LOG_BACKEND_UNAVAILABLE.failure();return result;
  }catch(ApiCode.Failure failure){throw failure;}catch(InterruptedException failure){Thread.currentThread().interrupt();throw ApiCode.LOG_BACKEND_UNAVAILABLE.failure();}catch(Exception failure){throw ApiCode.LOG_BACKEND_UNAVAILABLE.failure();}finally{slots.release();}
 }
 @Scheduled(fixedDelay=3600000,initialDelay=3600000)void cleanup(){db.update("DELETE FROM log_ingestion_budgets WHERE day<(now() AT TIME ZONE 'UTC')::date-8");}
}
