package kr.shnea.platform.project;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
class CommonLogController {
 private final ProjectService projects;private final CommonLogs logs;
 CommonLogController(ProjectService projects,CommonLogs logs){this.projects=projects;this.logs=logs;}
 @PostMapping("/api/v1/logs") @ResponseStatus(HttpStatus.ACCEPTED)
 CommonLogs.Accepted ingest(@RequestHeader(value="X-Platform-Key",required=false)String key,@RequestBody CommonLogs.Batch body){return logs.ingest(projects.context(key,"logs:write").environmentId(),body);}
 @GetMapping("/api/v1/logs")
 CommonLogs.Page query(@RequestHeader(value="X-Platform-Key",required=false)String key,@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,@RequestParam(required=false)String service,@RequestParam(required=false)String level,@RequestParam(required=false)String text,@RequestParam(required=false)String requestId,@RequestParam(required=false)String traceId,@RequestParam(defaultValue="100")int limit){return logs.query(projects.context(key,"logs:read").environmentId(),from,to,service,level,text,requestId,traceId,limit);}
 @GetMapping("/api/v1/admin/environments/{env}/logs")
 CommonLogs.Page admin(@PathVariable UUID env,@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to,@RequestParam(required=false)String service,@RequestParam(required=false)String level,@RequestParam(required=false)String text,@RequestParam(required=false)String requestId,@RequestParam(required=false)String traceId,@RequestParam(defaultValue="100")int limit){return logs.query(env,from,to,service,level,text,requestId,traceId,limit);}
}
