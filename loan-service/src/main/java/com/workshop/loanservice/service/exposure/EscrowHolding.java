package com.workshop.loanservice.service.exposure;

import java.math.BigDecimal;

public record EscrowHolding(BigDecimal balance) implements KnownExposureComponent {}
