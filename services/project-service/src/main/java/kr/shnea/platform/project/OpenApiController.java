package kr.shnea.platform.project;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
class OpenApiController {
    private final ObjectNode specification;
    OpenApiController(JsonMapper json, @Value("${platform.mode}") String mode) throws IOException {
        try (var input = new ClassPathResource("openapi.json").getInputStream()) {
            specification = (ObjectNode) json.readTree(input);
        }
        if (!mode.equals("dev")) {
            var paths = (ObjectNode) specification.get("paths");
            for (String path : new java.util.ArrayList<>(paths.propertyNames())) {
                var item = (ObjectNode) paths.get(path);
                for (String method : new java.util.ArrayList<>(item.propertyNames()))
                    if (item.get(method).path("x-development-only").asBoolean(false)) item.remove(method);
                if (item.isEmpty()) paths.remove(path);
            }
        }
    }
    @GetMapping(value="/api/v1/admin/openapi", produces="application/json")
    ResponseEntity<ObjectNode> specification() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(specification);
    }
}
