package kr.shnea.platform.file;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import kr.shnea.platform.http.RequestTrace;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FilesHttpTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    FileAccess access; FilesService files; FileStore store; MockMvc mvc;
    final UUID id = UUID.randomUUID();
    final FileAccess.Context context = new FileAccess.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    @BeforeEach void setup() {
        access = mock(FileAccess.class); files = mock(FilesService.class); store = mock(FileStore.class);
        mvc = MockMvcBuilders.standaloneSetup(new FilesController(access, files, store))
            .setControllerAdvice(new FileErrors()).addFilters(new RequestTrace(), new FileHeaders()).build();
    }
    FilesService.Row row(String visibility) {
        return new FilesService.Row(id, context.projectId(), context.environmentId(), context.credentialId(), "안전.html", 3,
            "0".repeat(64), 3, "READY", visibility, "default", Instant.now(), Instant.now(), Instant.now(), "CREDENTIAL");
    }
    @Test void publicDownloadIsAttachmentNoStoreAndHeadDoesNotExtendRetention() throws Exception {
        when(files.downloadable(id)).thenReturn(row("PUBLIC"));
        var path=directory.resolve("file.bin");java.nio.file.Files.write(path,new byte[]{1,2,3});when(store.path(id)).thenReturn(path);
        mvc.perform(get("/api/v1/files/"+id+"/download"))
            .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1,2,3}))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
            .andExpect(content().contentType("application/octet-stream"));
        verify(access).requireActive(context.environmentId()); verify(files).finishDownload(isNull(),any(),eq(true));
        clearInvocations(files);
        mvc.perform(head("/api/v1/files/"+id+"/download")).andExpect(status().isOk());
        verify(files).finishDownload(isNull(),any(),eq(false));
    }
    @Test void privateAndConcurrentVisibilityChangesCannotUseAnonymousDownload() throws Exception {
        when(files.downloadable(id)).thenReturn(row("PRIVATE"));
        mvc.perform(get("/api/v1/files/"+id+"/download"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        verifyNoInteractions(store);
        when(files.downloadable(id)).thenReturn(row("PUBLIC"), row("PRIVATE"));
        mvc.perform(get("/api/v1/files/"+id+"/download")).andExpect(status().isNotFound());
        verifyNoInteractions(store);
    }
    @Test void singleRangesSupportSeekingAndRejectInvalidOrMultipleRanges() throws Exception {
        when(files.downloadable(id)).thenReturn(row("PUBLIC"));var path=directory.resolve("video.bin");
        java.nio.file.Files.write(path,new byte[]{1,2,3});when(store.path(id)).thenReturn(path);
        mvc.perform(get("/api/v1/files/"+id+"/download").header("Range","bytes=1-"))
            .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 1-2/3")).andExpect(content().bytes(new byte[]{2,3}));
        mvc.perform(get("/api/v1/files/"+id+"/download").header("Range","bytes=-1"))
            .andExpect(status().isPartialContent()).andExpect(content().bytes(new byte[]{3}));
        for(String range:java.util.List.of("bytes=9-","bytes=0-1,2-2","bytes=oops","bytes=-0"))
            mvc.perform(get("/api/v1/files/"+id+"/download").header("Range",range)).andExpect(status().isRequestedRangeNotSatisfiable());
        clearInvocations(files);
        mvc.perform(head("/api/v1/files/"+id+"/download").header("Range","bytes=1-")).andExpect(status().isOk()).andExpect(header().string("Content-Length","3"));
        verify(files).finishDownload(isNull(),any(),eq(false));
    }
    @Test void operationsEnforceDistinctScopesAndDoNotLeakInternalErrors() throws Exception {
        mvc.perform(post("/api/v1/files/uploads").contentType("application/json").content("{broken"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/files/not-a-uuid")).andExpect(status().isBadRequest());
        when(access.require(null, "files:read")).thenThrow(new FileFailure("INVALID_API_KEY", 401, "API 키가 필요합니다."));
        mvc.perform(get("/api/v1/files")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.requestId").isString());
        verifyNoInteractions(files);
        when(access.require("key", "files:delete")).thenReturn(context);
        mvc.perform(delete("/api/v1/files/"+id).header("X-Platform-Key", "key")).andExpect(status().isNoContent());
        verify(files).delete(id, context);
        when(access.require("key", "files:write")).thenReturn(context);
        mvc.perform(post("/api/v1/files/uploads/"+id+"/complete").header("X-Platform-Key", "key")).andExpect(status().isOk());
        verify(files).complete(id, context);
        when(access.require("key", "files:read")).thenThrow(new IllegalStateException("password=do-not-leak"));
        mvc.perform(get("/api/v1/files").header("X-Platform-Key", "key")).andExpect(status().isServiceUnavailable())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("do-not-leak"))));
    }
}
