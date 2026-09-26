package kr.shnea.platform.file;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.MDC;

@Service
class FilePublicShares {
    record Settings(String title,String description,Boolean showThumbnail,Long revision) {}
    private final JdbcTemplate db;private final TransactionTemplate tx;private final FilesService files;
    FilePublicShares(JdbcTemplate db,TransactionTemplate tx,FilesService files){this.db=db;this.tx=tx;this.files=files;}
    Settings settings(UUID id){return db.query("SELECT * FROM file_public_shares WHERE file_id=?",(r,n)->new Settings(r.getString("title"),r.getString("description"),r.getBoolean("show_thumbnail"),r.getLong("revision")),id).stream().findFirst().orElse(new Settings("","",true,0L));}
    Settings get(UUID id,FileAccess.Context context){files.detail(id,context);return settings(id);}
    Settings save(UUID id,FileAccess.Context context,Settings input){
        if(input==null||input.title()==null||input.description()==null||input.showThumbnail()==null||input.revision()==null
            ||input.title().length()>120||input.description().length()>300||input.title().chars().anyMatch(Character::isISOControl)||input.description().chars().anyMatch(Character::isISOControl))
            throw new FileFailure("FILE_SHARE_METADATA_INVALID",400,"제목은 120자, 설명은 300자 이내로 줄바꿈 없이 입력해 주세요.");
        return tx.execute(s->{
            db.queryForList("SELECT id FROM files WHERE id=? FOR UPDATE",id);
            var file=files.detail(id,context);
            if(!file.visibility().equals("PUBLIC"))throw new FileFailure("FILE_PUBLIC_REQUIRED",409,"공개 파일에서만 공유 미리보기를 설정할 수 있습니다.");
            if(!settings(id).revision().equals(input.revision()))throw new FileFailure("FILE_SHARE_METADATA_CHANGED",409,"공유 설정이 변경되었습니다. 다시 조회해 주세요.");
            db.update("INSERT INTO file_public_shares(file_id,title,description,show_thumbnail,revision) VALUES (?,?,?,?,1) ON CONFLICT(file_id) DO UPDATE SET title=excluded.title,description=excluded.description,show_thumbnail=excluded.show_thumbnail,revision=file_public_shares.revision+1",id,input.title().strip(),input.description().strip(),input.showThumbnail());
            db.update("INSERT INTO file_audit(file_id,environment_id,actor,action,request_id) VALUES (?,?,?,?,?)",id,context.environmentId(),context.actor(),"file.public-share.updated",MDC.get("requestId"));
            return settings(id);
        });
    }
}
