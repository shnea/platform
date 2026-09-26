package kr.shnea.platform.file;

import java.io.IOException;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
class TicketDownloadController {
    private final DownloadTickets tickets;
    private final FileAccess access;
    private final FilesService files;
    private final FileStore store;
    TicketDownloadController(DownloadTickets tickets, FileAccess access, FilesService files, FileStore store) {
        this.tickets=tickets; this.access=access; this.files=files; this.store=store;
    }
    @GetMapping("/api/v1/files/downloads/{token}")
    void download(@PathVariable String token, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if(!request.getMethod().equals("GET")) throw new FileFailure("METHOD_NOT_ALLOWED",405,"일회용 다운로드는 GET 요청으로 사용해 주세요.");
        var id=tickets.consume(token);
        var row=files.downloadable(id);
        access.requireActive(row.environment());
        FileDelivery.send(files,store,files.downloadable(id),request,response);
    }
}
