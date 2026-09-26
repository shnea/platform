package kr.shnea.platform.project;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import kr.shnea.platform.http.RequestTrace;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
class ExternalJobController {
 record ClaimRequest(String queue,String workerId) {}
 record Heartbeat(Integer progress) {}
 record Report(JsonNode result,String errorCode,Boolean retryable) {}
 private final ProjectService projects;private final ExternalJobs jobs;
 ExternalJobController(ProjectService projects,ExternalJobs jobs){this.projects=projects;this.jobs=jobs;}
 private UUID env(String key,String scope){return projects.context(key,scope).environmentId();}
 private String actor(String key){return "credential:"+key.split("_",3)[1];}
 @PostMapping("/api/v1/jobs") @ResponseStatus(HttpStatus.ACCEPTED)
 ExternalJobs.Job submit(@RequestHeader(value="X-Platform-Key",required=false)String key,@RequestBody ExternalJobs.Submit body,HttpServletRequest request){UUID env=env(key,"jobs:write");return jobs.enqueue(env,body,actor(key),RequestTrace.id(request));}
 @GetMapping("/api/v1/jobs")
 ExternalJobs.Page list(@RequestHeader(value="X-Platform-Key",required=false)String key,@RequestParam(required=false)String queue,@RequestParam(required=false)String state,@RequestParam(defaultValue="20")int limit,@RequestParam(defaultValue="0")int offset){return jobs.list(env(key,"jobs:read"),queue,state,limit,offset);}
 @GetMapping("/api/v1/jobs/{id}")
 ExternalJobs.Detail detail(@RequestHeader(value="X-Platform-Key",required=false)String key,@PathVariable UUID id){return jobs.detail(env(key,"jobs:read"),id);}
 @PostMapping("/api/v1/jobs/claim")
 ResponseEntity<ExternalJobs.Claim> claim(@RequestHeader(value="X-Platform-Key",required=false)String key,@RequestBody ClaimRequest body){UUID env=env(key,"jobs:work");var claim=jobs.claim(env,body.queue(),body.workerId(),actor(key));return claim==null?ResponseEntity.noContent().build():ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(claim);}
 @PostMapping("/api/v1/jobs/{id}/heartbeat")
 ExternalJobs.Job heartbeat(@RequestHeader(value="X-Platform-Key",required=false)String key,@PathVariable UUID id,@RequestHeader(value="X-Job-Lease",required=false)String lease,@RequestBody Heartbeat body){return jobs.heartbeat(env(key,"jobs:work"),id,lease,body.progress());}
 @PostMapping("/api/v1/jobs/{id}/complete")
 ExternalJobs.Job complete(@RequestHeader(value="X-Platform-Key",required=false)String key,@PathVariable UUID id,@RequestHeader(value="X-Job-Lease",required=false)String lease,@RequestBody Report body){UUID env=env(key,"jobs:work");return jobs.report(env,id,lease,true,body.result(),null,false,actor(key));}
 @PostMapping("/api/v1/jobs/{id}/fail")
 ExternalJobs.Job fail(@RequestHeader(value="X-Platform-Key",required=false)String key,@PathVariable UUID id,@RequestHeader(value="X-Job-Lease",required=false)String lease,@RequestBody Report body){UUID env=env(key,"jobs:work");return jobs.report(env,id,lease,false,null,body.errorCode(),Boolean.TRUE.equals(body.retryable()),actor(key));}
 @PostMapping("/api/v1/jobs/{id}/cancel")
 ExternalJobs.Job cancel(@RequestHeader(value="X-Platform-Key",required=false)String key,@PathVariable UUID id){UUID env=env(key,"jobs:write");return jobs.cancel(env,id,actor(key));}
 @PostMapping("/api/v1/jobs/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
 ExternalJobs.Job retry(@RequestHeader(value="X-Platform-Key",required=false)String key,@PathVariable UUID id,HttpServletRequest request){UUID env=env(key,"jobs:write");return jobs.retry(env,id,actor(key),RequestTrace.id(request));}
 @GetMapping("/api/v1/admin/environments/{env}/external-jobs")
 ExternalJobs.Page adminList(@PathVariable UUID env,@RequestParam(required=false)String queue,@RequestParam(required=false)String state,@RequestParam(defaultValue="20")int limit,@RequestParam(defaultValue="0")int offset){return jobs.list(env,queue,state,limit,offset);}
 @GetMapping("/api/v1/admin/environments/{env}/external-jobs/{id}")
 ExternalJobs.Detail adminDetail(@PathVariable UUID env,@PathVariable UUID id){return jobs.detail(env,id);}
 @PostMapping("/api/v1/admin/environments/{env}/external-jobs/{id}/cancel")
 ExternalJobs.Job adminCancel(@PathVariable UUID env,@PathVariable UUID id,@AuthenticationPrincipal Jwt actor){return jobs.cancel(env,id,actor.getSubject());}
 @PostMapping("/api/v1/admin/environments/{env}/external-jobs/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
 ExternalJobs.Job adminRetry(@PathVariable UUID env,@PathVariable UUID id,@AuthenticationPrincipal Jwt actor,HttpServletRequest request){return jobs.retry(env,id,actor.getSubject(),RequestTrace.id(request));}
}
