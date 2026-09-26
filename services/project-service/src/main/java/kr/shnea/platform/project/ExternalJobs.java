package kr.shnea.platform.project;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Durable pull queue. Host workers execute business code; no code or URLs execute here. */
@Service
class ExternalJobs {
 record Submit(UUID requestId,String queue,JsonNode payload,Integer maxAttempts,Instant runAfter) {}
 record Job(UUID id,UUID projectId,UUID environmentId,String queue,String state,JsonNode payload,
   int attempts,int maxAttempts,int progress,Instant nextRunAt,String workerId,Instant leaseUntil,
   JsonNode result,String errorCode,UUID retryOf,String requestId,Instant createdAt,Instant completedAt) {}
 record Attempt(int attempt,String workerId,String state,String errorCode,Instant startedAt,Instant endedAt) {}
 record Detail(Job job,List<Attempt> attempts) {}
 record Claim(Job job,String leaseToken) {}
 record Page(List<Job> items,boolean hasMore) {}
 private final JdbcTemplate db; private final TransactionTemplate tx; private final ProjectService projects;
 private final JsonMapper json=new JsonMapper();
 ExternalJobs(JdbcTemplate db,TransactionTemplate tx,ProjectService projects){this.db=db;this.tx=tx;this.projects=projects;}
 private static Timestamp time(Instant value){return value==null?null:Timestamp.from(value);}
 private static Instant instant(ResultSet rs,String name)throws SQLException{var value=rs.getTimestamp(name);return value==null?null:value.toInstant();}
 private Job map(ResultSet rs)throws SQLException{return new Job(rs.getObject("id",UUID.class),rs.getObject("project_id",UUID.class),rs.getObject("environment_id",UUID.class),rs.getString("queue"),rs.getString("state"),json.readTree(rs.getString("payload")),rs.getInt("attempts"),rs.getInt("max_attempts"),rs.getInt("progress"),instant(rs,"next_run_at"),rs.getString("worker_id"),instant(rs,"lease_until"),rs.getString("result")==null?null:json.readTree(rs.getString("result")),rs.getString("error_code"),rs.getObject("retry_of",UUID.class),rs.getString("request_id"),instant(rs,"created_at"),instant(rs,"completed_at"));}
 private Job job(UUID env,UUID id,boolean lock){return db.query("SELECT * FROM external_jobs WHERE environment_id=? AND id=?"+(lock?" FOR UPDATE":""),(rs,n)->map(rs),env,id).stream().findFirst().orElseThrow(ApiCode.RESOURCE_NOT_FOUND::failure);}
 private void lockEnvironment(UUID env){
  var environment=projects.findEnvironment(env);projects.lockProject(environment.projectId());
  var available=db.queryForObject("SELECT count(*) FROM environments e JOIN projects p ON p.id=e.project_id WHERE e.id=? AND e.state='READY' AND p.status='ACTIVE'",Integer.class,env);
  if(available!=1)throw ApiCode.ENVIRONMENT_NOT_READY.failure();
 }
 static String name(String value,int length){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,"+(length-1)+"}"))throw ApiCode.INVALID_REQUEST.failure();return value;}
 private String data(JsonNode node){if(node==null||!node.isObject())throw ApiCode.INVALID_REQUEST.failure();String value=json.writeValueAsString(node);if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw ApiCode.PAYLOAD_TOO_LARGE.failure();return value;}
 Job enqueue(UUID env,Submit request,String actor,String trace){
  if(request==null||request.requestId()==null)throw ApiCode.INVALID_REQUEST.failure();
  name(request.queue(),64);String payload=data(request.payload());int max=request.maxAttempts()==null?3:request.maxAttempts();
  if(max<1||max>10||request.runAfter()!=null&&request.runAfter().isAfter(Instant.now().plus(Duration.ofDays(7))))throw ApiCode.INVALID_REQUEST.failure();
  return tx.execute(status->{lockEnvironment(env);
   var old=db.query("SELECT * FROM external_jobs WHERE environment_id=? AND request_key=?",(rs,n)->map(rs),env,request.requestId());
   if(!old.isEmpty()){
    boolean same=db.queryForObject("SELECT queue=? AND payload=?::jsonb AND max_attempts=? AND scheduled_at IS NOT DISTINCT FROM ? FROM external_jobs WHERE id=?",Boolean.class,request.queue(),payload,max,time(request.runAfter()),old.getFirst().id());
    if(!same)throw ApiCode.EXTERNAL_JOB_REQUEST_CONFLICT.failure();return old.getFirst();
   }
   capacity(env);UUID id=UUID.randomUUID();UUID project=projects.findEnvironment(env).projectId();
   db.update("INSERT INTO external_jobs(id,project_id,environment_id,request_key,queue,payload,state,max_attempts,scheduled_at,next_run_at,request_id) VALUES (?,?,?,?,?,?::jsonb,'QUEUED',?,?,coalesce(?,now()),?)",id,project,env,request.requestId(),request.queue(),payload,max,time(request.runAfter()),time(request.runAfter()),trace);
   audit(env,id,actor,"queued");return job(env,id,false);
  });
 }
 private void capacity(UUID env){
  var counts=db.queryForMap("SELECT count(*) AS total,count(*) FILTER(WHERE state IN ('QUEUED','RUNNING','RETRY_WAIT')) AS active FROM external_jobs WHERE environment_id=?",env);
  if(((Number)counts.get("total")).longValue()>=10000||((Number)counts.get("active")).longValue()>=1000)throw ApiCode.EXTERNAL_JOB_CAPACITY.failure();
 }
 Page list(UUID env,String queue,String state,int limit,int offset){
  projects.findEnvironment(env);if(limit<1||limit>100||offset<0||offset>10000)throw ApiCode.INVALID_PAGINATION.failure();
  var sql=new StringBuilder("SELECT * FROM external_jobs WHERE environment_id=?");var args=new ArrayList<Object>();args.add(env);
  if(queue!=null){name(queue,64);sql.append(" AND queue=?");args.add(queue);}
  if(state!=null){if(!List.of("QUEUED","RUNNING","RETRY_WAIT","SUCCEEDED","FAILED","CANCELLED").contains(state))throw ApiCode.INVALID_REQUEST.failure();sql.append(" AND state=?");args.add(state);}
  args.add(limit+1);args.add(offset);var rows=db.query(sql+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(rs,n)->map(rs),args.toArray());
  return new Page(rows.stream().limit(limit).toList(),rows.size()>limit);
 }
 Detail detail(UUID env,UUID id){return new Detail(job(env,id,false),db.query("SELECT * FROM external_job_attempts WHERE job_id=? ORDER BY attempt",(rs,n)->new Attempt(rs.getInt("attempt"),rs.getString("worker_id"),rs.getString("state"),rs.getString("error_code"),instant(rs,"started_at"),instant(rs,"ended_at")),id));}
 Claim claim(UUID env,String queue,String worker,String actor){name(queue,64);name(worker,100);return tx.execute(status->{
  lockEnvironment(env);recover(env);
  var rows=db.query("SELECT * FROM external_jobs WHERE environment_id=? AND queue=? AND state IN ('QUEUED','RETRY_WAIT') AND next_run_at<=clock_timestamp() ORDER BY next_run_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",(rs,n)->map(rs),env,queue);
  if(rows.isEmpty())return null;var job=rows.getFirst();String token=UUID.randomUUID().toString()+UUID.randomUUID();
  db.update("UPDATE external_jobs SET state='RUNNING',attempts=attempts+1,worker_id=?,lease_hash=?,lease_until=clock_timestamp()+interval '60 seconds',progress=0,updated_at=now(),error_code=NULL WHERE id=?",worker,ProjectService.hash(token),job.id());
  db.update("INSERT INTO external_job_attempts(job_id,attempt,worker_id,state) VALUES (?,?,?,'RUNNING')",job.id(),job.attempts()+1,worker);
  audit(env,job.id(),actor,"claimed");return new Claim(job(env,job.id(),false),token);
 });}
 private void requireToken(UUID id,String token){if(token==null||token.length()>150)throw ApiCode.EXTERNAL_JOB_LEASE_LOST.failure();var hash=db.queryForObject("SELECT lease_hash FROM external_jobs WHERE id=?",String.class,id);if(hash==null||!java.security.MessageDigest.isEqual(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8),ProjectService.hash(token).getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw ApiCode.EXTERNAL_JOB_LEASE_LOST.failure();}
 private void liveLease(Job job){if(!job.state().equals("RUNNING")||!db.queryForObject("SELECT lease_until>clock_timestamp() FROM external_jobs WHERE id=?",Boolean.class,job.id()))throw ApiCode.EXTERNAL_JOB_LEASE_LOST.failure();}
 Job heartbeat(UUID env,UUID id,String token,Integer progress){return tx.execute(status->{lockEnvironment(env);var job=job(env,id,true);requireToken(id,token);liveLease(job);if(progress!=null&&(progress<0||progress>100))throw ApiCode.INVALID_REQUEST.failure();db.update("UPDATE external_jobs SET lease_until=clock_timestamp()+interval '60 seconds',progress=?,updated_at=now() WHERE id=?",progress==null?job.progress():progress,id);return job(env,id,false);});}
 Job report(UUID env,UUID id,String token,boolean success,JsonNode result,String error,boolean retryable,String actor){
  String output=success?data(result==null?json.createObjectNode():result):null;if(!success)name(error,80);
  return tx.execute(status->{lockEnvironment(env);var job=job(env,id,true);requireToken(id,token);
   if(!job.state().equals("RUNNING")){
    if(success&&job.state().equals("SUCCEEDED")&&Objects.equals(job.result(),json.readTree(output)))return job;
    if(!success&&List.of("RETRY_WAIT","FAILED").contains(job.state())&&Objects.equals(job.errorCode(),error)&&Boolean.valueOf(retryable).equals(db.queryForObject("SELECT report_retryable FROM external_jobs WHERE id=?",Boolean.class,id)))return job;
    throw ApiCode.EXTERNAL_JOB_LEASE_LOST.failure();
   }
   liveLease(job);String state=success?"SUCCEEDED":retryable&&job.attempts()<job.maxAttempts()?"RETRY_WAIT":"FAILED";
   db.update("UPDATE external_jobs SET report_retryable=? WHERE id=?",success?null:retryable,id);
   db.update("UPDATE external_jobs SET state=?,result=?::jsonb,error_code=?,lease_until=NULL,progress=?,updated_at=now(),next_run_at=now()+(? * interval '1 second'),completed_at=CASE WHEN ?='RETRY_WAIT' THEN NULL ELSE now() END WHERE id=?",state,output,success?null:error,success?100:job.progress(),Math.min(60,10*job.attempts()),state,id);
   db.update("UPDATE external_job_attempts SET state=?,error_code=?,ended_at=now() WHERE job_id=? AND attempt=?",success?"SUCCEEDED":"FAILED",success?null:error,id,job.attempts());
   audit(env,id,actor,state.toLowerCase(Locale.ROOT));return job(env,id,false);
  });
 }
 Job cancel(UUID env,UUID id,String actor){return tx.execute(status->{var job=job(env,id,true);if(job.state().equals("CANCELLED"))return job;if(!List.of("QUEUED","RETRY_WAIT").contains(job.state()))throw ApiCode.JOB_NOT_CANCELLABLE.failure();db.update("UPDATE external_jobs SET state='CANCELLED',completed_at=now(),updated_at=now(),lease_hash=NULL WHERE id=?",id);audit(env,id,actor,"cancelled");return job(env,id,false);});}
 Job retry(UUID env,UUID id,String actor,String trace){return tx.execute(status->{lockEnvironment(env);var old=job(env,id,true);if(!old.state().equals("FAILED"))throw ApiCode.JOB_NOT_RETRYABLE.failure();var existing=db.query("SELECT * FROM external_jobs WHERE retry_of=?",(rs,n)->map(rs),id);if(!existing.isEmpty())return existing.getFirst();capacity(env);UUID next=UUID.randomUUID();db.update("INSERT INTO external_jobs(id,project_id,environment_id,request_key,queue,payload,state,max_attempts,retry_of,request_id) VALUES (?,?,?,?,?,?::jsonb,'QUEUED',?,?,?)",next,old.projectId(),env,UUID.randomUUID(),old.queue(),json.writeValueAsString(old.payload()),old.maxAttempts(),id,trace);audit(env,next,actor,"retried");return job(env,next,false);});}
 private void recover(UUID env){
  for(var job:db.query("SELECT * FROM external_jobs WHERE environment_id=? AND state='RUNNING' AND lease_until<=clock_timestamp() ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED",(rs,n)->map(rs),env)){
   String state=job.attempts()>=job.maxAttempts()?"FAILED":"RETRY_WAIT";
   db.update("UPDATE external_job_attempts SET state='ABANDONED',error_code='WORKER_INTERRUPTED',ended_at=now() WHERE job_id=? AND attempt=?",job.id(),job.attempts());
   db.update("UPDATE external_jobs SET state=?,error_code='WORKER_INTERRUPTED',lease_hash=NULL,lease_until=NULL,next_run_at=now()+interval '10 seconds',updated_at=now(),completed_at=CASE WHEN ?='FAILED' THEN now() ELSE NULL END WHERE id=?",state,state,job.id());audit(env,job.id(),"system:external-jobs","lease_expired");
  }
 }
 @Scheduled(fixedDelay=5000,initialDelay=5000) void maintain(){
  for(UUID env:db.queryForList("SELECT DISTINCT environment_id FROM external_jobs WHERE state='RUNNING' AND lease_until<=now() LIMIT 100",UUID.class))tx.executeWithoutResult(s->recover(env));
  db.update("DELETE FROM external_jobs WHERE id IN (SELECT id FROM external_jobs WHERE completed_at<now()-interval '30 days' ORDER BY completed_at LIMIT 500)");
 }
 private void audit(UUID env,UUID id,String actor,String action){db.update("INSERT INTO audit_events(actor,action,target_id,environment_id) VALUES (?,?,?,?)",actor,"external.job."+action,id,env);}
}
