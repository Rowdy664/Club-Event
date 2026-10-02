package com.campusconnect.service.payment;

import com.campusconnect.entity.enums.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Development / demo gateway that approves every charge instantly. It holds no credentials and
 * contacts no external service, so the app is fully runnable out of the box. Swap in a real
 * provider by adding another {@link PaymentGateway} bean and changing {@code app.payment.provider}.
 */
@Component
@Slf4j
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public GatewayChargeResult charge(GatewayChargeRequest request) {
        String providerReference = "MOCK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        log.info("[mock-gateway] Approving charge of {} {} (ref={})",
                request.amount(), request.currency(), request.reference());
        return new GatewayChargeResult(providerReference, PaymentStatus.SUCCESS, "Approved by mock gateway");
    }

    @Override
    public GatewayChargeResult verify(GatewayVerifyRequest request) {
        return new GatewayChargeResult(request.providerReference(), PaymentStatus.SUCCESS, "Verified by mock gateway");
    }

    @Override
    public GatewayChargeResult refund(String providerReference, BigDecimal amount) {
        String refundReference = "MOCK-RFND-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        log.info("[mock-gateway] Refunding {} for charge {} (refundRef={})", amount, providerReference, refundReference);
        return new GatewayChargeResult(refundReference, PaymentStatus.REFUNDED, "Refunded by mock gateway");
    }
}
