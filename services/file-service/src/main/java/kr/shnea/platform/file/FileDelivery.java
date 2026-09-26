package kr.shnea.platform.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpRange;
import java.nio.file.*;

final class FileDelivery {
    private FileDelivery() {}
    static void send(FilesService files, FileStore store, FilesService.Row row, HttpServletRequest request, HttpServletResponse response) throws IOException {
        send(files,store.path(row.id()),row,"application/octet-stream",false,request,response);
    }
    static void send(FilesService files, Path path, FilesService.Row row,String type,boolean inline,HttpServletRequest request,HttpServletResponse response) throws IOException {
        long size=Files.size(path),start=0,end=size-1;
        String range=request.getHeader("If-Range")==null?request.getHeader("Range"):null;
        if(range!=null&&!request.getMethod().equals("HEAD")) {
            try {
                var ranges=HttpRange.parseRanges(range);if(ranges.size()!=1||size==0)throw new IllegalArgumentException();
                start=ranges.getFirst().getRangeStart(size);end=ranges.getFirst().getRangeEnd(size);
                if(start>=size||end<start)throw new IllegalArgumentException();
                response.setStatus(206);response.setHeader("Content-Range","bytes "+start+"-"+end+"/"+size);
            } catch(IllegalArgumentException e){response.setHeader("Content-Range","bytes */"+size);throw new FileFailure("FILE_RANGE_INVALID",416,"요청한 파일 범위를 제공할 수 없습니다.");}
        }
        var lease=files.beginDownload(row);boolean success=false;
        try(var input=Files.newInputStream(path)) {
            response.setContentType(type);
            response.setHeader("Content-Disposition",(inline?ContentDisposition.inline():ContentDisposition.attachment()).filename(row.name(),StandardCharsets.UTF_8).build().toString());
            response.setHeader("Accept-Ranges","bytes");response.setHeader("X-Frame-Options","SAMEORIGIN");
            if(inline)response.setHeader("Content-Security-Policy","default-src 'none'; img-src 'self'; media-src 'self'; frame-ancestors 'self'");
            response.setContentLengthLong(end-start+1);
            if(request.getMethod().equals("HEAD")) return;
            input.skipNBytes(start);long remaining=end-start+1;byte[] buffer=new byte[64*1024];
            while(remaining>0) {int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(count<0)throw new IOException("Unexpected end of file");files.checkDownload(lease);response.getOutputStream().write(buffer,0,count);remaining-=count;}
            response.flushBuffer();success=true;
        } finally {files.finishDownload(lease,row,success);}
    }
}
