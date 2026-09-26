package kr.shnea.platform.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.*;
import org.springframework.http.ContentDisposition;

final class FileDelivery {
    private FileDelivery() {}
    static void send(FilesService files, FileStore store, FilesService.Row row, HttpServletRequest request, HttpServletResponse response) throws IOException {
        try(var input=store.open(row.id())) {
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(row.name(),StandardCharsets.UTF_8).build().toString());
            response.setContentLengthLong(row.size());
            if(request.getMethod().equals("HEAD")) return;
            input.transferTo(response.getOutputStream());
            response.flushBuffer(); files.used(row.id());
        }
    }
}
