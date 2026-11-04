package com.workshop.loanservice.service.exposure;

import java.math.BigDecimal;

public record OutstandingPrincipal(String loanAccountNumber, BigDecimal originalAmount,
                                   BigDecimal currentBalance) implements KnownExposureComponent {}
