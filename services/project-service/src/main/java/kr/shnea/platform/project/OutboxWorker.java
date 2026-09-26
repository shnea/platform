package kr.shnea.platform.project;

import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="platform.events.enabled",havingValue="true")
class OutboxWorker {
    private final OutboxDelivery delivery;
    OutboxWorker(OutboxDelivery delivery) { this.delivery=delivery; }
    // ponytail: one delivery per process tick; no broker or parallel executor until throughput requires it.
    @Scheduled(fixedDelay=2000,initialDelay=5000)
    void tick() {
        var previous=MDC.getCopyOfContextMap();
        try {
            var claim=delivery.claim(); if (claim==null) return;
            MDC.put("requestId",claim.event().requestId()); MDC.put("eventId",claim.event().id().toString());
            try { delivery.deliver(claim); }
            catch (Exception error) {
                LoggerFactory.getLogger(OutboxWorker.class).warn("event_delivery_failed class={}",error.getClass().getName());
                delivery.failed(claim,error);
            }
        } catch (Exception error) {
            LoggerFactory.getLogger(OutboxWorker.class).warn("outbox_worker_unavailable class={}",error.getClass().getName());
        } finally { if (previous==null) MDC.clear(); else MDC.setContextMap(previous); }
    }
}
