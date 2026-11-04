package com.workshop.loanservice.service.exposure;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentRecord(LocalDate paymentDate, BigDecimal totalAmount, BigDecimal lateFee)
        implements KnownExposureComponent {}
