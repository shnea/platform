package kr.shnea.platform.project;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class OutboxController {
    private final OutboxDelivery delivery;
    OutboxController(OutboxDelivery delivery) { this.delivery=delivery; }
    @GetMapping("/api/v1/admin/jobs/{id}/events")
    List<OutboxDelivery.Detail> events(@PathVariable UUID id) { return delivery.forJob(id); }
    @PostMapping("/api/v1/admin/events/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    OutboxDelivery.Delivery retry(@PathVariable UUID id,@AuthenticationPrincipal Jwt actor) { return delivery.retry(id,actor.getSubject()); }
}
