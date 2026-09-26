package kr.shnea.platform.file;

import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
class FileContract {
    private final FileAccess access;
    private final JsonNode specification;
    FileContract(FileAccess access) throws IOException {
        this.access = access;
        try (var input = new ClassPathResource("openapi.json").getInputStream()) {
            specification = new JsonMapper().readTree(input);
        }
    }
    @GetMapping("/api/v1/files/openapi")
    Object specification(@RequestHeader(value="X-Platform-Key", required=false) String key) {
        access.require(key, "files:read");
        return specification;
    }
    @GetMapping("/api/v1/files/admin/openapi")
    Object administratorSpecification() { return specification; }
}
